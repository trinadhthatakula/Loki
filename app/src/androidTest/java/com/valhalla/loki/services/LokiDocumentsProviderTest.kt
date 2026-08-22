package com.valhalla.loki.services

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException

/**
 * Exercises [LokiDocumentsProvider] through a real [ContentResolver] on a device.
 *
 * These are instrumented rather than unit tests because the thing worth testing *is* the framework
 * contract: URI matching, the projection columns a picker actually reads, and what crosses the
 * provider boundary when a caller asks for something it should not get. A fake would only test the
 * assumptions.
 *
 * The test runs in Loki's own UID, so the `MANAGE_DOCUMENTS` permission on the manifest entry is
 * bypassed — a same-UID caller skips the provider permission check. That is the point: it lets the
 * read-only and traversal guards be tested directly, without a signature-privileged harness.
 *
 * Everything is created under [TEST_PACKAGE_DIR] and removed again, so a run cannot destroy logs a
 * real capture left on the device.
 */
@RunWith(AndroidJUnit4::class)
class LokiDocumentsProviderTest {

    private lateinit var context: Context
    private lateinit var resolver: ContentResolver
    private lateinit var testDir: File
    private lateinit var logFile: File

    /** `/test.loki.provider/probe.log` — the document ID of [logFile]. */
    private val logDocumentId get() = "$TEST_PACKAGE_DOC_ID/$LOG_NAME"

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        resolver = context.contentResolver
        testDir = File(File(context.filesDir, "logs"), TEST_PACKAGE_DIR)
        testDir.deleteRecursively()
        assertTrue("could not create $testDir", testDir.mkdirs())
        logFile = File(testDir, LOG_NAME)
        logFile.writeText(LOG_CONTENT)
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun queryRoots_advertisesOneReadOnlyLocalRoot() {
        resolver.query(DocumentsContract.buildRootsUri(AUTHORITY), null, null, null, null)
            .use { cursor ->
                assertNotNull(cursor)
                requireNotNull(cursor)
                assertEquals("expected exactly one root", 1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    LokiDocumentsProvider.ROOT_ID,
                    cursor.getString(cursor.getColumnIndexOrThrow(Root.COLUMN_ROOT_ID)),
                )
                assertEquals(
                    LokiDocumentsProvider.ROOT_DOCUMENT_ID,
                    cursor.getString(cursor.getColumnIndexOrThrow(Root.COLUMN_DOCUMENT_ID)),
                )
                val flags = cursor.getInt(cursor.getColumnIndexOrThrow(Root.COLUMN_FLAGS))
                assertTrue("search is advertised", flags and Root.FLAG_SUPPORTS_SEARCH != 0)
                assertTrue("isChild is advertised", flags and Root.FLAG_SUPPORTS_IS_CHILD != 0)
                // The whole reason the create/rename sinks were removed: nothing may be minted here.
                assertEquals(
                    "the root must not advertise create",
                    0,
                    flags and Root.FLAG_SUPPORTS_CREATE,
                )
            }
    }

    @Test
    fun queryChildDocuments_listsTheSeededLogWithATextMimeType() {
        val children = childDocumentIds(TEST_PACKAGE_DOC_ID)
        assertEquals(listOf(logDocumentId), children)

        resolver.query(documentUri(logDocumentId), null, null, null, null).use { cursor ->
            assertNotNull(cursor)
            requireNotNull(cursor)
            assertTrue(cursor.moveToFirst())
            assertEquals(
                LOG_NAME,
                cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)),
            )
            // MimeTypeMap has no "log" entry, so this is the mapping the provider adds by hand.
            assertEquals(
                "text/plain",
                cursor.getString(cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)),
            )
            assertEquals(
                LOG_CONTENT.length.toLong(),
                cursor.getLong(cursor.getColumnIndexOrThrow(Document.COLUMN_SIZE)),
            )
            val flags = cursor.getInt(cursor.getColumnIndexOrThrow(Document.COLUMN_FLAGS))
            assertTrue("delete is offered", flags and Document.FLAG_SUPPORTS_DELETE != 0)
            assertEquals("write is not", 0, flags and Document.FLAG_SUPPORTS_WRITE)
        }
    }

    @Test
    fun openDocument_readsContentButRefusesEveryWriteMode() {
        resolver.openInputStream(documentUri(logDocumentId)).use { stream ->
            assertEquals(LOG_CONTENT, stream?.readBytes()?.decodeToString())
        }
        // "rw" and "wt" are what a caller reaches for when it wants to edit in place; "wa" is the
        // append a log viewer might try. All three are the same answer.
        for (mode in listOf("w", "rw", "wt", "wa")) {
            try {
                resolver.openFileDescriptor(documentUri(logDocumentId), mode)?.close()
                throw AssertionError("mode '$mode' was accepted; the root must be read-only")
            } catch (expected: FileNotFoundException) {
                assertTrue(expected.message.orEmpty().contains("read-only"))
            }
        }
        // Still intact — a rejected open must not have truncated anything.
        assertEquals(LOG_CONTENT, logFile.readText())
    }

    @Test
    fun querySearchDocuments_matchesOnDisplayName() {
        val hits = searchDocumentIds(LOG_NAME.substringBefore('.'))
        assertTrue("expected $logDocumentId in $hits", logDocumentId in hits)
        assertTrue(searchDocumentIds("no-such-file-anywhere").isEmpty())
        // A search scoped to some other root must not leak this one's documents.
        assertTrue(searchDocumentIds(LOG_NAME, rootId = "not-loki-logs").isEmpty())
    }

    @Test
    fun traversalDocumentIdsAreRejected() {
        // The three shapes §1.2 found: relative escape, absolute path, and an escape that lands
        // back inside filesDir but outside the logs root.
        for (id in listOf("/../databases", "/data/data/com.valhalla.loki", "/../datastore")) {
            assertNull(
                "queryDocument accepted '$id'",
                resolver.query(documentUri(id), null, null, null, null)?.also { it.close() },
            )
        }
        assertDeleteFails(LokiDocumentsProvider.ROOT_DOCUMENT_ID)
        assertDeleteFails("/../databases")
    }

    @Test
    fun isChildDocument_answersOnCanonicalPaths() {
        val root = LokiDocumentsProvider.ROOT_DOCUMENT_ID
        assertTrue(isChild(root, logDocumentId))
        assertTrue(isChild(TEST_PACKAGE_DOC_ID, logDocumentId))
        assertFalse(isChild(logDocumentId, TEST_PACKAGE_DOC_ID))
        // Would have answered true against absolutePath, which is what the contribution compared.
        assertFalse(isChild(TEST_PACKAGE_DOC_ID, "$TEST_PACKAGE_DOC_ID/../../databases"))
    }

    @Test
    fun deleteDocument_removesTheFileAndTheListingFollows() {
        assertTrue(
            DocumentsContract.deleteDocument(resolver, documentUri(logDocumentId)),
        )
        assertFalse("the file is gone from disk", logFile.exists())
        assertTrue("and from the listing", childDocumentIds(TEST_PACKAGE_DOC_ID).isEmpty())
        // Deleting it twice is a caller acting on a stale listing. It has to fail cleanly, as
        // "no such document" — not as a crash and not as a second success.
        assertDeleteFails(logDocumentId)
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun documentUri(documentId: String): Uri =
        DocumentsContract.buildDocumentUri(AUTHORITY, documentId)

    /**
     * A refused delete.
     *
     * `DocumentsContract.deleteDocument` returns `false` *or* rethrows, depending on the caller's
     * target SDK — Loki targets 36, so a provider-side [FileNotFoundException] comes back as itself.
     * Both outcomes mean "did not delete", which is the only thing worth asserting.
     */
    private fun assertDeleteFails(documentId: String) {
        val deleted = try {
            DocumentsContract.deleteDocument(resolver, documentUri(documentId))
        } catch (_: FileNotFoundException) {
            false
        }
        assertFalse("delete of '$documentId' should have been refused", deleted)
    }

    private fun isChild(parentDocumentId: String, documentId: String): Boolean =
        DocumentsContract.isChildDocument(resolver, documentUri(parentDocumentId), documentUri(documentId))

    private fun childDocumentIds(parentDocumentId: String): List<String> =
        documentIdsFrom(DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId))

    /**
     * A search has to be issued **both** ways at once.
     *
     * On API 29 and up `DocumentsProvider.query` routes a search URI to the `Bundle` overload and
     * hard-fails with `NullPointerException: queryArgs can not be null` if the caller did not send
     * one — which is what the legacy `query(uri, projection, null, null, null)` produces. On API 28
     * the same routing reads the needle off the URI instead. Sending both covers `minSdk 28` through
     * `targetSdk 36`, and is what DocumentsUI itself does.
     */
    private fun searchDocumentIds(
        query: String,
        rootId: String = LokiDocumentsProvider.ROOT_ID,
    ): List<String> = documentIdsFrom(
        uri = DocumentsContract.buildSearchDocumentsUri(AUTHORITY, rootId, query),
        queryArgs = Bundle().apply { putString(DocumentsContract.QUERY_ARG_DISPLAY_NAME, query) },
    )

    private fun documentIdsFrom(uri: Uri, queryArgs: Bundle? = null): List<String> =
        resolver.query(uri, null, queryArgs, null).use { cursor ->
            assertNotNull("null cursor for $uri", cursor)
            requireNotNull(cursor)
            val column = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(column))
            }
        }

    private companion object {
        val AUTHORITY: String = LokiDocumentsProvider.AUTHORITY

        /**
         * Not a real package name, so a run can never collide with — or delete — logs a genuine
         * capture wrote for an app that happens to be installed.
         */
        const val TEST_PACKAGE_DIR = "test.loki.provider"
        const val TEST_PACKAGE_DOC_ID = "/$TEST_PACKAGE_DIR"
        const val LOG_NAME = "1750000000000.log"
        const val LOG_CONTENT = "08-22 03:00:00.000  1000  1000 I Probe: seeded by an instrumented test\n"
    }
}
