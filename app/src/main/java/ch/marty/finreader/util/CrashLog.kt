package ch.marty.finreader.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A sideloaded app has no crash reporter and nobody watching logcat, so the
 * uncaught-exception handler writes the stack trace to a file that survives the
 * restart. Settings → Diagnostics shows it.
 */
object CrashLog {

    private const val FILE_NAME = "crash.log"
    private const val MAX_CHARS = 64 * 1024

    @Volatile
    private var file: File? = null

    @Volatile
    private var appVersion: String = "?"

    fun install(context: Context) {
        val app = context.applicationContext
        file = File(app.filesDir, FILE_NAME)
        appVersion = AppVersion.full(app)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record("Uncaught on thread \"${thread.name}\"", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Also usable for caught-but-unexpected failures in workers and the service. */
    fun record(what: String, error: Throwable) {
        val target = file ?: return
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val entry = buildString {
            append("=== ").append(timestamp()).append(" · ").append(what).append('\n')
            append("app ").append(appVersion)
                .append(" · Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(')')
                .append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append(trace).append('\n')
        }
        runCatching {
            val existing = if (target.exists()) target.readText() else ""
            target.writeText((existing + entry).takeLast(MAX_CHARS))
        }
    }

    fun read(): String = runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()

    fun clear() {
        runCatching { file?.delete() }
    }

    /**
     * An app may read its own logcat without any permission, which catches the
     * frames Android logged around a crash that never reached our handler.
     */
    fun recentLogcat(maxLines: Int = 400): String = runCatching {
        val process = ProcessBuilder(
            "logcat", "-d", "-v", "time", "--pid=${android.os.Process.myPid()}",
        ).redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        process.destroy()
        lines.takeLast(maxLines).joinToString("\n")
    }.getOrElse { "logcat unavailable: ${it.message}" }

    /** Everything worth pasting into a bug report, in one string. */
    fun report(): String = buildString {
        append("FinReader diagnostics · ").append(timestamp()).append('\n')
        append("app ").append(appVersion)
            .append(" · Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(')')
            .append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n\n")
        val crashes = read()
        append("--- crash log ---\n")
        append(crashes.ifBlank { "(no crashes recorded)\n" })
        append("\n--- recent logcat (this process) ---\n")
        append(recentLogcat())
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
}
