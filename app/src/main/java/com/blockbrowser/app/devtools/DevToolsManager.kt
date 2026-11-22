package com.blockbrowser.app.devtools

data class ConsoleMessageItem(
    val level: String, // DEBUG, LOG, WARNING, ERROR
    val message: String,
    val source: String,
    val timestamp: Long = System.currentTimeMillis()
)

object DevToolsManager {
    private val consoleLogs = mutableListOf<ConsoleMessageItem>()
    private const val MAX_LOGS = 250

    fun addConsoleMessage(entry: ConsoleMessageItem) {
        synchronized(consoleLogs) {
            consoleLogs.add(0, entry)
            if (consoleLogs.size > MAX_LOGS) {
                consoleLogs.removeAt(consoleLogs.size - 1)
            }
        }
    }

    fun getLogs(): List<ConsoleMessageItem> {
        synchronized(consoleLogs) {
            return consoleLogs.toList()
        }
    }

    fun clearLogs() {
        synchronized(consoleLogs) {
            consoleLogs.clear()
        }
    }
}
