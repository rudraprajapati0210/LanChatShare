@file:Suppress("MissingPermission")
package dev.lanchatshare.connectivity

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import dev.lanchatshare.data.Profile
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

private val service = ParcelUuid(UUID.fromString("9d227b24-2b7f-47f6-b325-f45bb134a141"))
data class Candidate(val address: String, val name: String, val suffix: String, val port: Int, val seen: Long)
data class PairPrompt(val name: String, val suffix: String, val code: String, val changed: Boolean)
data class NearbyState(
    val status: String = "Starting Bluetooth…", val devices: Map<String, Candidate> = emptyMap(),
    val prompt: PairPrompt? = null, val busy: Boolean = false, val failed: Boolean = false
)

internal fun beacon(profile: Profile, port: Int): ByteArray {
    val suffix = profile.id.takeLast(4).toInt(16)
    val name = StringBuilder()
    for (point in profile.name.codePoints().toArray()) {
        val next = String(Character.toChars(point))
        if ((name.toString() + next).encodeToByteArray().size > 9) break
        name.append(next)
    }
    return byteArrayOf(1, port.toByte(), (suffix ushr 8).toByte(), suffix.toByte()) + name.toString().encodeToByteArray()
}

@SuppressLint("MissingPermission")
class NearbyRadio(private val context: Context, private val profile: Profile, private val trust: TrustStore) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val mutable = MutableStateFlow(NearbyState())
    val state = mutable.asStateFlow()
    private val guard = Any()
    private var closed = false
    private var listener: BluetoothServerSocket? = null
    private var active: BluetoothSocket? = null
    private var decision: CompletableDeferred<Boolean>? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanFailed(errorCode: Int) = fail("Bluetooth scan stopped ($errorCode). Try again.")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val bytes = result.scanRecord?.getServiceData(service) ?: return
            if (bytes.size !in 4..13 || bytes[0].toInt() != 1) return
            val port = bytes[1].toInt() and 255
            if (port !in 128..255) return
            val suffix = "%02X%02X".format(bytes[2], bytes[3])
            val address = try { result.device.address } catch (_: SecurityException) {
                fail("Bluetooth permission was removed. Allow discovery to continue.")
                return
            }
            val candidate = Candidate(address, bytes.copyOfRange(4, bytes.size).decodeToString(),
                suffix, port, SystemClock.elapsedRealtime())
            mutable.update { it.copy(devices = it.devices + (candidate.address to candidate)) }
        }
    }
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) = fail("This phone couldn’t advertise ($errorCode). Retry with Bluetooth on.")
    }
    private fun fail(message: String) {
        mutable.update { it.copy(status = message, failed = true, busy = false, prompt = null) }
        close()
    }

    fun start() {
        scope.launch {
            try {
                val radio = requireNotNull(adapter)
                check(radio.isEnabled)
                val server = radio.listenUsingL2capChannel()
                synchronized(guard) {
                    if (closed) { server.close(); return@launch }
                    listener = server
                    scanner = checkNotNull(radio.bluetoothLeScanner)
                    advertiser = checkNotNull(radio.bluetoothLeAdvertiser)
                    val main = AdvertiseData.Builder().addServiceUuid(service).build()
                    val response = AdvertiseData.Builder().addServiceData(service, beacon(profile, server.psm)).build()
                    advertiser?.startAdvertising(AdvertiseSettings.Builder().setConnectable(true)
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED).build(), main, response, advertiseCallback)
                    scanner?.startScan(listOf(ScanFilter.Builder().setServiceUuid(service).build()),
                        ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), scanCallback)
                    mutable.update { it.copy(status = "Looking for nearby phones…") }
                }
                launch {
                    while (isActive) {
                        delay(5000)
                        if (!radio.isEnabled) { fail("Bluetooth is off. Turn it on and retry."); return@launch }
                        mutable.update { it.copy(devices = it.devices.filterValues { device -> SystemClock.elapsedRealtime() - device.seen < 30000 }) }
                    }
                }
                while (isActive) runSocket(server.accept(), false)
            } catch (e: Exception) {
                if (isActive) fail("Nearby couldn’t start. Check Bluetooth, permissions and BLE advertising support, then retry.")
            }
        }
    }

    fun connect(peer: Candidate) {
        scope.launch {
            try { runSocket(checkNotNull(adapter).getRemoteDevice(peer.address).createL2capChannel(peer.port), true) }
            catch (e: Exception) { if (isActive) mutable.update { it.copy(status = "Couldn’t reach this phone. Try again.") } }
        }
    }

    private fun runSocket(socket: BluetoothSocket, initiator: Boolean) {
        synchronized(guard) {
            if (closed || active != null) { socket.close(); return }
            active = socket
            mutable.update { it.copy(busy = true, status = "Checking the other phone…") }
        }
        scope.launch {
            val deadline = SystemClock.elapsedRealtime() + 90000
            val timeout = launch {
                delay(90000)
                synchronized(guard) { if (active === socket) { decision?.complete(false); runCatching { socket.close() } } }
            }
            try {
                if (initiator) socket.connect()
                val verified = pair(Frames(socket.inputStream, socket.outputStream), initiator, profile,
                    identityKeys(), trust.dao::pin, wifiEndpoints(context)) { match ->
                    val wait = CompletableDeferred<Boolean>()
                    synchronized(guard) {
                        if (closed || active !== socket || SystemClock.elapsedRealtime() >= deadline) return@pair false
                        decision = wait
                        mutable.update { it.copy(prompt = PairPrompt(match.name, match.id.takeLast(4).uppercase(), match.digits, match.changed)) }
                    }
                    withTimeout((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)) { wait.await() }
                }
                ensureActive()
                check(SystemClock.elapsedRealtime() < deadline)
                trust.dao.save(verified.peer)
                trust.issue(verified.peer.id, verified.token, verified.endpoints, initiator)
                val address = verified.endpoints.firstOrNull()?.display()
                val route = address?.let { " Wi-Fi endpoint: $it." }.orEmpty()
                mutable.update { it.copy(status = "Verified " + verified.peer.name + "." + route) }
            } catch (e: Exception) {
                val message = when {
                    SystemClock.elapsedRealtime() >= deadline -> "Pairing timed out. Start again on both phones."
                    e is SecurityException -> "Bluetooth permission changed. Check App settings."
                    else -> "Pairing ended. Check both phones and try again."
                }
                if (isActive) mutable.update { it.copy(status = message) }
            } finally {
                timeout.cancel()
                runCatching { socket.close() }
                synchronized(guard) {
                    active = null; decision = null
                    mutable.update { it.copy(busy = false, prompt = null) }
                }
            }
        }
    }

    fun answer(matches: Boolean) = synchronized(guard) {
        decision?.complete(matches)
        mutable.update { it.copy(prompt = null, status = if (matches) "Waiting for the other phone…" else "Pairing cancelled.") }
    }
    override fun close() {
        synchronized(guard) {
            if (closed) return
            closed = true
            scope.cancel()
            decision?.cancel()
            runCatching { listener?.close() }
            runCatching { active?.close() }
            runCatching { scanner?.stopScan(scanCallback) }
            runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        }
    }
}


