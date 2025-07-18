package com.valhalla.loki.model

import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppInfo(
    var appName: String? = null,
    var packageName: String = "",
    var versionName: String? = "",
    var versionCode: Int = 0,
    var minSdk: Int = 0,
    var targetSdk: Int = 0,
    var isSystem: Boolean = false,
    var installerPackageName: String? = null,
    var publicSourceDir: String? = null,
    var splitPublicSourceDirs: List<String> = emptyList(),
    var enabled: Boolean = true,
    var enabledState: Int = COMPONENT_ENABLED_STATE_DEFAULT
) {
    fun asString() = Json.encodeToString(this)

    companion object {
        fun fromString(string: String?) = try {
            if (string != null)
                Json.decodeFromString<AppInfo>(string)
            else null
        } catch (_: Exception) {
            null
        }

    }

}