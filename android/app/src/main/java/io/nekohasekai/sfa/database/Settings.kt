package io.nekohasekai.sfa.database

import io.nekohasekai.sfa.bg.VPNService

// Minimal stand-in for the SFA settings store, exposing only what the copied
// service layer reads. Clashbox runs VPN mode only; per-app proxy and system
// proxy toggles stay off, and the selected profile is driven by the core
// plugin (see ProfileManager).
object Settings {
    const val PER_APP_PROXY_EXCLUDE = 1
    const val PER_APP_PROXY_INCLUDE = 2

    object DataStore {
        suspend fun initialize() {}

        fun flush() {}
    }

    val dataStore = DataStore

    @Volatile
    var selectedProfile: Long = -1L

    @Volatile
    var startedByUser: Boolean = false

    val autoRedirect: Boolean = false
    val perAppProxyEnabled: Boolean = false
    val systemProxyEnabled: Boolean = false
    val allowBypass: Boolean = true
    val dynamicNotification: Boolean = false

    fun rebuildServiceMode() {}

    fun serviceClass(): Class<*> = VPNService::class.java

    fun getEffectivePerAppProxyList(): List<String> = emptyList()

    fun getEffectivePerAppProxyMode(): Int = PER_APP_PROXY_EXCLUDE
}
