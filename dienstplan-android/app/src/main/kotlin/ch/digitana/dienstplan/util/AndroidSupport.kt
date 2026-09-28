package ch.digitana.dienstplan.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import ch.digitana.dienstplan.core.util.Logger

/** Logcat – wird nur in Debug-Builds verwendet. Der Kern übergibt nie Geheimnisse oder Namen. */
object AndroidLogger : Logger {
    override fun debug(tag: String, message: String) {
        Log.d("Dienstplan/$tag", message)
    }

    override fun warn(tag: String, message: String, error: Throwable?) {
        Log.w("Dienstplan/$tag", message, error)
    }
}

fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Kopiert Text in die Zwischenablage. Mit [sensitive] wird der Inhalt als vertraulich
 * markiert: Android 13+ zeigt ihn dann nicht in der Vorschau, Tastaturen schlagen ihn
 * nicht vor.
 */
fun copyToClipboard(context: Context, label: String, text: String, sensitive: Boolean) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(label, text)
    if (sensitive) {
        clip.description.extras = PersistableBundle().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            } else {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
    }
    clipboard.setPrimaryClip(clip)
}

/** Öffnet das Android-Teilen-Menü. */
fun shareText(context: Context, chooserTitle: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(send, chooserTitle)
    if (context.findActivity() == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
