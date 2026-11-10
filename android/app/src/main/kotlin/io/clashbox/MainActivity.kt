package io.clashbox

import android.app.Activity
import android.content.Intent
import io.clashbox.plugins.AppPlugin
import io.clashbox.plugins.BatteryPlugin
import io.clashbox.plugins.ServicePlugin
import io.clashbox.plugins.TilePlugin
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        flutterEngine.plugins.add(AppPlugin())
        flutterEngine.plugins.add(BatteryPlugin())
        flutterEngine.plugins.add(ServicePlugin())
        flutterEngine.plugins.add(TilePlugin())
        ServiceState.attachFlutterEngine(flutterEngine)
    }

    override fun onResume() {
        super.onResume()
        currentActivityRef = WeakReference(this)
    }

    override fun onDestroy() {
        if (currentActivityRef?.get() === this) {
            currentActivityRef = null
        }
        flutterEngine?.let(ServiceState::detachFlutterEngine)
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_PERMISSION) {
            val continuation = pendingVpnContinuation
            pendingVpnContinuation = null
            continuation?.resume(resultCode == Activity.RESULT_OK)
        }
    }

    companion object {
        private const val REQUEST_VPN_PERMISSION = 0xCF01

        private var currentActivityRef: WeakReference<MainActivity>? = null
        private var pendingVpnContinuation: kotlin.coroutines.Continuation<Boolean>? = null

        val currentActivity: MainActivity?
            get() = currentActivityRef?.get()

        suspend fun requestVpnPermission(intent: Intent): Boolean {
            val activity = currentActivity ?: return false
            return suspendCancellableCoroutine { continuation ->
                pendingVpnContinuation = continuation
                continuation.invokeOnCancellation {
                    if (pendingVpnContinuation === continuation) {
                        pendingVpnContinuation = null
                    }
                }
                @Suppress("DEPRECATION")
                activity.startActivityForResult(intent, REQUEST_VPN_PERMISSION)
            }
        }
    }
}
