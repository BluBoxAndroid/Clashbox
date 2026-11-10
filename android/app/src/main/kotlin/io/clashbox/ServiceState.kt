package io.clashbox

import android.net.VpnService
import io.clashbox.common.GlobalState
import io.clashbox.core.SingBoxCore
import io.clashbox.models.SharedState
import io.clashbox.plugins.ServicePlugin
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.PluginRegistry
import io.nekohasekai.sfa.bg.BoxService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RunState {
    STARTED,
    STARTING,
    STOPPED,
    STOPPING,
}

// Owns the VPN run state on top of the official sing-box service layer
// (io.nekohasekai.sfa.bg.BoxService). Liveness is tracked through the
// libbox command client, so a slow or unresponsive core degrades to a
// stopped state instead of wedging the UI.
object ServiceState {
    private val _runState = MutableStateFlow(RunState.STOPPED)
    val runState: StateFlow<RunState> = _runState.asStateFlow()

    @Volatile
    var isRunRequested: Boolean = false
        private set

    private var flutterEngine: FlutterEngine? = null

    @Volatile
    private var startTimeMillis: Long = 0

    @Volatile
    private var connectedSinceMillis: Long = 0

    init {
        SingBoxCore.onConfigChangedWhileRunning = {
            if (isRunRequested) {
                requestRestart()
            }
        }
    }

    fun attachFlutterEngine(engine: FlutterEngine) {
        flutterEngine = engine
    }

    fun detachFlutterEngine(engine: FlutterEngine) {
        if (flutterEngine === engine) {
            flutterEngine = null
        }
    }

    private fun plugin(): ServicePlugin? {
        val registry = flutterEngine?.plugins ?: return null
        return runCatching {
            registry.get(ServicePlugin::class.java) as? ServicePlugin
        }.getOrNull()
    }

    private fun setState(state: RunState) {
        _runState.value = state
        if (state == RunState.STOPPED) {
            plugin()?.notifyStopped()
        }
    }

    suspend fun awaitRunTime(): Long {
        val deadline = System.currentTimeMillis() + 30_000
        while (_runState.value == RunState.STARTING && System.currentTimeMillis() < deadline) {
            delay(100)
        }
        return startTimeMillis
    }

    fun syncSharedState(state: SharedState) {
        state.vpnOptions?.let { SingBoxCore.updateVpnOptions(it) }
        SingBoxCore.updateProfileName(state.currentProfileName)
        val setupParams = state.setupParams ?: return
        val selected = setupParams.get("selected-map")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return
        SingBoxCore.updateSelectedMap(
            selected.entrySet().associate { entry ->
                entry.key to entry.value.asString
            },
        )
    }

    fun requestStart() {
        if (_runState.value == RunState.STARTED || _runState.value == RunState.STARTING) {
            isRunRequested = true
            return
        }
        isRunRequested = true
        setState(RunState.STARTING)
        GlobalState.launch {
            if (!ensureVpnPermission()) {
                isRunRequested = false
                setState(RunState.STOPPED)
                return@launch
            }
            runCatching { BoxService.start() }
                .onFailure { error ->
                    GlobalState.log("Unable to start sing-box service: $error")
                    isRunRequested = false
                    setState(RunState.STOPPED)
                    return@launch
                }
            startMonitor()
        }
    }

    fun requestStop() {
        isRunRequested = false
        if (_runState.value == RunState.STOPPED) {
            return
        }
        setState(RunState.STOPPING)
        GlobalState.launch {
            runCatching { BoxService.stop() }
            waitUntilStopped()
        }
    }

    fun requestRestart() {
        if (!isRunRequested) {
            return
        }
        GlobalState.launch {
            runCatching { BoxService.stop() }
            waitUntilStopped()
            if (isRunRequested) {
                setState(RunState.STARTING)
                runCatching { BoxService.start() }
                    .onFailure {
                        isRunRequested = false
                        setState(RunState.STOPPED)
                        return@launch
                    }
                startMonitor()
            }
        }
    }

    fun handleStartAction() {
        requestStart()
    }

    fun handleStopAction() {
        requestStop()
    }

    fun handleToggleAction() {
        if (_runState.value == RunState.STARTED || _runState.value == RunState.STARTING) {
            requestStop()
        } else {
            requestStart()
        }
    }

    fun handleVpnRevokeAction() {
        isRunRequested = false
        SingBoxCore.onServiceStopped()
        setState(RunState.STOPPED)
    }

    private fun startMonitor() {
        GlobalState.launch {
            while (true) {
                delay(1000)
                when (_runState.value) {
                    RunState.STARTING -> {
                        if (SingBoxCore.serviceConnected) {
                            startTimeMillis = System.currentTimeMillis()
                            connectedSinceMillis = startTimeMillis
                            SingBoxCore.onServiceStarted()
                            setState(RunState.STARTED)
                        }
                    }
                    RunState.STARTED -> {
                        if (SingBoxCore.serviceConnected) {
                            connectedSinceMillis = System.currentTimeMillis()
                        } else if (
                            connectedSinceMillis > 0 &&
                            System.currentTimeMillis() - connectedSinceMillis > 10_000
                        ) {
                            SingBoxCore.onServiceStopped()
                            isRunRequested = false
                            setState(RunState.STOPPED)
                            break
                        }
                    }
                    else -> break
                }
            }
        }
    }

    private suspend fun waitUntilStopped() {
        var waited = 0L
        while (SingBoxCore.serviceConnected && waited < 15_000) {
            delay(200)
            waited += 200
        }
        delay(300)
        SingBoxCore.onServiceStopped()
        setState(RunState.STOPPED)
    }

    private suspend fun ensureVpnPermission(): Boolean {
        val context = GlobalState.application
        if (VpnService.prepare(context) == null) {
            return true
        }
        val activity = MainActivity.currentActivity ?: return false
        val intent = VpnService.prepare(activity) ?: return true
        if (MainActivity.requestVpnPermission(intent)) {
            return true
        }
        repeat(20) {
            delay(500)
            if (VpnService.prepare(context) == null) {
                return true
            }
        }
        return false
    }
}
