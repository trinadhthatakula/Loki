package com.valhalla.loki.model

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * Reads installed-app metadata out of `PackageManager`.
 *
 * Every method here blocks: a package sweep loads each app's label, which opens that app's resource
 * APK. Callers belong on a background dispatcher.
 */
@Suppress("DEPRECATION")
class AppInfoGrabber(private val context: Context) {

    private val packageManager: PackageManager get() = context.packageManager

    /**
     * Every installed app, in `PackageManager`'s own order. Partition on [AppInfo.isSystem] to
     * split them.
     *
     * One package sweep. The `getUserApps()` and `getSystemApps()` pair this replaces swept twice
     * and loaded every label twice, to throw half of each result away.
     */
    val allApps: List<AppInfo>
        get() = installedPackages().mapNotNull { it.toAppInfo() }

    /**
     * One app, or `null` when nothing by that name is installed.
     *
     * A single-package query. This used to read [allApps] *twice* — once to test membership and
     * again to pick the match — and [allApps] was itself two full sweeps. Four sweeps and four
     * label loads per installed app, per lookup, on the path that builds the saved-logs list once
     * per captured package.
     */
    fun getAppInfo(packageName: String): AppInfo? = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            packageManager.getPackageInfo(packageName, 0)
        }
        info.toAppInfo()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * An app's launcher icon, or `null` when it has none or is not installed.
     *
     * Blocking, like everything else here: `getApplicationIcon` opens the app's resource APK. This
     * exists so the saved-logs ViewModel can load icons on a background dispatcher — the
     * contribution called `PackageManager` from inside a `remember` in every list row, which is
     * disk I/O on the main thread per row.
     */
    fun getAppIcon(packageName: String): Drawable? = getAppIcon(packageName, context)

    @SuppressLint("QueryPermissionsNeeded")
    private fun installedPackages(): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            packageManager.getInstalledPackages(0)
        }

    /** `null` when the package has no `ApplicationInfo`, which happens for a partially installed app. */
    private fun PackageInfo.toAppInfo(): AppInfo? {
        val app = applicationInfo ?: return null
        return AppInfo(
            appName = app.loadLabel(packageManager).toString(),
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            minSdk = app.minSdkVersion,
            targetSdk = app.targetSdkVersion,
            isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            installerPackageName = packageManager.getInstallerPackageName(packageName),
            publicSourceDir = app.publicSourceDir,
            splitPublicSourceDirs = app.splitPublicSourceDirs?.toList() ?: emptyList(),
            enabled = app.enabled,
            enabledState = packageManager.getApplicationEnabledSetting(packageName),
        )
    }
}
