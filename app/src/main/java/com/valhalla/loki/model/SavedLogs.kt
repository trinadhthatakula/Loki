package com.valhalla.loki.model

import java.io.File

/**
 * Represents a single saved log file, holding its timestamp and file path.
 * The timestamp is derived from the filename.
 */
data class SavedLog(
    val timestamp: Long,
    val file: File
)

/**
 * Represents an application that has one or more saved logs.
 * It holds information about the app and a list of its associated log files.
 */
data class LoggedApp(
    val appInfo: AppInfo, // Contains appName, packageName, etc.
    val logs: List<SavedLog>
)
