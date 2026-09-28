package ch.digitana.dienstplan.crash

import android.content.Context
import android.os.Build
import ch.digitana.dienstplan.BuildConfig
import ch.digitana.dienstplan.core.util.CrashReportFormatter
import java.io.File

/**
 * Lokaler Absturzbericht ohne sensible Daten. Nichts wird automatisch versendet; beim
 * nächsten Start kann die Nutzerin oder der Nutzer den Bericht kopieren oder verwerfen.
 */
object CrashReporter {
    private const val FILE_NAME = "crash-report.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val report = CrashReportFormatter.format(
                    throwable = error,
                    threadName = thread.name,
                    appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                    device = "${Build.MANUFACTURER} ${Build.MODEL}",
                    timeMillis = System.currentTimeMillis(),
                )
                file(appContext).writeText(report)
            } catch (ignored: Throwable) {
                // Der Bericht darf den eigentlichen Absturz nicht verdecken.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun pendingReport(context: Context): String? =
        file(context).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun file(context: Context) = File(context.applicationContext.noBackupFilesDir, FILE_NAME)
}
