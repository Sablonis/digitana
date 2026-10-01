package ch.digitana.dienstplan

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import ch.digitana.dienstplan.crash.CrashReporter
import kotlinx.coroutines.launch

/**
 * WorkManager wird nicht automatisch beim Prozessstart initialisiert (siehe Manifest),
 * sondern bei der ersten Verwendung über [Configuration.Provider]. So läuft ein Job im
 * Hintergrund nie, bevor [container] existiert.
 */
class DienstplanApp : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.ERROR)
            .build()

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        container = AppContainer(this)
        container.initialize()

        // Der Sync läuft, solange die App im Vordergrund ist (mit kurzem Nachlauf). Beim
        // Öffnen und Verlassen gilt der Plan als gesehen: Grundlage für Benachrichtigungen.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.syncController.onForeground()
                markPlanSeen()
            }

            override fun onStop(owner: LifecycleOwner) {
                container.syncController.onBackground()
                markPlanSeen()
            }
        })

        // Nach einem Netzwechsel sofort neu verbinden statt den Backoff abzuwarten.
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        container.syncController.reconnectNow()
                    }
                },
            )
        }
    }

    private fun markPlanSeen() {
        container.scope.launch {
            if (container.awaitReady(READY_TIMEOUT_MILLIS)) container.shiftAlerts.planSeen()
        }
    }

    private companion object {
        const val READY_TIMEOUT_MILLIS = 15_000L
    }
}
