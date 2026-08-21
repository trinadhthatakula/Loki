package com.valhalla.loki.services

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.valhalla.loki.BuildConfig
import com.valhalla.loki.R
import com.valhalla.loki.model.logsDir
import java.io.File
import java.io.FileNotFoundException

/**
 * Exposes `filesDir/logs` to the system document picker as a read-only root called "Loki logs".
 *
 * This is the supported way to make private storage reachable from a file manager, and it is why
 * saved logs did **not** have to move to `getExternalFilesDir()` — see
 * `docs/review-anon-contribution.md` §1.1. `filesDir` is private on every API level; a
 * `DocumentsProvider` hands out access one document at a time, to a caller the user picked, instead
 * of leaving the whole tree world-readable on API 28.
 *
 * The root is deliberately **read and delete only**. A log reader has no reason to let another
 * application create, rename or rewrite a captured log, and every one of those operations is a
 * place where a caller-supplied `displayName` becomes a filesystem path. §1.2 found the traversal
 * in exactly those three sinks; removing them removes the class of bug rather than guarding it.
 *
 * ### Document IDs
 *
 * A document ID is a **root-relative** POSIX path, with [ROOT_DOCUMENT_ID] for the root itself:
 * `/`, `/com.example.app`, `/com.example.app/1750000000000.log`. The contribution used the
 * absolute filesystem path, which put `/data/user/0/com.valhalla.loki/files/...` into every URI
 * handed to another application and into anything that logs those URIs. Relative IDs also make
 * [isChildDocument] and [findDocumentPath] answerable by string comparison on values this class
 * minted, rather than on paths a caller supplied.
 *
 * Every ID still goes through [resolve], which canonicalises and re-checks containment, because a
 * relative ID can still say `../../databases`.
 */
class LokiDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    // A getter, not `by lazy`: `context` is null until onCreate() and a cached lazy would capture
    // whatever it was at first touch. filesDir is a cheap field read on the Context.
    private val rootDir: File
        get() = requireCtx().logsDir

    private fun requireCtx(): Context = context
        ?: throw IllegalStateException("LokiDocumentsProvider queried before onCreate()")

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        // So the root is visible in a picker before the first capture has ever run. The result is
        // ignored on purpose: filesDir is always writable, and a root row that cannot be listed is
        // still better feedback than no root at all.
        rootDir.mkdirs()
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
            add(Root.COLUMN_TITLE, ROOT_TITLE)
            add(Root.COLUMN_SUMMARY, "Captured logcat output")
            // No FLAG_SUPPORTS_CREATE: the root is read-only to everyone but Loki. FLAG_LOCAL_ONLY
            // keeps it out of pickers that only want cloud-backed roots, which this never is.
            add(
                Root.COLUMN_FLAGS,
                Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_SEARCH,
            )
            add(Root.COLUMN_ICON, R.mipmap.launch)
            add(Root.COLUMN_MIME_TYPES, "text/*")
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val file = resolve(documentId)
        // "No such document" is how a picker learns its listing is stale. Without this check a
        // deleted — or never-existing — ID inside the root still produced a row, because
        // File.length() answers 0 and File.getName() answers something for any path at all.
        if (!file.exists()) throw FileNotFoundException("No such document: $documentId")
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        addFileRow(cursor, file)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val parent = resolve(parentDocumentId)
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        parent.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.forEach { addFileRow(cursor, it) }
        // Without this the picker shows a stale listing for as long as it stays open — the
        // contribution never registered one, which is half of why deletes appeared to do nothing.
        cursor.setNotificationUri(requireCtx().contentResolver, childrenUri(parentDocumentId))
        return cursor
    }

    /**
     * Backs `Root.FLAG_SUPPORTS_SEARCH`, which the contribution advertised without implementing —
     * so search in the picker silently returned nothing.
     *
     * Matching is on the display name only. Searching log *contents* through SAF would stream every
     * saved byte to whichever app asked, which is not something a search box should do quietly.
     */
    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val needle = query.trim().lowercase()
        if (rootId != ROOT_ID || needle.isEmpty()) return cursor
        val root = rootDir
        var emitted = 0
        // maxDepth caps the walk: FileTreeWalk follows directory symlinks, and a bounded walk is
        // cheaper to reason about than proving none can exist. Real depth here is 2.
        for (file in root.walkTopDown().maxDepth(SEARCH_MAX_DEPTH)) {
            if (emitted >= SEARCH_RESULT_LIMIT) break
            if (file == root || !file.name.lowercase().contains(needle)) continue
            addFileRow(cursor, file)
            emitted++
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        // Canonical, not absolute. The contribution compared absolutePath, so
        // ("/root", "/root/../etc/passwd") answered true.
        val parent = runCatching { canonicalOf(resolve(parentDocumentId)) }.getOrNull() ?: return false
        val child = runCatching { canonicalOf(resolve(documentId)) }.getOrNull() ?: return false
        return child.startsWith(parent + File.separator)
    }

    override fun findDocumentPath(
        parentDocumentId: String?,
        childDocumentId: String,
    ): DocumentsContract.Path {
        val stopAt = canonicalOf(resolve(parentDocumentId ?: ROOT_DOCUMENT_ID))
        val ids = ArrayDeque<String>()
        var walker: File? = resolve(childDocumentId)
        while (walker != null) {
            ids.addFirst(documentIdOf(walker))
            if (canonicalOf(walker) == stopAt) break
            walker = walker.parentFile
        }
        if (walker == null) {
            throw FileNotFoundException("$childDocumentId is not below ${parentDocumentId.orEmpty()}")
        }
        return DocumentsContract.Path(
            if (parentDocumentId == null) ROOT_ID else null,
            ids.toList(),
        )
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        // Read-only, enforced rather than merely un-advertised. Nothing in queryRoots or
        // addFileRow offers a write flag, so a write mode arriving here is a caller ignoring the
        // contract; honouring it would let a MANAGE_DOCUMENTS holder rewrite a captured log in
        // place, and a log the user cannot trust is worse than no log.
        if (mode.any { it == 'w' || it == 'W' || it == 't' || it == 'a' }) {
            throw FileNotFoundException("The $ROOT_TITLE root is read-only (mode '$mode')")
        }
        val file = resolve(documentId)
        if (!file.isFile) throw FileNotFoundException("Not a file: $documentId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun deleteDocument(documentId: String) {
        val file = resolve(documentId)
        if (documentIdOf(file) == ROOT_DOCUMENT_ID) {
            throw FileNotFoundException("The $ROOT_TITLE root itself cannot be deleted")
        }
        if (!file.exists()) throw FileNotFoundException("No such document: $documentId")
        // deleteRecursively() reports false on a partial delete, and a half-deleted directory that
        // reported success is how a picker ends up showing files that are already gone.
        if (!file.deleteRecursively()) {
            throw FileNotFoundException("Failed to delete ${file.name}")
        }
        val parent = file.parentFile?.takeIf { it.startsWith(rootDir) } ?: rootDir
        requireCtx().contentResolver.notifyChange(childrenUri(documentIdOf(parent)), null)
    }

    override fun getDocumentType(documentId: String): String = mimeTypeOf(resolve(documentId))

    // --- paths -------------------------------------------------------------------------------

    /**
     * Turns a caller-supplied document ID into a file that is provably inside [rootDir].
     *
     * Throws [FileNotFoundException], not [IllegalArgumentException]: these methods declare the
     * former, and it crosses the Binder boundary as the "no such document" the framework already
     * handles. The contribution's `require()` surfaced in the *calling* app as an
     * IllegalArgumentException from a provider it does not own.
     */
    private fun resolve(documentId: String): File {
        val root = canonicalOf(rootDir)
        val relative = documentId.trim('/')
        if (relative.isEmpty()) return File(root)
        val target = canonicalOf(File(root, relative))
        if (target != root && !target.startsWith(root + File.separator)) {
            throw FileNotFoundException("Document is outside the $ROOT_TITLE root: $documentId")
        }
        return File(target)
    }

    /** The inverse of [resolve]. Only ever called with files this class produced. */
    private fun documentIdOf(file: File): String {
        val root = canonicalOf(rootDir)
        val path = canonicalOf(file)
        if (path == root) return ROOT_DOCUMENT_ID
        return ROOT_DOCUMENT_ID + path.removePrefix(root + File.separator)
    }

    /**
     * Canonical path, falling back to absolute.
     *
     * `canonicalPath` resolves symlinks and touches the filesystem, so it can throw IOException —
     * for a path that does not exist yet, among other reasons. Falling back to `absolutePath` keeps
     * the containment check meaningful in that case, because [resolve] builds the candidate from an
     * already-canonicalised root.
     */
    private fun canonicalOf(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    private fun childrenUri(documentId: String): Uri =
        DocumentsContract.buildChildDocumentsUri(AUTHORITY, documentId)

    // --- rows --------------------------------------------------------------------------------

    private fun addFileRow(cursor: MatrixCursor, file: File) {
        val documentId = documentIdOf(file)
        val isDir = file.isDirectory
        // No FLAG_SUPPORTS_WRITE and no FLAG_DIR_SUPPORTS_CREATE — see the class comment. Delete is
        // offered because "get rid of this log" is a thing a user does from a file manager, and it
        // takes no caller-supplied name.
        var flags = 0
        if (documentId != ROOT_DOCUMENT_ID) flags = flags or Document.FLAG_SUPPORTS_DELETE

        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(
                Document.COLUMN_DISPLAY_NAME,
                if (documentId == ROOT_DOCUMENT_ID) ROOT_TITLE else file.name,
            )
            // length() on a directory is a filesystem-dependent number that means nothing to a
            // picker, so leave it unset rather than reporting 4096 bytes of "size".
            add(Document.COLUMN_SIZE, if (isDir) null else file.length())
            add(Document.COLUMN_MIME_TYPE, mimeTypeOf(file))
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun mimeTypeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        val extension = file.extension.lowercase()
        // MimeTypeMap has no entry for "log" on Android, so without this every saved log came back
        // as application/octet-stream and no picker would offer to preview it.
        if (extension == "log") return "text/plain"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    companion object {
        /** Must match `android:authorities` on the provider in AndroidManifest.xml. */
        val AUTHORITY: String = BuildConfig.APPLICATION_ID + ".documents"

        /** Stable across installs and versions; it is persisted inside URIs the system keeps. */
        const val ROOT_ID = "loki-logs"

        /** The document ID of the root. Document IDs are root-relative, so this is just "/". */
        const val ROOT_DOCUMENT_ID = "/"

        private const val ROOT_TITLE = "Loki logs"

        private const val SEARCH_MAX_DEPTH = 4
        private const val SEARCH_RESULT_LIMIT = 256

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
