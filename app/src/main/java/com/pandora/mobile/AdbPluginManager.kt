package com.pandora.mobile

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.pandora.mobile.linux.RootfsInstaller
import java.io.Closeable
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AdbPluginStage {
    DISABLED,
    READY,
    PAIRED,
    PREPARING,
    WAITING_FOR_PAIRING,
    WAITING_FOR_CODE,
    PAIRING,
    CONNECTING,
    CONNECTED,
    ERROR,
}

data class AdbPluginState(
    val enabled: Boolean = false,
    val stage: AdbPluginStage = AdbPluginStage.DISABLED,
    val detail: String? = null,
    val pairingEndpoint: String? = null,
    val controlActive: Boolean = false,
)

/** Owns the on-device ADB pairing lifecycle and the localhost control-session signal. */
class AdbPluginManager(private val context: Context) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installer = RootfsInstaller(context)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val _state = MutableStateFlow(
        if (AppSettings.adbPluginEnabled(context)) {
            AdbPluginState(enabled = true, stage = AdbPluginStage.READY)
        } else {
            AdbPluginState()
        },
    )
    val state: StateFlow<AdbPluginState> = _state.asStateFlow()

    private var discovery: NsdManager.DiscoveryListener? = null
    private var stateJob: Job? = null
    private var connectionRequestedForControl = false
    private var bridge: AdbControlBridge? = null
    private var adbServerProcess: Process? = null
    private val adbIdentityDirectory = File(context.filesDir, "adb-identity")
    private val legacyAdbIdentityDirectory = File(installer.workspace, ".android")
    private val bridgeTokenFile = File(installer.workspace, ".pandora/bridge-token")
    private val controlSerialFile = File(installer.workspace, ".pandora/adb-control-serial")
    private val controlOverlay = SystemAdbControlOverlay(context, ::stopControl)
    private val agentNotifications = AgentNotificationController(context)
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        ensureBridgeToken()
        migrateLegacyAdbIdentity()
        bridge = AdbControlBridge(this).also(AdbControlBridge::start)
        if (_state.value.enabled && hasPairingKey()) {
            _state.value = _state.value.copy(stage = AdbPluginStage.PAIRED, detail = "Phone pairing is ready.")
        }
    }

    fun setEnabled(enabled: Boolean) {
        AppSettings.setAdbPluginEnabled(context, enabled)
        if (!enabled) {
            stopDiscovery()
            connectionRequestedForControl = false
            stateJob?.cancel()
            controlOverlay.hide()
            _state.value = AdbPluginState()
            context.stopService(Intent(context, AdbPairingService::class.java))
            scope.launch {
                deactivateAdbTransport()
            }
            return
        }
        _state.value = AdbPluginState(enabled = true, stage = AdbPluginStage.READY)
        if (!started.get()) start()
        migrateLegacyAdbIdentity()
        if (hasPairingKey()) {
            _state.value = _state.value.copy(stage = AdbPluginStage.PAIRED, detail = "Phone pairing is ready.")
        }
    }

    fun beginPairing() {
        if (!_state.value.enabled) setEnabled(true)
        stateJob?.cancel()
        _state.value = _state.value.copy(
            stage = AdbPluginStage.PREPARING,
            detail = "Preparing Android Debug Bridge…",
            pairingEndpoint = null,
        )
        ContextCompat.startForegroundService(context, Intent(context, AdbPairingService::class.java))
        stateJob = scope.launch {
            val prepared = prepareAdb()
            if (!prepared) return@launch
            if (!ensureAdbIdentity()) return@launch
            _state.value = _state.value.copy(
                stage = AdbPluginStage.WAITING_FOR_PAIRING,
                detail = "In Wireless debugging, tap Pair device with pairing code.",
            )
            discoverOnce(PAIRING_SERVICE) { endpoint ->
                _state.value = _state.value.copy(
                    stage = AdbPluginStage.WAITING_FOR_CODE,
                    detail = "Enter the six-digit code from Android in the Pandora notification.",
                    pairingEndpoint = endpoint.address,
                )
                AdbPairingService.refresh(context)
            }
        }
    }

    fun submitPairingCode(code: String) {
        val normalized = code.filter(Char::isDigit)
        val endpoint = _state.value.pairingEndpoint
        if (normalized.length != 6 || endpoint == null) {
            fail("Enter the six-digit pairing code shown by Android.")
            return
        }
        _state.value = _state.value.copy(stage = AdbPluginStage.PAIRING, detail = "Pairing with this phone…")
        AdbPairingService.refresh(context)
        stateJob?.cancel()
        stateJob = scope.launch {
            // Passing the code explicitly avoids adb's interactive terminal reader,
            // which can wait forever when hosted by Android's ProcessBuilder pipes.
            val result = runAdb(listOf("pair", endpoint, normalized))
            if (result.exitCode != 0 || !result.output.contains("Successfully paired", ignoreCase = true)) {
                fail(result.output.ifBlank { "Android rejected the pairing code." })
                AdbPairingService.refresh(context)
                return@launch
            }
            _state.value = _state.value.copy(
                stage = AdbPluginStage.CONNECTING,
                detail = "Pairing complete. Connecting…",
                pairingEndpoint = null,
            )
            AdbPairingService.refresh(context)
            connectDiscoveredDevice()
        }
    }

    fun startControl() {
        if (!_state.value.enabled) return
        if (!Settings.canDrawOverlays(context)) {
            fail("Allow Pandora to display the control frame over other apps before starting phone control.")
            return
        }
        if (_state.value.controlActive) return
        connectionRequestedForControl = true
        controlOverlay.show()
        _state.value = _state.value.copy(
            stage = AdbPluginStage.CONNECTING,
            detail = "Connecting to this phone…",
            controlActive = false,
        )
        stateJob?.cancel()
        stateJob = scope.launch {
            if (!prepareAdb() || !ensureAdbIdentity()) return@launch
            val connected = connectedSerials().firstOrNull()
            if (connected != null) {
                if (installControlSkill()) activateControl(connected)
            } else {
                connectDiscoveredDevice()
            }
        }
    }

    fun stopControl() {
        connectionRequestedForControl = false
        controlOverlay.hide()
        controlSerialFile.delete()
        val wasEnabled = _state.value.enabled
        _state.value = _state.value.copy(controlActive = false, detail = "Control stopped.")
        if (wasEnabled) {
            scope.launch {
                deactivateAdbTransport()
                if (_state.value.enabled) {
                    _state.value = _state.value.copy(stage = AdbPluginStage.PAIRED, detail = "Control stopped.")
                }
            }
        }
    }

    internal fun sendAgentNotification(title: String, message: String): AgentNotificationResult =
        agentNotifications.send(title, message)

    fun retry() {
        if (!_state.value.enabled) return
        if (!hasPairingKey()) {
            beginPairing()
            return
        }
        stateJob?.cancel()
        _state.value = _state.value.copy(stage = AdbPluginStage.CONNECTING, detail = "Checking phone pairing…")
        stateJob = scope.launch {
            if (!prepareAdb() || !ensureAdbIdentity()) return@launch
            connectDiscoveredDevice()
        }
    }

    private suspend fun prepareAdb(): Boolean = runCatching {
        installer.installIfNeeded { status ->
            _state.value = _state.value.copy(stage = AdbPluginStage.PREPARING, detail = status)
            AdbPairingService.refresh(context)
        }
        check(java.io.File(installer.rootfs, "usr/bin/adb").isFile) {
            "ADB support could not be installed. Check the network and try again."
        }
        migrateLegacyAdbIdentity()
        true
    }.getOrElse {
        fail(it.message ?: "Could not prepare ADB support.")
        false
    }

    /** Gives Android's paired-device list a stable, human-readable Pandora label. */
    private suspend fun ensureAdbIdentity(): Boolean = runCatching {
        val key = File(adbIdentityDirectory, "adbkey")
        val publicKey = File(adbIdentityDirectory, "adbkey.pub")
        if (!key.isFile || !publicKey.isFile) {
            key.parentFile?.mkdirs()
            val result = runAdb(listOf("keygen", "/root/.android/adbkey"))
            check(result.exitCode == 0 && key.isFile && publicKey.isFile) {
                result.output.ifBlank { "Could not create Pandora's ADB identity." }
            }
        }
        val encodedKey = publicKey.readText().trim().substringBefore(' ')
        check(encodedKey.isNotBlank()) { "Pandora's ADB identity is invalid." }
        publicKey.writeText("$encodedKey $ADB_IDENTITY\n")
        true
    }.getOrElse {
        fail(it.message ?: "Could not prepare Pandora's ADB identity.")
        AdbPairingService.refresh(context)
        false
    }

    private fun hasPairingKey(): Boolean = File(adbIdentityDirectory, "adbkey").isFile

    private suspend fun connectedSerials(): List<String> {
        val result = runAdb(listOf("devices"))
        if (result.exitCode != 0) return emptyList()
        return result.output.lineSequence()
            .drop(1)
            .map(String::trim)
            .filter { it.endsWith("\tdevice") }
            .map { it.substringBefore('\t') }
            .toList()
    }

    private fun connectDiscoveredDevice() {
        discoverOnce(CONNECT_SERVICE) { endpoint ->
            scope.launch {
                val result = runAdb(listOf("connect", endpoint.address))
                // Wireless debugging rotates its connection port. NSD can briefly
                // resolve the retired service even while the existing ADB transport
                // is healthy, so prefer the actual device list over that stale error.
                val connected =
                    (result.exitCode == 0 && result.output.contains("connected", ignoreCase = true)) ||
                        connectedSerials().isNotEmpty()
                if (connected) {
                    val sourceSerial = connectedSerials().firstOrNull { it == endpoint.address }
                        ?: connectedSerials().firstOrNull()
                        ?: return@launch fail("Phone paired, but Pandora could not identify the ADB connection.")
                    if (!installControlSkill()) {
                        AdbPairingService.refresh(context)
                        return@launch
                    }
                    val activate = connectionRequestedForControl
                    connectionRequestedForControl = false
                    if (activate) {
                        activateControl(sourceSerial)
                    } else {
                        deactivateAdbTransport()
                        _state.value = _state.value.copy(
                            stage = AdbPluginStage.PAIRED,
                            detail = "Phone pairing is ready.",
                            controlActive = false,
                        )
                    }
                    AdbPairingService.complete(context)
                } else {
                    fail(result.output.ifBlank { "Paired, but could not connect to Android debugging." })
                    AdbPairingService.refresh(context)
                }
            }
        }
    }

    private fun installControlSkill(): Boolean = runCatching {
        installer.installAdbControlSkill()
        true
    }.getOrElse {
        fail("Phone connected, but Pandora could not install its Android control skill.")
        Log.w(TAG, "Could not install Android control skill", it)
        false
    }

    private fun discoverOnce(serviceType: String, onResolved: (AdbEndpoint) -> Unit) {
        stopDiscovery()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopDiscovery()
                fail("Android could not start wireless-debugging discovery ($errorCode).")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.startsWith(serviceType.removeSuffix("."))) return
                @Suppress("DEPRECATION")
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val port = serviceInfo.port
                        @Suppress("DEPRECATION")
                        val host = serviceInfo.host?.hostAddress
                        if (port <= 0 || host.isNullOrBlank()) return
                        stopDiscovery()
                        onResolved(AdbEndpoint(host, port))
                    }
                })
            }
        }
        discovery = listener
        runCatching { nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { fail(it.message ?: "Could not discover Android wireless debugging.") }
    }

    @Synchronized
    private fun stopDiscovery() {
        val listener = discovery ?: return
        discovery = null
        runCatching { nsd.stopServiceDiscovery(listener) }
    }

    private fun fail(message: String) {
        controlOverlay.hide()
        controlSerialFile.delete()
        connectionRequestedForControl = false
        _state.value = _state.value.copy(stage = AdbPluginStage.ERROR, detail = message, controlActive = false)
        scope.launch { deactivateAdbTransport() }
    }

    private suspend fun runAdb(arguments: List<String>, stdin: String? = null): CommandResult {
        if (arguments.firstOrNull() != "keygen" && !ensureAdbServer()) {
            return CommandResult(-1, "Pandora could not start its ADB service.")
        }
        adbIdentityDirectory.mkdirs()
        val command = installer.containerCommandWithBindings(
            listOf(adbIdentityDirectory to "/root/.android"),
            "/usr/bin/adb",
            *arguments.toTypedArray(),
        )
        return runCatching {
            val process = installer.startContainerProcess(command, mergeError = true)
            if (stdin != null) {
                process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(stdin) }
            } else {
                process.outputStream.close()
            }
            if (!process.waitFor(ADB_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                installer.terminateProcessTree(process)
                return@runCatching CommandResult(-1, "ADB command timed out.")
            }
            val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trim()
            CommandResult(process.exitValue(), output)
        }.getOrElse { CommandResult(-1, it.message ?: "ADB command failed.") }
    }

    /**
     * ADB normally forks its server on first use. Under PRoot that daemon remains a
     * supervised descendant, so the short-lived client appears to hang. Running the
     * server explicitly gives PRoot one intentional long-lived process and lets every
     * command client exit normally.
     */
    @Synchronized
    private fun ensureAdbServer(): Boolean {
        if (adbServerReachable()) return true
        adbServerProcess?.takeIf(Process::isAlive)?.let(installer::terminateProcessTree)
        adbIdentityDirectory.mkdirs()
        val command = installer.containerCommandWithBindings(
            listOf(adbIdentityDirectory to "/root/.android"),
            "/usr/bin/adb",
            "server",
            "nodaemon",
        )
        val process = runCatching { installer.startContainerProcess(command, mergeError = true) }
            .getOrElse {
                Log.w(TAG, "Could not launch ADB server", it)
                return false
            }
        adbServerProcess = process
        Thread({
            runCatching {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { Log.d(TAG, "adb server: $it") }
                }
            }
        }, "pandora-adb-server-log").apply { isDaemon = true; start() }

        repeat(ADB_SERVER_START_POLLS) {
            if (adbServerReachable()) return true
            if (!process.isAlive) return false
            Thread.sleep(ADB_SERVER_START_POLL_MS)
        }
        return false
    }

    private fun adbServerReachable(): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), ADB_SERVER_PORT), ADB_SERVER_CONNECT_TIMEOUT_MS)
        }
        true
    }.getOrDefault(false)

    @Synchronized
    private fun stopAdbServer() {
        adbServerProcess?.let(installer::terminateProcessTree)
        adbServerProcess = null
    }

    override fun close() {
        stopDiscovery()
        stateJob?.cancel()
        bridge?.close()
        bridge = null
        controlOverlay.close()
        controlSerialFile.delete()
        stopAdbServer()
        scope.coroutineContext[Job]?.cancel()
    }

    private data class CommandResult(val exitCode: Int, val output: String)

    internal fun bridgeToken(): String = ensureBridgeToken()

    private fun activateControl(serial: String) {
        controlSerialFile.parentFile?.mkdirs()
        controlSerialFile.writeText(serial + "\n")
        _state.value = _state.value.copy(
            stage = AdbPluginStage.CONNECTED,
            detail = "ADB phone control is active.",
            controlActive = true,
        )
    }

    private suspend fun deactivateAdbTransport() {
        runCatching { runAdb(listOf("disconnect")) }
        stopAdbServer()
    }

    @Synchronized
    private fun migrateLegacyAdbIdentity() {
        if (!legacyAdbIdentityDirectory.isDirectory) return
        adbIdentityDirectory.mkdirs()
        listOf("adbkey", "adbkey.pub").forEach { name ->
            val legacy = File(legacyAdbIdentityDirectory, name)
            val target = File(adbIdentityDirectory, name)
            if (legacy.isFile && !target.isFile) legacy.copyTo(target)
            if (target.isFile) legacy.delete()
        }
        legacyAdbIdentityDirectory.delete()
    }

    @Synchronized
    private fun ensureBridgeToken(): String {
        bridgeTokenFile.takeIf(File::isFile)?.readText()?.trim()?.takeIf { it.length >= 32 }?.let { return it }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val token = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        bridgeTokenFile.parentFile?.mkdirs()
        bridgeTokenFile.writeText(token + "\n")
        return token
    }

    private data class AdbEndpoint(val host: String, val port: Int) {
        val address: String = if (':' in host) "[$host]:$port" else "$host:$port"
    }

    private companion object {
        const val TAG = "AdbPluginManager"
        const val PAIRING_SERVICE = "_adb-tls-pairing._tcp."
        const val CONNECT_SERVICE = "_adb-tls-connect._tcp."
        const val ADB_IDENTITY = "Pandora@device"
        const val ADB_COMMAND_TIMEOUT_SECONDS = 20L
        const val ADB_SERVER_PORT = 5037
        const val ADB_SERVER_CONNECT_TIMEOUT_MS = 100
        const val ADB_SERVER_START_POLL_MS = 50L
        const val ADB_SERVER_START_POLLS = 100
    }
}

/** Tiny loopback-only bridge used by the Pandora agent to bracket active control sessions. */
private class AdbControlBridge(private val manager: AdbPluginManager) : Closeable {
    private val running = AtomicBoolean(false)
    private var socket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({
            runCatching {
                ServerSocket(BRIDGE_PORT, 4, InetAddress.getByName("127.0.0.1")).also { server ->
                    socket = server
                    while (running.get()) runCatching { handle(server.accept()) }
                }
            }.onFailure { if (running.get()) Log.w(TAG, "Control bridge stopped", it) }
        }, "pandora-adb-bridge").apply { isDaemon = true; start() }
    }

    private fun handle(client: Socket) = client.use { socket ->
        socket.soTimeout = 2_000
        val request = readRequest(socket) ?: return@use writeResponse(
            socket,
            BridgeResponse("400 Bad Request", "{\"ok\":false,\"error\":\"bad_request\"}"),
        )
        val providedToken = request.headers[BRIDGE_TOKEN_HEADER]
        if (!secureEquals(manager.bridgeToken(), providedToken)) {
            return@use writeResponse(
                socket,
                BridgeResponse("403 Forbidden", "{\"ok\":false,\"error\":\"unauthorized\"}"),
            )
        }
        val response = when (request.method to request.path) {
            "POST" to "/v1/control/start" -> {
                manager.startControl()
                BridgeResponse("200 OK", "{\"ok\":true,\"state\":\"starting\"}")
            }
            "POST" to "/v1/control/stop" -> {
                manager.stopControl()
                BridgeResponse("200 OK", "{\"ok\":true,\"state\":\"stopped\"}")
            }
            "GET" to "/v1/status" -> {
                val state = manager.state.value
                BridgeResponse(
                    "200 OK",
                    "{\"enabled\":${state.enabled},\"connected\":${state.stage == AdbPluginStage.CONNECTED},\"active\":${state.controlActive}}",
                )
            }
            "POST" to "/v1/notify" -> {
                val result = manager.sendAgentNotification(
                    title = request.parameters["title"].orEmpty(),
                    message = request.parameters["message"].orEmpty(),
                )
                if (result.sent) {
                    val state = if (result.suppressed) "suppressed" else "sent"
                    BridgeResponse("200 OK", "{\"ok\":true,\"state\":\"$state\"}")
                } else {
                    val status = if (result.error == "message_required") "400 Bad Request" else "403 Forbidden"
                    BridgeResponse(status, "{\"ok\":false,\"error\":\"${result.error}\"}")
                }
            }
            else -> BridgeResponse("404 Not Found", "{\"ok\":false,\"error\":\"not_found\"}")
        }
        writeResponse(socket, response)
    }

    private fun readRequest(socket: Socket): BridgeRequest? {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val firstLine = reader.readLine()?.split(' ') ?: return null
        if (firstLine.size < 2) return null
        val method = firstLine[0].uppercase()
        val target = firstLine[1]
        var contentLength = 0
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: return null
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: return null
            }
        }
        if (contentLength !in 0..MAX_REQUEST_BODY_LENGTH) return null
        val body = CharArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val count = reader.read(body, read, contentLength - read)
            if (count < 0) return null
            read += count
        }
        val parameters = parseForm(target.substringAfter('?', "")) + parseForm(String(body))
        return BridgeRequest(method, target.substringBefore('?'), parameters, headers)
    }

    private fun parseForm(encoded: String): Map<String, String> = encoded
        .split('&')
        .filter(String::isNotBlank)
        .associate { field ->
            URLDecoder.decode(field.substringBefore('='), StandardCharsets.UTF_8.name()) to
                URLDecoder.decode(field.substringAfter('=', ""), StandardCharsets.UTF_8.name())
        }

    private fun writeResponse(socket: Socket, response: BridgeResponse) {
        val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
        socket.getOutputStream().bufferedWriter(StandardCharsets.US_ASCII).use { writer ->
            writer.write("HTTP/1.1 ${response.status}\r\n")
            writer.write("Content-Type: application/json\r\n")
            writer.write("Content-Length: ${bytes.size}\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.write(response.body)
        }
    }

    override fun close() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
    }

    private companion object {
        const val TAG = "AdbControlBridge"
        const val BRIDGE_PORT = 8765
        const val MAX_REQUEST_BODY_LENGTH = 8_192
        const val BRIDGE_TOKEN_HEADER = "x-pandora-token"
    }

    private data class BridgeRequest(
        val method: String,
        val path: String,
        val parameters: Map<String, String>,
        val headers: Map<String, String>,
    )

    private data class BridgeResponse(val status: String, val body: String)
}

internal fun secureEquals(expected: String, actual: String?): Boolean {
    if (actual == null) return false
    return MessageDigest.isEqual(
        expected.toByteArray(StandardCharsets.UTF_8),
        actual.toByteArray(StandardCharsets.UTF_8),
    )
}
