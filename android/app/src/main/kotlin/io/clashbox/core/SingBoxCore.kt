package io.clashbox.core

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.clashbox.service.models.VpnOptions
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.CommandClient as SfaCommandClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.time.Instant

// Implements the FlClash core wire protocol (JSON method calls, see
// lib/core/method.dart) on top of the official sing-box libbox.
object SingBoxCore {
    private const val DEFAULT_CONFIG_NAME = "Clashbox"

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var eventSink: ((ByteArray) -> Unit)? = null

    @Volatile
    var onConfigChangedWhileRunning: (() -> Unit)? = null

    @Volatile
    private var homeDir: String? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var rawConfig: String? = null

    @Volatile
    private var profileName: String = DEFAULT_CONFIG_NAME

    @Volatile
    private var selectedMap: Map<String, String> = emptyMap()

    @Volatile
    private var vpnOptions: VpnOptions? = null

    @Volatile
    private var updateParams: JsonObject? = null

    @Volatile
    private var testUrl: String = "https://www.gstatic.com/generate_204"

    @Volatile
    private var coreEpoch = 0

    @Volatile
    private var routeVersion = 0

    @Volatile
    private var lastStatus: StatusMessage? = null

    @Volatile
    private var lastGroups: List<OutboundGroup> = emptyList()

    private data class ConnSnap(
        val id: String,
        val createdAt: Long,
        val closedAt: Long,
        val network: String,
        val source: String,
        val destination: String,
        val domain: String,
        val protocol: String,
        val rule: String,
        val outbound: String,
        val outboundType: String,
        val chain: List<String>,
        val processPath: String,
        val userId: Int,
        var uplinkTotal: Long,
        var downlinkTotal: Long,
    )

    private val connections = LinkedHashMap<String, ConnSnap>()

    @Volatile
    private var logForwarding = false

    @Volatile
    var serviceConnected = false
        private set

    private var commandClient: SfaCommandClient? = null

    fun updateVpnOptions(options: VpnOptions) {
        vpnOptions = options
    }

    fun updateProfileName(name: String) {
        if (name.isNotBlank()) {
            profileName = name
        }
    }

    fun updateSelectedMap(map: Map<String, String>) {
        if (map.isNotEmpty()) {
            selectedMap = map
        }
    }

    fun currentConfigFile(): File? {
        val dir = homeDir?.let { File(it, "clashbox") } ?: return null
        return File(dir, "current-config.json")
    }

    fun hasConfig(): Boolean = rawConfig != null

    fun onServiceStarted() {
        coreEpoch++
        connectClient()
    }

    fun onServiceStopped() {
        serviceConnected = false
        commandClient?.disconnect()
        commandClient = null
        lastStatus = null
        lastGroups = emptyList()
        synchronized(connections) { connections.clear() }
    }

    private fun connectClient() {
        commandClient?.disconnect()
        val handler = object : SfaCommandClient.Handler {
            override fun onConnected() {
                serviceConnected = true
                applySelections()
            }

            override fun onDisconnected() {
                serviceConnected = false
            }

            override fun updateStatus(status: StatusMessage) {
                lastStatus = status
            }

            override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
                lastGroups = newGroups.toList()
            }

            override fun appendLogs(message: List<LogEntry>) {
                if (!logForwarding) {
                    return
                }
                val events = JsonArray()
                for (entry in message) {
                    val data = JsonObject()
                    data.addProperty("LogLevel", logLevelName(entry.level))
                    data.addProperty("Payload", entry.message ?: "")
                    data.addProperty("dateTime", Instant.now().toString())
                    val event = JsonObject()
                    event.addProperty("type", "log")
                    event.add("data", data)
                    events.add(event)
                }
                pushEvents(events)
            }

            override fun writeConnectionEvents(events: io.nekohasekai.libbox.ConnectionEvents) {
                synchronized(connections) {
                    if (events.reset) {
                        connections.clear()
                    }
                    val iterator = events.iterator()
                    while (iterator.hasNext()) {
                        val event = iterator.next()
                        val connection = event.connection ?: continue
                        val closed = event.closedAt > 0 || connection.closedAt > 0
                        if (closed) {
                            connections.remove(event.id)
                            continue
                        }
                        val chain = mutableListOf<String>()
                        val chainIterator = connection.chain()
                        while (chainIterator.hasNext()) {
                            chain.add(chainIterator.next())
                        }
                        connections[connection.id] = ConnSnap(
                            id = connection.id,
                            createdAt = connection.createdAt,
                            closedAt = connection.closedAt,
                            network = connection.network ?: "",
                            source = connection.source ?: "",
                            destination = connection.destination ?: "",
                            domain = connection.domain ?: "",
                            protocol = connection.protocol ?: "",
                            rule = connection.rule ?: "",
                            outbound = connection.outbound ?: "",
                            outboundType = connection.outboundType ?: "",
                            chain = chain,
                            processPath = connection.processInfo?.processPath ?: "",
                            userId = connection.processInfo?.userID ?: 0,
                            uplinkTotal = connection.uplinkTotal,
                            downlinkTotal = connection.downlinkTotal,
                        )
                    }
                }
            }
        }
        val client = SfaCommandClient(
            scope,
            listOf(
                SfaCommandClient.ConnectionType.Status,
                SfaCommandClient.ConnectionType.Groups,
                SfaCommandClient.ConnectionType.Log,
                SfaCommandClient.ConnectionType.Connections,
            ),
            handler,
            localOnly = true,
        )
        commandClient = client
        client.connect()
    }

    private fun applySelections() {
        val pending = selectedMap
        if (pending.isEmpty()) {
            return
        }
        scope.launch {
            val client = standaloneClient() ?: return@launch
            for ((group, outbound) in pending) {
                runCatching { client.selectOutbound(group, outbound) }
            }
        }
    }

    private fun standaloneClient(): CommandClient? = runCatching {
        Libbox.newStandaloneCommandClient()
    }.getOrNull()

    private fun pushEvents(events: JsonArray) {
        val sink = eventSink ?: return
        val call = JsonObject()
        call.addProperty("method", "message")
        call.add("arguments", events)
        sink(gson.toJson(call).toByteArray(Charsets.UTF_8))
    }

    private fun logLevelName(level: Int): String = when (level) {
        0 -> "trace"
        1 -> "debug"
        2 -> "info"
        3 -> "warning"
        4, 5, 6 -> "error"
        else -> "info"
    }

    // ---- wire dispatch -------------------------------------------------

    fun invoke(data: String, callback: (ByteArray) -> Unit) {
        scope.launch {
            val response = try {
                val call = JsonParser.parseString(data).asJsonObject
                val id = call.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                val method = call.get("method")?.asString ?: ""
                val arguments = call.get("arguments")
                val result = dispatch(method, arguments)
                val envelope = JsonObject()
                if (id != null) {
                    envelope.addProperty("id", id)
                }
                envelope.add("result", result)
                envelope
            } catch (error: CoreFailure) {
                val envelope = JsonObject()
                runCatching {
                    val call = JsonParser.parseString(data).asJsonObject
                    call.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.let {
                        envelope.addProperty("id", it)
                    }
                }
                val err = JsonObject()
                err.addProperty("code", error.code)
                err.addProperty("message", error.message ?: "")
                envelope.add("error", err)
                envelope
            } catch (error: Exception) {
                val envelope = JsonObject()
                runCatching {
                    val call = JsonParser.parseString(data).asJsonObject
                    call.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.let {
                        envelope.addProperty("id", it)
                    }
                }
                val err = JsonObject()
                err.addProperty("code", "core_error")
                err.addProperty("message", error.message ?: error.toString())
                envelope.add("error", err)
                envelope
            }
            callback(gson.toJson(response).toByteArray(Charsets.UTF_8))
        }
    }

    private class CoreFailure(val code: String, message: String) : Exception(message)

    private fun unsupported(method: String): Nothing =
        throw CoreFailure("unsupported", "Not supported by the sing-box core: $method")

    private fun dispatch(method: String, arguments: JsonElement?): JsonElement? {
        return when (method) {
            "core.init" -> {
                val args = arguments?.asJsonObject
                homeDir = args?.get("home-dir")?.asString
                runBlocking { Application.application.setupLibbox() }
                initialized = true
                null
            }
            "core.initialized" -> JsonPrimitive(initialized)
            "core.gc" -> null
            "core.memory" -> {
                val result = JsonObject()
                result.addProperty("rss", lastStatus?.memory ?: 0)
                result.addProperty("heap-inuse", 0)
                result.addProperty("heap-idle", 0)
                result.addProperty("stack-inuse", 0)
                result.addProperty("runtime-other", 0)
                result
            }
            "core.crash" -> unsupported(method)
            "config.setup" -> setupConfig(arguments)
            "config.update" -> {
                updateParams = arguments?.asJsonObject
                null
            }
            "config.validate" -> validateConfig(arguments)
            "config.decrypt" -> unsupported(method)
            "config.read" -> readConfig(arguments)
            "profile.base" -> profileBase(arguments)
            "profile.outbounds" -> readConfig(arguments)
            "profile.build" -> {
                val base = arguments?.asJsonObject
                val path = base?.get("path")?.asString ?: ""
                JsonPrimitive(File(path).takeIf { it.isFile }?.readText() ?: "")
            }
            "profile.clearEffect" -> null
            "proxy.list" -> proxyList()
            "proxy.select" -> proxySelect(arguments)
            "proxy.definition" -> proxyDefinition(arguments)
            "proxy.testDelay" -> proxyTestDelay(arguments)
            "proxy.validate" -> JsonObject()
            "proxy.convert" -> unsupported(method)
            "shareLink.encode", "shareLink.decode" -> unsupported(method)
            "filter.validate" -> JsonObject()
            "provider.list" -> JsonArray()
            "provider.get" -> null
            "provider.update" -> null
            "provider.sideLoad" -> unsupported(method)
            "ruleSet.dump", "ruleSet.compile" -> unsupported(method)
            "geo.update" -> unsupported(method)
            "traffic.current" -> {
                val result = JsonObject()
                result.addProperty("up", lastStatus?.uplink ?: 0)
                result.addProperty("down", lastStatus?.downlink ?: 0)
                result
            }
            "traffic.total" -> {
                val result = JsonObject()
                result.addProperty("up", lastStatus?.uplinkTotal ?: 0)
                result.addProperty("down", lastStatus?.downlinkTotal ?: 0)
                result
            }
            "traffic.reset" -> null
            "connection.list" -> connectionList()
            "connection.count" -> JsonPrimitive(synchronized(connections) { connections.size })
            "connection.close" -> {
                val id = arguments?.asString
                if (id != null) {
                    standaloneClient()?.let { runCatching { it.closeConnection(id) } }
                }
                null
            }
            "connection.closeAll", "connection.reset" -> {
                standaloneClient()?.let { runCatching { it.closeConnections() } }
                null
            }
            "dns.clearCache" -> null
            "network.changed" -> null
            "log.start" -> {
                logForwarding = true
                null
            }
            "log.stop" -> {
                logForwarding = false
                null
            }
            "listener.start", "listener.stop" -> null
            "route.watch" -> {
                val result = JsonObject()
                result.addProperty("core-epoch", coreEpoch)
                result.addProperty("route-version", routeVersion)
                result
            }
            "route.check" -> {
                val hosts = arguments?.asJsonObject?.get("hosts")?.takeIf { it.isJsonArray }?.asJsonArray
                val result = JsonObject()
                result.addProperty("core-epoch", coreEpoch)
                result.addProperty("route-version", routeVersion)
                val held = JsonArray()
                repeat(hosts?.size() ?: 0) { held.add(false) }
                result.add("held", held)
                result
            }
            "probe.outboundIp" -> unsupported(method)
            "probe.services" -> JsonArray()
            "mitm.certificate", "mitm.generateCertificate", "mitm.validate",
            "mitm.issueCertificate", "mitm.runScript", "mitm.exchanges",
            "mitm.exchange", "mitm.body" -> unsupported(method)
            else -> unsupported(method)
        }
    }

    // ---- config --------------------------------------------------------

    private fun setupConfig(arguments: JsonElement?): JsonElement? {
        val args = arguments?.asJsonObject ?: throw CoreFailure("invalid_argument", "Missing setup params")
        val base = args.get("base")?.takeIf { it.isJsonObject }?.asJsonObject
        val basePath = base?.get("path")?.asString
        val configPath = basePath ?: homeDir?.let { File(it, "config.yaml").path }
            ?: throw CoreFailure("invalid_argument", "No config path")
        val file = File(configPath)
        if (!file.isFile) {
            throw CoreFailure("config_rejected", "Configuration file not found: $configPath")
        }
        val content = file.readText()
        if (content.isBlank()) {
            throw CoreFailure("config_rejected", "Configuration is empty")
        }
        val selected = args.get("selected-map")?.takeIf { it.isJsonObject }?.asJsonObject
        if (selected != null) {
            selectedMap = selected.entrySet().associate { it.key to it.value.asString }
        }
        val options = args.get("options")?.takeIf { it.isJsonObject }?.asJsonObject
        options?.get("test-url")?.asString?.let { testUrl = it }

        val merged = mergeSettings(content)
        checkConfigOrThrow(merged)

        rawConfig = content
        val target = currentConfigFile() ?: throw CoreFailure("core_error", "Home dir is not set")
        target.parentFile?.mkdirs()
        target.writeText(merged)
        ProfileManager.currentPath = target.absolutePath
        ProfileManager.currentName = profileName
        Settings.selectedProfile = 1L
        routeVersion++
        if (serviceConnected) {
            onConfigChangedWhileRunning?.invoke()
        }
        return null
    }

    private fun checkConfigOrThrow(content: String) {
        try {
            Libbox.checkConfig(content)
        } catch (error: Exception) {
            throw CoreFailure("config_rejected", error.message ?: "Invalid configuration")
        }
    }

    private fun mergeSettings(content: String): String {
        val root = try {
            JsonParser.parseString(content).asJsonObject
        } catch (error: Exception) {
            throw CoreFailure("config_rejected", "Configuration is not valid JSON: ${error.message}")
        }
        // Log level from the app settings, when the profile does not set one.
        val logLevel = updateParams?.get("log-level")?.takeIf { it.isJsonPrimitive }?.asString
        if (logLevel != null) {
            val log = root.getAsJsonObject("log") ?: JsonObject().also { root.add("log", it) }
            if (!log.has("level")) {
                log.addProperty("level", logLevel)
            }
            if (!log.has("timestamp")) {
                log.addProperty("timestamp", true)
            }
        }
        val vpn = vpnOptions
        val inbounds = root.getAsJsonArray("inbounds") ?: JsonArray().also { root.add("inbounds", it) }
        val hasTun = inbounds.any { it.isJsonObject && it.asJsonObject.get("type")?.asString == "tun" }
        if (vpn != null && vpn.enable && !hasTun) {
            val tun = JsonObject()
            tun.addProperty("type", "tun")
            tun.addProperty("tag", "tun-in")
            val addresses = JsonArray()
            addresses.add("172.19.0.1/30")
            if (vpn.ipv6) {
                addresses.add("fdfe:dcba:9876::1/126")
            }
            tun.add("address", addresses)
            tun.addProperty("mtu", if (vpn.mtu > 0) vpn.mtu else 9000)
            tun.addProperty("auto_route", true)
            tun.addProperty("strict_route", true)
            tun.addProperty("stack", mapStack(vpn.stack))
            if (vpn.routeAddress.isNotEmpty()) {
                val routes = JsonArray()
                vpn.routeAddress.forEach { routes.add(it) }
                tun.add("route_address", routes)
            }
            inbounds.add(tun)
            val route = root.getAsJsonObject("route") ?: JsonObject().also { root.add("route", it) }
            if (!route.has("auto_detect_interface")) {
                route.addProperty("auto_detect_interface", true)
            }
            if (vpn.dnsHijacking) {
                val rules = route.getAsJsonArray("rules") ?: JsonArray().also { route.add("rules", it) }
                val hasHijack = rules.any {
                    it.isJsonObject && it.asJsonObject.get("action")?.asString == "hijack-dns"
                }
                if (!hasHijack) {
                    val hijack = JsonObject()
                    hijack.addProperty("protocol", "dns")
                    hijack.addProperty("action", "hijack-dns")
                    val newRules = JsonArray()
                    newRules.add(hijack)
                    rules.forEach { newRules.add(it) }
                    route.add("rules", newRules)
                }
            }
        }
        return gson.toJson(root)
    }

    private fun mapStack(stack: String): String = when (stack.lowercase()) {
        "gvisor" -> "gvisor"
        "system" -> "system"
        else -> "mixed"
    }

    private fun validateConfig(arguments: JsonElement?): JsonElement? {
        val path = arguments?.asString ?: return JsonPrimitive("Missing config path")
        val file = File(path)
        if (!file.isFile) {
            return JsonPrimitive("Configuration file not found")
        }
        val content = file.readText()
        try {
            JsonParser.parseString(content)
        } catch (error: Exception) {
            return JsonPrimitive("Configuration is not valid sing-box JSON: ${error.message}")
        }
        return try {
            Libbox.checkConfig(content)
            null
        } catch (error: Exception) {
            JsonPrimitive(error.message ?: "Invalid configuration")
        }
    }

    // ---- profile data for the UI ---------------------------------------

    private fun parseConfigFile(path: String): JsonObject? {
        val file = File(path)
        if (!file.isFile) {
            return null
        }
        return runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrNull()
    }

    private fun synthesizedConfig(root: JsonObject): JsonObject {
        val result = JsonObject()
        val proxies = JsonArray()
        val groups = JsonArray()
        val outbounds = root.getAsJsonArray("outbounds") ?: JsonArray()
        for (element in outbounds) {
            if (!element.isJsonObject) {
                continue
            }
            val outbound = element.asJsonObject
            val tag = outbound.get("tag")?.asString ?: continue
            val type = outbound.get("type")?.asString ?: continue
            if (type == "selector" || type == "urltest") {
                val group = JsonObject()
                group.addProperty("name", tag)
                group.addProperty("type", if (type == "selector") "Selector" else "URLTest")
                val members = JsonArray()
                outbound.getAsJsonArray("outbounds")?.forEach { members.add(it) }
                group.add("proxies", members)
                groups.add(group)
            } else if (type != "dns") {
                val proxy = JsonObject()
                proxy.addProperty("name", tag)
                proxy.addProperty("type", type)
                outbound.get("server")?.let { proxy.add("server", it) }
                outbound.get("server_port")?.let { proxy.add("port", it) }
                proxies.add(proxy)
            }
        }
        result.add("proxies", proxies)
        result.add("proxy-groups", groups)
        result.add("rules", JsonArray())
        result.add("rule", JsonArray())
        return result
    }

    private fun readConfig(arguments: JsonElement?): JsonElement? {
        val path = arguments?.asString ?: return JsonObject()
        val root = parseConfigFile(path) ?: return JsonObject()
        return synthesizedConfig(root)
    }

    private fun profileBase(arguments: JsonElement?): JsonElement? {
        val path = arguments?.asString ?: return null
        val file = File(path)
        if (!file.isFile) {
            return null
        }
        val content = file.readText()
        val root = runCatching { JsonParser.parseString(content).asJsonObject }.getOrNull()
            ?: return null
        val synthesized = synthesizedConfig(root)
        val result = JsonObject()
        result.add("config", synthesized)
        result.addProperty("digest", md5(content))
        result.addProperty(
            "proxies",
            synthesized.getAsJsonArray("proxies")?.size() ?: 0,
        )
        result.addProperty(
            "proxy-groups",
            synthesized.getAsJsonArray("proxy-groups")?.size() ?: 0,
        )
        result.addProperty("rules", 0)
        result.add("match-rules", JsonArray())
        return result
    }

    private fun md5(text: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ---- proxies ---------------------------------------------------------

    private fun groupTypeName(libboxType: String): String? = when (libboxType.lowercase()) {
        "selector" -> "Selector"
        "urltest" -> "URLTest"
        else -> null
    }

    private fun proxyList(): JsonElement {
        val result = JsonObject()
        val proxies = JsonObject()
        val all = JsonArray()
        val groups = lastGroups
        if (groups.isNotEmpty()) {
            for (group in groups) {
                val typeName = groupTypeName(group.type ?: "") ?: continue
                val entry = JsonObject()
                entry.addProperty("name", group.tag)
                entry.addProperty("type", typeName)
                entry.addProperty("now", group.selected ?: "")
                val members = JsonArray()
                val items = group.items
                while (items.hasNext()) {
                    val item = items.next()
                    members.add(item.tag)
                    val leaf = JsonObject()
                    leaf.addProperty("name", item.tag)
                    leaf.addProperty("type", item.type ?: "")
                    proxies.add(item.tag, leaf)
                }
                entry.add("all", members)
                proxies.add(group.tag, entry)
                all.add(group.tag)
            }
        } else {
            // Not running: derive the group list from the stored config so the
            // proxies page still reflects the selected profile.
            val root = rawConfig?.let {
                runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull()
            }
            if (root != null) {
                val outbounds = root.getAsJsonArray("outbounds") ?: JsonArray()
                val byTag = mutableMapOf<String, JsonObject>()
                for (element in outbounds) {
                    if (element.isJsonObject) {
                        val outbound = element.asJsonObject
                        val tag = outbound.get("tag")?.asString
                        if (tag != null) {
                            byTag[tag] = outbound
                        }
                    }
                }
                for ((tag, outbound) in byTag) {
                    val type = outbound.get("type")?.asString ?: continue
                    val typeName = groupTypeName(type) ?: continue
                    val entry = JsonObject()
                    entry.addProperty("name", tag)
                    entry.addProperty("type", typeName)
                    val members = JsonArray()
                    outbound.getAsJsonArray("outbounds")?.forEach { member ->
                        val memberTag = member.asString
                        members.add(memberTag)
                        val leaf = JsonObject()
                        leaf.addProperty("name", memberTag)
                        leaf.addProperty("type", byTag[memberTag]?.get("type")?.asString ?: "")
                        proxies.add(memberTag, leaf)
                    }
                    entry.add("all", members)
                    entry.addProperty(
                        "now",
                        selectedMap[tag]
                            ?: outbound.get("default")?.asString
                            ?: members.firstOrNull()?.asString
                            ?: "",
                    )
                    proxies.add(tag, entry)
                    all.add(tag)
                }
            }
        }
        result.add("proxies", proxies)
        result.add("all", all)
        return result
    }

    private fun proxySelect(arguments: JsonElement?): JsonElement {
        val args = arguments?.asJsonObject ?: throw CoreFailure("invalid_argument", "Missing select params")
        val group = args.get("group-name")?.asString ?: ""
        val proxy = args.get("proxy-name")?.asString ?: ""
        val result = JsonObject()
        val client = standaloneClient()
        if (client == null) {
            result.addProperty("changed", false)
            result.addProperty("message", "Core is not running")
            return result
        }
        return try {
            client.selectOutbound(group, proxy)
            selectedMap = selectedMap + (group to proxy)
            routeVersion++
            result.addProperty("changed", true)
            result.addProperty("message", "")
            result
        } catch (error: Exception) {
            result.addProperty("changed", false)
            result.addProperty("message", error.message ?: "")
            result
        }
    }

    private fun proxyDefinition(arguments: JsonElement?): JsonElement? {
        val name = arguments?.asString ?: return null
        val root = rawConfig?.let {
            runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull()
        } ?: return null
        val outbounds = root.getAsJsonArray("outbounds") ?: return null
        for (element in outbounds) {
            if (element.isJsonObject && element.asJsonObject.get("tag")?.asString == name) {
                return element
            }
        }
        return null
    }

    private fun proxyTestDelay(arguments: JsonElement?): JsonElement {
        val args = arguments?.asJsonObject ?: throw CoreFailure("invalid_argument", "Missing test params")
        val proxyName = args.get("proxy-name")?.asString ?: ""
        val url = args.get("test-url")?.asString ?: testUrl
        val timeout = args.get("timeout")?.asInt ?: 5000
        val result = JsonObject()
        result.addProperty("name", proxyName)
        result.addProperty("url", url)
        val client = standaloneClient()
        if (client == null || !serviceConnected) {
            return result
        }
        val group = lastGroups.firstOrNull { group ->
            if (group.tag == proxyName) {
                true
            } else {
                val items = group.items
                var found = false
                while (items.hasNext()) {
                    if (items.next().tag == proxyName) {
                        found = true
                        break
                    }
                }
                found
            }
        } ?: return result
        val before = groupItemDelay(group.tag, proxyName)
        runCatching { client.urlTest(group.tag) }
        val deadline = System.currentTimeMillis() + timeout.coerceAtMost(15000)
        var delay: Int? = null
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(300)
            val current = groupItemDelay(group.tag, proxyName)
            if (current != null && current != before) {
                delay = current
                break
            }
            if (current != null && proxyName == group.tag) {
                delay = current
                break
            }
        }
        if (delay == null) {
            delay = groupItemDelay(group.tag, proxyName)
        }
        if (delay != null && delay > 0) {
            result.addProperty("value", delay)
        }
        return result
    }

    private fun groupItemDelay(groupTag: String, itemTag: String): Int? {
        val group = lastGroups.firstOrNull { it.tag == groupTag } ?: return null
        val items = group.items
        while (items.hasNext()) {
            val item = items.next()
            if (item.tag == itemTag) {
                return item.urlTestDelay
            }
        }
        return null
    }

    // ---- connections -----------------------------------------------------

    private fun connectionList(): JsonElement {
        val result = JsonObject()
        val list = JsonArray()
        val snapshot = synchronized(connections) {
            connections.values.sortedByDescending { it.createdAt }
        }
        for (connection in snapshot) {
            val item = JsonObject()
            item.addProperty("id", connection.id)
            item.addProperty("upload", connection.uplinkTotal)
            item.addProperty("download", connection.downlinkTotal)
            item.addProperty(
                "start",
                Instant.ofEpochMilli(connection.createdAt).toString(),
            )
            val metadata = JsonObject()
            metadata.addProperty("uid", connection.userId)
            metadata.addProperty("network", connection.network)
            val (sourceIp, sourcePort) = splitHostPort(connection.source)
            val (destinationIp, destinationPort) = splitHostPort(connection.destination)
            metadata.addProperty("sourceIP", sourceIp)
            metadata.addProperty("sourcePort", sourcePort)
            metadata.addProperty("destinationIP", destinationIp)
            metadata.addProperty("destinationPort", destinationPort)
            metadata.addProperty("host", connection.domain)
            metadata.addProperty("process", connection.processPath.substringAfterLast('/'))
            metadata.addProperty("processPath", connection.processPath)
            metadata.addProperty("remoteDestination", connection.outbound)
            item.add("metadata", metadata)
            val chains = JsonArray()
            connection.chain.forEach { chains.add(it) }
            if (chains.size() == 0 && connection.outbound.isNotEmpty()) {
                chains.add(connection.outbound)
            }
            item.add("chains", chains)
            item.addProperty("rule", connection.rule)
            item.addProperty("rulePayload", "")
            list.add(item)
        }
        result.add("connections", list)
        return result
    }

    private fun splitHostPort(value: String): Pair<String, String> {
        val index = value.lastIndexOf(':')
        return if (index > 0) {
            value.substring(0, index).removeSurrounding("[", "]") to value.substring(index + 1)
        } else {
            value to ""
        }
    }
}
