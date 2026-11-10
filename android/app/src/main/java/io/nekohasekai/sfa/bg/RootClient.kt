package io.nekohasekai.sfa.bg

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Clashbox runs without root: the upstream root-service client is replaced
// with an always-unavailable stand-in so the platform interface falls back
// to the regular VPN service paths. The shell environment helpers below are
// kept because local (same-uid) shell sessions still use them.
object RootClient {
    private val _rootAvailable = MutableStateFlow<Boolean?>(false)
    val rootAvailable: StateFlow<Boolean?> = _rootAvailable

    suspend fun checkRootAvailable(): Boolean = false

    suspend fun registerNeighborTableCallback(callback: INeighborTableCallback) {
    }

    suspend fun unregisterNeighborTableCallback(callback: INeighborTableCallback) {
    }

    suspend fun lookupSFTPServer(): String {
        error("not supported")
    }

    suspend fun openShellSession(
        user: String,
        command: String?,
        env: Array<String>,
        term: String?,
        rows: Int,
        cols: Int,
    ): IRootShellSession {
        error("missing root permission")
    }

    suspend fun openBridge(
        bridgeName: String,
        mtu: Int,
        inet4Port: String,
        inet6Port: String,
        ruleIndex: Int,
        routeTable: Int,
    ): IBridgeSession {
        error("missing root permission")
    }

    suspend fun startAutoRedirect(
        options: ByteArray,
        handler: IAutoRedirectHandler,
    ): IAutoRedirectSession {
        error("missing root permission")
    }
}

internal fun parseEnvArray(sshEnv: Array<out String>?): MutableMap<String, String> {
    val env = mutableMapOf<String, String>()
    sshEnv?.forEach { entry ->
        val idx = entry.indexOf('=')
        if (idx > 0) env[entry.substring(0, idx)] = entry.substring(idx + 1)
    }
    return env
}

internal fun buildBasicEnvironment(
    sshEnv: Array<out String>?,
    shell: String,
    home: String,
    term: String?,
): Array<String> {
    val env = parseEnvArray(sshEnv)
    env["HOME"] = home
    env["PATH"] = "/system/bin:/system/xbin:/vendor/bin"
    env["SHELL"] = shell
    env["TMPDIR"] = "/data/local/tmp"
    if (!term.isNullOrEmpty()) {
        env["TERM"] = term
    }
    addAndroidSystemEnvironment(env)
    return env.map { (k, v) -> "$k=$v" }.toTypedArray()
}

internal fun addAndroidSystemEnvironment(env: MutableMap<String, String>) {
    val androidVars = arrayOf(
        "ANDROID_ASSETS", "ANDROID_DATA", "ANDROID_ROOT", "ANDROID_STORAGE",
        "EXTERNAL_STORAGE", "ASEC_MOUNTPOINT", "LOOP_MOUNTPOINT",
        "ANDROID_RUNTIME_ROOT", "ANDROID_ART_ROOT",
        "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT",
        "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "SYSTEMSERVERCLASSPATH",
    )
    for (name in androidVars) {
        val value = System.getenv(name)
        if (value != null) {
            env[name] = value
        }
    }
}
