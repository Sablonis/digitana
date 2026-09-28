package ch.digitana.dienstplan

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import ch.digitana.dienstplan.crash.CrashReporter

class DienstplanApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        container = AppContainer(this)
        container.initialize()

        // Der Sync läuft, solange die App im Vordergrund ist (mit kurzem Nachlauf).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                container.syncController.onForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                container.syncController.onBackground()
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
}
