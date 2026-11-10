package io.clashbox.service.models

import com.google.gson.JsonObject

// Mirrors the Dart VpnOptions model as serialized into SharedState.
data class VpnOptions(
    val enable: Boolean = false,
    val port: Int = 0,
    val ipv6: Boolean = false,
    val dnsHijacking: Boolean = false,
    val accessControlProps: JsonObject? = null,
    val allowBypass: Boolean = true,
    val systemProxy: Boolean = false,
    val bypassDomain: List<String> = emptyList(),
    val stack: String = "mixed",
    val routeAddress: List<String> = emptyList(),
    val mtu: Int = 0,
    val congestionController: String = "",
)
