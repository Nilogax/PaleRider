package com.nilogax.palerider.ble

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nilogax.palerider.data.BikeStore
import com.nilogax.palerider.data.TyreRepository
import com.nilogax.palerider.data.WheelId
import com.nilogax.palerider.data.WheelPosition
import java.util.Locale

/**
 * Reads tyres when the app is in the background, with no foreground service involved.
 *
 * Android refuses startForegroundService() from a background scan-result broadcast, but
 * connecting GATT and posting a notification don't need a foreground service. So when
 * SensorScanReceiver is refused it calls start() here, which connects to the paired sensors
 * of the bikes that woke up, takes one pressure reading from each, disconnects (so the Garmin
 * isn't blocked) and posts one pressure notification per bike as soon as that bike is done.
 * No tap is needed.
 *
 * Calling start() while a read is running adds the new bikes to it, so two bikes waking at
 * slightly different times are both handled.
 *
 * If nothing at all could be read within the time limits, a tap-to-read notification is
 * shown instead (a user tap is always allowed to start the foreground service).
 *
 * The foreground service handles the in-app buttons. While it is running, start() does
 * nothing, and when the service starts it calls cancel(), so the two never fight over the
 * same sensors.
 */
@SuppressLint("MissingPermission")
object BackgroundReader {

    private const val TAG = "PR"

    // Backstop for the whole background read, restarted whenever bikes are added
    private const val OVERALL_TIMEOUT_MS = 20_000L

    // Per attempt, from connect request until the first pressure reading
    private const val ATTEMPT_TIMEOUT_MS = 8_000L
    private const val MAX_ATTEMPTS = 2
    private const val RETRY_DELAY_MS = 300L

    const val FALLBACK_NOTIF_ID = 103

    private val handler = Handler(Looper.getMainLooper())

    // All of the state below is only touched on the main thread. GATT callbacks arrive on
    // Binder threads, so they hand work to `handler` rather than touching it directly.
    private var appContext: Context? = null
    private var adapter: BluetoothAdapter? = null
    private var active = false
    private var startMillis = 0L
    private val wheels = LinkedHashSet<WheelId>()
    private val gatts = mutableMapOf<WheelId, BluetoothGatt>()
    private val done = mutableSetOf<WheelId>()
    private val failed = mutableSetOf<WheelId>()
    private val attempts = mutableMapOf<WheelId, Int>()
    private val notifiedBikes = mutableSetOf<String>()
    private val watchdogs = mutableMapOf<WheelId, Runnable>()

    private val overallTimeout = Runnable { finish("timed out") }

    /** True while a background read is in progress. Main thread. */
    val isActive: Boolean
        get() = active

    private fun watchdogFor(wheel: WheelId): Runnable =
        watchdogs.getOrPut(wheel) {
            Runnable { onAttemptFailed(wheel, "no reading after $ATTEMPT_TIMEOUT_MS ms") }
        }

    private fun label(wheel: WheelId): String {
        val name = appContext?.let { BikeStore.bike(it, wheel.bikeId)?.name } ?: "?"
        return "$name ${wheel.position.name.lowercase(Locale.ROOT)}"
    }

    /**
     * Starts a background read of the given bikes (null = every bike). If a read is already
     * running, the bikes are added to it. Does nothing while the foreground service is
     * running, since that is already reading. Main thread.
     */
    fun start(context: Context, bikeIds: Collection<String>? = null) {
        if (TyreReaderService.running) {
            Log.d(TAG, "BG: foreground service is running, standing down")
            return
        }
        val app = context.applicationContext
        val paired = BikeStore.pairedWheels(app, bikeIds?.toSet())
        if (paired.isEmpty()) {
            Log.d(TAG, "BG: no sensors paired on those bikes, nothing to read")
            return
        }

        if (active) {
            // Add only wheels not already handled in this read
            val fresh = paired.filter { it !in wheels }
            if (fresh.isEmpty()) {
                Log.d(TAG, "BG: read already running for those bikes")
                return
            }
            Log.d(TAG, "BG: adding ${fresh.size} wheel(s) to the running read")
            addWheels(fresh, delayOffset = 1)
            handler.removeCallbacks(overallTimeout)
            handler.postDelayed(overallTimeout, OVERALL_TIMEOUT_MS)
            return
        }

        val btAdapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            Log.w(TAG, "BG: no Bluetooth adapter available, posting tap-to-read notification")
            postTapToReadNotification(app)
            return
        }

        appContext = app
        adapter = btAdapter
        active = true
        startMillis = System.currentTimeMillis()
        wheels.clear()
        done.clear()
        failed.clear()
        attempts.clear()
        notifiedBikes.clear()

        Log.d(TAG, "BG: starting background read of ${paired.size} wheel(s)")
        TyreRepository.setStatus("Reading tyres in the background...")

        addWheels(paired, delayOffset = 0)
        handler.postDelayed(overallTimeout, OVERALL_TIMEOUT_MS)
    }

    /** Registers wheels and connects to them, 400 ms apart to avoid BLE stack collisions. */
    private fun addWheels(fresh: List<WheelId>, delayOffset: Int) {
        wheels.addAll(fresh)
        fresh.forEachIndexed { index, wheel ->
            val delay = 400L * (index + delayOffset)
            if (delay == 0L) connect(wheel)
            else handler.postDelayed({ if (active) connect(wheel) }, delay)
        }
    }

    /** Stops any background read without posting anything. Safe from any thread. */
    fun cancel() {
        if (Looper.myLooper() == Looper.getMainLooper()) cancelNow() else handler.post { cancelNow() }
    }

    private fun cancelNow() {
        if (!active) return
        Log.d(TAG, "BG: cancelled")
        teardown()
    }

    private fun connect(wheel: WheelId) {
        val app = appContext ?: return
        if (!active || wheel in done || gatts.containsKey(wheel)) return

        val mac = BikeStore.addressOf(app, wheel)
        val device = mac?.let { adapter?.getRemoteDevice(it) }
        if (device == null) {
            Log.w(TAG, "BG ${label(wheel)}: no sensor paired or no adapter, skipping")
            failed.add(wheel)
            wheelSettled(wheel)
            return
        }

        val attempt = (attempts[wheel] ?: 0) + 1
        attempts[wheel] = attempt
        Log.d(TAG, "BG ${label(wheel)}: connecting (attempt $attempt of $MAX_ATTEMPTS)")

        val gatt = device.connectGatt(app, false, createCallback(wheel), BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            onAttemptFailed(wheel, "connectGatt returned null")
            return
        }
        gatts[wheel] = gatt

        handler.removeCallbacks(watchdogFor(wheel))
        handler.postDelayed(watchdogFor(wheel), ATTEMPT_TIMEOUT_MS)
    }

    private fun onAttemptFailed(wheel: WheelId, reason: String) {
        if (!active || wheel in done) return

        val attemptsSoFar = attempts[wheel] ?: 0
        Log.w(TAG, "BG ${label(wheel)}: $reason (attempt $attemptsSoFar of $MAX_ATTEMPTS)")
        releaseWheel(wheel)

        if (attemptsSoFar >= MAX_ATTEMPTS) {
            Log.w(TAG, "BG ${label(wheel)}: giving up")
            failed.add(wheel)
            wheelSettled(wheel)
            return
        }

        handler.postDelayed({ connect(wheel) }, RETRY_DELAY_MS)
    }

    /**
     * A wheel has been read or abandoned. Once every wheel of its bike is settled, that bike's
     * notification goes out straight away; once every bike is settled, the read is over.
     */
    private fun wheelSettled(wheel: WheelId) {
        notifyBikeIfComplete(wheel.bikeId)
        if (wheels.all { it in done || it in failed }) {
            finish("every wheel read or abandoned")
        }
    }

    private fun notifyBikeIfComplete(bikeId: String) {
        val app = appContext ?: return
        if (bikeId in notifiedBikes) return
        val bikeWheels = wheels.filter { it.bikeId == bikeId }
        if (bikeWheels.none { it in done }) return
        if (!bikeWheels.all { it in done || it in failed }) return

        notifiedBikes.add(bikeId)

        // Log this read to the bike's history, same as the foreground service does
        val state = TyreRepository.state.value
        val frontReading = state.reading(WheelId(bikeId, WheelPosition.FRONT))
        val rearReading = state.reading(WheelId(bikeId, WheelPosition.REAR))
        val frontPsi = frontReading.psi?.takeIf { frontReading.lastUpdatedMillis >= startMillis }
        val rearPsi = rearReading.psi?.takeIf { rearReading.lastUpdatedMillis >= startMillis }
        BikeStore.recordHistory(app, bikeId, frontPsi, rearPsi)

        TyreReaderService.showResultNotification(app, bikeId, startMillis, freshAlert = true)
    }

    private fun onPressure(wheel: WheelId, gatt: BluetoothGatt, bytes: ByteArray) {
        if (!active || gatts[wheel] !== gatt) {
            Log.d(TAG, "BG ${label(wheel)}: ignoring packet from a GATT that is no longer active")
            return
        }
        val psi = TyreReaderService.parsePressure(bytes)
        if (psi == null) {
            Log.w(TAG, "BG ${label(wheel)}: pressure packet too short (${bytes.size} bytes)")
            return
        }
        Log.d(TAG, "BG ${label(wheel)}: packet=${bytes.joinToString(" ") { "%02x".format(it) }} psi=$psi")

        TyreRepository.updatePressure(wheel, psi)
        done.add(wheel)
        releaseWheel(wheel)
        wheelSettled(wheel)
    }

    /** Ends the read, frees the sensors for the Garmin and posts anything not yet posted. */
    private fun finish(reason: String) {
        if (!active) return
        Log.d(TAG, "BG: finished ($reason), read=${done.map { label(it) }} failed=${failed.map { label(it) }}")
        // Wheels still outstanding (overall timeout) count as failed
        for (wheel in wheels) if (wheel !in done) failed.add(wheel)
        teardown()

        val app = appContext ?: return
        if (done.isEmpty()) {
            TyreRepository.setStatus("Background read failed — Garmin free to connect")
            postTapToReadNotification(app)
            return
        }

        // Bikes that got at least one tyre but weren't posted yet (e.g. the other tyre timed out)
        for (bikeId in wheels.map { it.bikeId }.distinct()) notifyBikeIfComplete(bikeId)

        val msg = if (done.size == wheels.size) "Tyres read — Garmin free to connect"
        else "Some tyres could not be read — Garmin free to connect"
        TyreRepository.setStatus(msg)
    }

    private fun teardown() {
        active = false
        handler.removeCallbacksAndMessages(null)
        for (wheel in gatts.keys.toList()) releaseWheel(wheel)
    }

    private fun releaseWheel(wheel: WheelId) {
        handler.removeCallbacks(watchdogFor(wheel))
        // Remove from the map first so the disconnect callback isn't treated as a failure
        gatts.remove(wheel)?.let { gatt ->
            gatt.disconnect()
            gatt.close()
        }
        TyreRepository.setConnectionState(wheel, false)
    }

    private fun createCallback(wheel: WheelId) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "BG ${label(wheel)}: connectionStateChange status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!gatt.discoverServices()) {
                    Log.w(TAG, "BG ${label(wheel)}: discoverServices() returned false")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close()
                handler.post {
                    // Only a failure if we didn't release this GATT ourselves
                    if (gatts[wheel] === gatt) {
                        gatts.remove(wheel)
                        onAttemptFailed(wheel, "unexpected disconnect (status=$status)")
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post {
                    if (gatts[wheel] === gatt) onAttemptFailed(wheel, "service discovery failed, status=$status")
                }
                return
            }

            val characteristic = TyreReaderService.findPressureCharacteristic(gatt)
            if (characteristic == null) {
                handler.post {
                    if (gatts[wheel] === gatt) onAttemptFailed(wheel, "no pressure characteristic")
                }
                return
            }

            // Stagger descriptor writes between wheels to prevent BLE command collisions
            handler.post {
                val slot = wheels.indexOf(wheel).coerceAtLeast(0) % 4
                handler.postDelayed({
                    if (gatts[wheel] !== gatt) return@postDelayed
                    TyreReaderService.enableNotifications(gatt, characteristic, "BG ${label(wheel)}")
                }, 100L + 300L * slot)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "BG ${label(wheel)}: notifications enabled")
            } else {
                // No reading will arrive, so the attempt watchdog will retry this wheel
                Log.w(TAG, "BG ${label(wheel)}: CCCD write failed, status=$status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handler.post { onPressure(wheel, gatt, value) }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Copy on the callback thread; the characteristic's buffer can change under us
            @Suppress("DEPRECATION")
            val value = characteristic.value?.copyOf() ?: return
            handler.post { onPressure(wheel, gatt, value) }
        }
    }

    /** Last resort: tapping this starts the service. A user tap is always allowed to do that. */
    fun postTapToReadNotification(context: Context) {
        TyreReaderService.ensureNotificationChannels(context)

        val readIntent = Intent(context, TyreReaderService::class.java).apply {
            action = TyreReaderService.ACTION_BURST_READ
        }
        val pendingRead = PendingIntent.getForegroundService(
            context,
            2,
            readIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, TyreReaderService.CHANNEL_ALERT_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Tyre sensors awake")
            .setContentText("Couldn't read pressures — tap to try again")
            .setContentIntent(pendingRead)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        context.getSystemService(NotificationManager::class.java).notify(FALLBACK_NOTIF_ID, notif)
    }
}