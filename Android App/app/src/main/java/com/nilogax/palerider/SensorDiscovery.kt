package com.nilogax.palerider.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nilogax.palerider.data.BikeStore
import com.nilogax.palerider.data.TyreRepository
import com.nilogax.palerider.data.WheelId
import com.nilogax.palerider.data.WheelPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/**
 * Finds a tyre sensor and pairs it to one wheel (front or rear) of one bike.
 *
 * While scanning, nearby devices are listed (the most likely sensors first, then by
 * signal strength). Tapping one does not trust the list: the app connects and
 * checks that the device really has the Outrider vendor service before saving it, so a wrong
 * tap can never pair a phone or a watch.
 *
 * While this is running, [busy] is true and SensorScanReceiver stands down so a background
 * read can't fight the pairing connection.
 *
 * A sensor belongs to one wheel only. A sensor that is already paired elsewhere is still
 * listed (so a sensor can be moved between bikes) but is marked with where it is paired.
 */
@SuppressLint("MissingPermission")
object SensorDiscovery {

    private const val TAG = "PR"

    private const val SCAN_TIMEOUT_MS = 90_000L
    private const val VERIFY_TIMEOUT_MS = 10_000L
    private const val TICK_MS = 1_000L

    // A device not heard from for this long drops off the list
    private const val STALE_MS = 6_000L
    private const val MIN_RSSI = -95
    private const val MAX_CANDIDATES = 8

    data class Candidate(
        val address: String,
        val name: String?,
        val rssi: Int,
        val lastSeenMillis: Long,
        /** Advertises the Outrider service or a matching name. Only a hint: pairing verifies. */
        val likely: Boolean,
        /** Where this sensor is paired now, e.g. "Road front", or null if it isn't paired. */
        val pairedTo: String? = null
    )

    data class State(
        val target: WheelId? = null,
        val scanning: Boolean = false,
        val candidates: List<Candidate> = emptyList(),
        val verifying: String? = null,
        val message: String? = null,
        val paired: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** True from start() until pairing finishes or is cancelled. */
    @Volatile
    var busy = false
        private set

    private val handler = Handler(Looper.getMainLooper())

    // Main thread only. Scan and GATT callbacks hand work to `handler`.
    private val seen = mutableMapOf<String, Candidate>()
    private var appContext: Context? = null
    private var scanner: BluetoothLeScanner? = null
    private var verifyGatt: BluetoothGatt? = null
    private var target: WheelId? = null

    private val tick = object : Runnable {
        override fun run() {
            publish()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val scanTimeout = Runnable {
        Log.d(TAG, "Discovery: scan timed out")
        stopScanOnly()
        handler.removeCallbacks(tick)
        busy = false
        _state.update { it.copy(scanning = false, message = "Scan stopped — no sensor picked") }
    }

    private val verifyTimeout = Runnable {
        failVerify("Couldn't reach that device — keep the wheel spinning and try again")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Discovery: scan failed, code=$errorCode")
            handler.post {
                stopInternal()
                _state.value = State(target = target, message = "Scan failed (code $errorCode)")
            }
        }
    }

    /** Starts scanning for a sensor to pair to [wheel]. Main thread. */
    fun start(context: Context, wheel: WheelId) {
        stopInternal()

        val app = context.applicationContext
        val leScanner = app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (leScanner == null) {
            _state.value = State(target = wheel, message = "Bluetooth is off — turn it on and try again")
            return
        }

        appContext = app
        scanner = leScanner
        target = wheel
        seen.clear()
        BackgroundReader.cancel()
        busy = true
        _state.value = State(target = wheel, scanning = true)

        beginScan()
    }

    /** Cancels pairing and resets the state. Main thread. */
    fun stop() {
        stopInternal()
        _state.value = State()
    }

    /** Verifies the chosen device is an Outrider sensor and, if so, saves it. Main thread. */
    fun select(context: Context, address: String) {
        val wheel = target ?: return
        if (_state.value.verifying != null) return

        val app = context.applicationContext
        val device = app.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address)
        if (device == null) {
            _state.update { it.copy(message = "Bluetooth is unavailable") }
            return
        }

        Log.d(TAG, "Discovery: verifying $address as $wheel")
        stopScanOnly()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(scanTimeout)
        _state.update { it.copy(scanning = false, verifying = address, message = null) }

        val gatt = device.connectGatt(app, false, createVerifyCallback(address), BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            failVerify("Couldn't start a connection")
            return
        }
        verifyGatt = gatt
        handler.postDelayed(verifyTimeout, VERIFY_TIMEOUT_MS)
    }

    private fun beginScan() {
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner?.startScan(null, settings, scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Discovery: startScan failed", e)
            busy = false
            _state.update { it.copy(scanning = false, message = "Couldn't start scanning") }
            return
        }
        handler.removeCallbacks(tick)
        handler.removeCallbacks(scanTimeout)
        handler.postDelayed(tick, TICK_MS)
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    private fun stopScanOnly() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Discovery: stopScan failed", e)
        }
    }

    private fun stopInternal() {
        stopScanOnly()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(scanTimeout)
        releaseVerify()
        seen.clear()
        busy = false
    }

    private fun handleResult(result: ScanResult) {
        // Pull everything out on the callback thread, then hand it to the main thread
        val address = result.device.address
        val name = result.scanRecord?.deviceName ?: result.device.name
        val services = result.scanRecord?.serviceUuids.orEmpty()
            .map { it.uuid.toString().lowercase(Locale.ROOT) }
        val likely = services.any { it.startsWith(TyreReaderService.SERVICE_PREFIX) } ||
                name?.contains("outrider", ignoreCase = true) == true
        val rssi = result.rssi
        val now = System.currentTimeMillis()

        handler.post {
            if (!_state.value.scanning) return@post
            if (!seen.containsKey(address)) {
                Log.d(TAG, "Discovery: new device $address name=$name rssi=$rssi services=$services likely=$likely")
            }
            seen[address] = Candidate(address, name, rssi, now, likely)
        }
    }

    /** eg "Road front" if the sensor is paired to a wheel, else null. */
    private fun pairedToLabel(context: Context, address: String): String? {
        val bike = BikeStore.bikeForAddress(context, address) ?: return null
        val position = WheelPosition.values().firstOrNull { bike.macFor(it) == address.uppercase() } ?: return null
        return "${bike.name} ${position.name.lowercase(Locale.ROOT)}"
    }

    private fun publish() {
        val app = appContext ?: return
        val now = System.currentTimeMillis()

        seen.values.removeAll { now - it.lastSeenMillis > 30_000L }

        val list = seen.values
            .filter { now - it.lastSeenMillis <= STALE_MS && it.rssi >= MIN_RSSI }
            .map { it.copy(pairedTo = pairedToLabel(app, it.address)) }
            .sortedWith(compareByDescending<Candidate> { it.likely }.thenByDescending { it.rssi })
            .take(MAX_CANDIDATES)

        _state.update { it.copy(candidates = list) }
    }

    private fun createVerifyCallback(address: String) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "Discovery $address: connectionStateChange status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!gatt.discoverServices()) {
                    Log.w(TAG, "Discovery $address: discoverServices() returned false")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close()
                handler.post {
                    // Only a failure if we didn't release this GATT ourselves
                    if (verifyGatt === gatt) {
                        verifyGatt = null
                        failVerify("Sensor disconnected — keep the wheel spinning and try again")
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val isSensor = status == BluetoothGatt.GATT_SUCCESS && gatt.services.any {
                it.uuid.toString().lowercase(Locale.ROOT).startsWith(TyreReaderService.SERVICE_PREFIX)
            }
            Log.d(TAG, "Discovery $address: services status=$status isSensor=$isSensor")
            handler.post {
                if (verifyGatt !== gatt) return@post
                if (isSensor) completePairing(address)
                else failVerify("That device isn't an Outrider sensor — pick another")
            }
        }
    }

    private fun completePairing(address: String) {
        val app = appContext ?: return
        val wheel = target ?: return

        releaseVerify()
        BikeStore.setSensor(app, wheel, address)
        val bikeName = BikeStore.bike(app, wheel.bikeId)?.name ?: "bike"
        TyreRepository.setStatus("$bikeName ${wheel.position.name.lowercase(Locale.ROOT)} sensor paired")
        // Watch for the new sensor in the background from now on
        TyreReaderService.registerBackgroundScan(app)

        Log.d(TAG, "Discovery: $wheel paired as $address")
        busy = false
        seen.clear()
        _state.value = State(target = wheel, paired = true)
    }

    private fun failVerify(message: String) {
        if (_state.value.verifying == null) return
        Log.w(TAG, "Discovery: verify failed: $message")
        releaseVerify()
        _state.update { it.copy(scanning = true, verifying = null, message = message) }
        beginScan()
    }

    private fun releaseVerify() {
        handler.removeCallbacks(verifyTimeout)
        // Clear the field first so the disconnect callback isn't treated as a failure
        verifyGatt?.let { gatt ->
            verifyGatt = null
            gatt.disconnect()
            gatt.close()
        }
    }
}