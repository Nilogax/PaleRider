package com.nilogax.palerider.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nilogax.palerider.MainActivity
import com.nilogax.palerider.R
import com.nilogax.palerider.data.BikeStore
import com.nilogax.palerider.data.NotificationGapPrefs
import com.nilogax.palerider.data.OutriderConfig
import com.nilogax.palerider.data.PressureStatus
import com.nilogax.palerider.data.TyreRepository
import com.nilogax.palerider.data.UnitPrefs
import com.nilogax.palerider.data.WheelId
import com.nilogax.palerider.data.WheelPosition
import com.nilogax.palerider.data.WheelReading
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

@SuppressLint("MissingPermission")
class TyreReaderService : Service() {

    companion object {
        private const val TAG = "PR"

        const val ACTION_BURST_READ = "com.nilogax.palerider.BURST_READ"
        const val ACTION_SET_KEEP_ALIVE = "com.nilogax.palerider.SET_KEEP_ALIVE"
        const val EXTRA_KEEP_ALIVE = "extra_keep_alive"
        const val EXTRA_KEEP_ALIVE_DURATION_MINS = "extra_keep_alive_duration_mins"
        const val EXTRA_BIKE_IDS = "extra_bike_ids"

        private const val CHANNEL_SERVICE_ID = "palerider_ble_service"
        const val CHANNEL_ALERT_ID = "palerider_pressure_alert"
        const val CHANNEL_WARNING_ID = "palerider_pressure_warning"
        private const val FOREGROUND_NOTIF_ID = 101
        private const val RESULT_NOTIF_BASE = 1000

        private const val BURST_TIMEOUT_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 7_000L
        private const val READ_TIMEOUT_MS = 6_000L
        private const val MAX_CONNECT_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 300L

        const val SERVICE_PREFIX = "efcd1400"
        private const val PRESSURE_CHAR_PREFIX = "efcd1401"

        @Volatile
        var running = false
            private set

        private val activeWarningNotifs = mutableSetOf<String>()

        // Floor for the merge window, so a gap of 0 still lets a second tyre complete a partial
        private const val MIN_PARTIAL_WINDOW_MS = 30_000L

        // bikeId -> start of the read whose notification is still missing a tyre
        private val partialStart = mutableMapOf<String, Long>()

        private fun addressFilter(mac: String): ScanFilter =
            ScanFilter.Builder().setDeviceAddress(mac).build()

        fun ensureNotificationChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)

            val audioAttrs = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()

            val okSoundUri = Uri.parse(
                "${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.palerider_ok}"
            )
            val warningSoundUri = Uri.parse(
                "${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.palerider_warning}"
            )

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SERVICE_ID,
                    "BLE Sensor Link",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERT_ID,
                    "Tyre Pressure Readings",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setSound(okSoundUri, audioAttrs)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_WARNING_ID,
                    "Tyre Pressure Warnings",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts when a tyre reading is outside your min/max target range"
                    setSound(warningSoundUri, audioAttrs)
                    vibrationPattern = longArrayOf(0, 200, 100, 200)
                }
            )
        }

        fun parsePressure(bytes: ByteArray): Float? {
            if (bytes.size < 2) return null
            val raw = ByteBuffer.wrap(bytes, 0, 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .short
                .toInt() and 0xFFFF
            val gaugePsi = (raw * OutriderConfig.PSI_PER_BIT) - OutriderConfig.ATMOSPHERIC_OFFSET_PSI
            return gaugePsi.coerceAtLeast(0f)
        }

        private fun freshPsi(reading: WheelReading, sinceMillis: Long): Float? =
            reading.psi?.takeIf { reading.lastUpdatedMillis >= sinceMillis }

        private fun notificationIdFor(bikeId: String): Int =
            RESULT_NOTIF_BASE + (bikeId.hashCode() and 0xFFFF)

        fun cancelResultNotification(context: Context, bikeId: String) {
            activeWarningNotifs.remove(bikeId)
            partialStart.remove(bikeId)
            context.getSystemService(NotificationManager::class.java).cancel(notificationIdFor(bikeId))
        }

        fun showResultNotification(
            context: Context,
            bikeId: String,
            sinceMillis: Long,
            freshAlert: Boolean = false
        ) {
            val bike = BikeStore.bike(context, bikeId) ?: return
            ensureNotificationChannels(context)
            val state = TyreRepository.state.value
            val unit = UnitPrefs.get(context)

            // If an earlier notification for this bike was missing a tyre, keep counting
            // readings from that earlier read (for as long as the notification gap) so the
            // two reads merge into one result.
            val nowMs = System.currentTimeMillis()
            val windowMs = maxOf(NotificationGapPrefs.getMinutes(context) * 60_000L, MIN_PARTIAL_WINDOW_MS)
            val openSince = partialStart[bikeId]
                ?.takeIf { nowMs - it < windowMs && it <= sinceMillis }
            val since = openSince ?: sinceMillis
            val merging = openSince != null && openSince < sinceMillis

            val wheelSummaries = bike.pairedWheels.map { wheel ->
                val psi = freshPsi(state.reading(wheel), since)
                val status = bike.statusFor(wheel.position, psi)
                val tag = if (wheel.position == WheelPosition.FRONT) "F: " else "R: "
                val valueStr = if (psi != null) unit.format(psi) else "--"
                val statusStr = when (status) {
                    PressureStatus.LOW -> "LOW"
                    PressureStatus.HIGH -> "HIGH"
                    PressureStatus.NORMAL -> valueStr
                }
                Triple(status, tag + statusStr, tag + valueStr)
            }

            val hasWarning = wheelSummaries.any { it.first != PressureStatus.NORMAL }
            val title = wheelSummaries.joinToString(" · ") { it.second } + " (" + bike.name + ")"
            val text = if (hasWarning) {
                wheelSummaries.joinToString(" · ") { it.third } + " · Tap to open"
            } else {
                "Tap to open PaleRider"
            }

            val channelId = if (hasWarning) CHANNEL_WARNING_ID else CHANNEL_ALERT_ID

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingOpen = PendingIntent.getActivity(
                context, 1, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val nm = context.getSystemService(NotificationManager::class.java)
            val id = notificationIdFor(bikeId)

            // Every paired tyre has a reading in this notification
            val allRead = bike.pairedWheels.isNotEmpty() &&
                    bike.pairedWheels.all { freshPsi(state.reading(it), since) != null }

            val escalatedToWarning = hasWarning && activeWarningNotifs.add(bikeId)
            if (!hasWarning) activeWarningNotifs.remove(bikeId)

            // Alert on a new read, or when a later read completes an earlier partial result.
            // A merge that still has a tyre missing updates quietly.
            val completedPartial = merging && allRead
            val shouldAlert = (freshAlert && !merging) || completedPartial || escalatedToWarning
            if (shouldAlert) nm.cancel(id)

            // The minimum-gap clock only starts once every paired tyre is in the notification.
            // While a tyre is missing, remember where the open result began.
            if (allRead) {
                partialStart.remove(bikeId)
                NotificationGapPrefs.recordNotified(context, bikeId)
            } else {
                partialStart[bikeId] = since
            }

            val notif = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(
                    if (hasWarning) R.drawable.ic_pressure_warning
                    else R.drawable.ic_pressure_ok
                )
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pendingOpen)
                .setAutoCancel(true)
                .setOnlyAlertOnce(!shouldAlert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            nm.notify(id, notif)
        }
        fun findPressureCharacteristic(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
            for (service in gatt.services) {
                val serviceId = service.uuid.toString().lowercase(Locale.ROOT)
                if (!serviceId.startsWith(SERVICE_PREFIX)) continue

                val base = serviceId.substring(SERVICE_PREFIX.length)
                val candidates = service.characteristics.filter {
                    it.uuid.toString().lowercase(Locale.ROOT).startsWith(PRESSURE_CHAR_PREFIX)
                }
                val exact = candidates.firstOrNull {
                    it.uuid.toString().lowercase(Locale.ROOT).substring(PRESSURE_CHAR_PREFIX.length) == base
                }
                val chosen = exact ?: candidates.firstOrNull()
                if (chosen != null) return chosen
            }
            return null
        }

        fun enableNotifications(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            label: String
        ) {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(OutriderConfig.CCCD_UUID) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }

        fun registerBackgroundScan(context: Context) {
            val btManager = context.getSystemService(BluetoothManager::class.java)
            val adapter = btManager?.adapter
            if (adapter == null || !adapter.isEnabled) {
                TyreRepository.setBackgroundWatch(false)
                return
            }
            val scanner = adapter.bluetoothLeScanner ?: run {
                TyreRepository.setBackgroundWatch(false)
                return
            }
            val pendingIntent = getScanPendingIntent(context)
            try {
                scanner.stopScan(pendingIntent)
            } catch (_: Exception) {
            }

            val filters = BikeStore.pairedAddresses(context).map { addressFilter(it) }
            if (filters.isEmpty()) {
                TyreRepository.setBackgroundWatch(false)
                return
            }

            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
            val result = try {
                scanner.startScan(filters, settings, pendingIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register background scan", e)
                -1
            }
            TyreRepository.setBackgroundWatch(result == 0)
        }
        fun unregisterBackgroundScan(context: Context) {
            val btManager = context.getSystemService(BluetoothManager::class.java)
            btManager?.adapter?.bluetoothLeScanner?.stopScan(getScanPendingIntent(context))
            TyreRepository.setBackgroundWatch(false)
        }

        private fun getScanPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, SensorScanReceiver::class.java)
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        }
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        getSystemService(BluetoothManager::class.java)?.adapter
    }

    private val activeGatts = mutableMapOf<WheelId, BluetoothGatt>()
    private val targets = LinkedHashSet<WheelId>()
    private val burstCompleted = mutableSetOf<WheelId>()
    private val gaveUp = mutableSetOf<WheelId>()
    private val attempts = mutableMapOf<WheelId, Int>()
    private val notified = mutableSetOf<String>()
    private val watchdogs = mutableMapOf<WheelId, Runnable>()
    private val handler = Handler(Looper.getMainLooper())

    private var burstActive = false
    private var burstStartMillis = 0L

    private val keepAlive: Boolean
        get() = TyreRepository.state.value.keepAlive

    private val timeoutRunnable = Runnable {
        if (!keepAlive) {
            finishBurstAndRelease("Burst timed out — releasing Garmin", postNotification = true)
        }
    }

    private val keepAliveExpiryRunnable = Runnable {
        Log.d(TAG, "Keep alive timer expired — auto-releasing")
        TyreRepository.setKeepAliveExpirationMillis(0L)
        finishBurstAndRelease("Keep alive timer expired — Garmin free to connect", postNotification = false)
    }

    private fun watchdogFor(wheel: WheelId): Runnable =
        watchdogs.getOrPut(wheel) { Runnable { onWatchdog(wheel) } }

    private fun label(wheel: WheelId): String {
        val name = BikeStore.bike(this, wheel.bikeId)?.name ?: "?"
        return "$name ${wheel.position.name.lowercase(Locale.ROOT)}"
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat("Connecting to tyre sensors...")

        when (intent?.action) {
            ACTION_SET_KEEP_ALIVE -> {
                val enable = intent.getBooleanExtra(EXTRA_KEEP_ALIVE, false)
                val durationMins = intent.getIntExtra(EXTRA_KEEP_ALIVE_DURATION_MINS, 5)
                handler.removeCallbacks(keepAliveExpiryRunnable)

                if (enable) {
                    TyreRepository.setKeepAlive(true)
                    val expiry = System.currentTimeMillis() + (durationMins * 60_000L)
                    TyreRepository.setKeepAliveExpirationMillis(expiry)
                    handler.postDelayed(keepAliveExpiryRunnable, durationMins * 60_000L)

                    BackgroundReader.cancel()
                    handler.removeCallbacks(timeoutRunnable)
                    val wheels = BikeStore.pairedWheels(this)
                    if (wheels.isEmpty()) {
                        TyreRepository.setKeepAlive(false)
                        TyreRepository.setKeepAliveExpirationMillis(0L)
                        finishBurstAndRelease("No sensors paired", postNotification = false)
                    } else {
                        TyreRepository.setStatus("Keeping connection alive for adjustment")
                        addWheels(wheels, reconnectFinished = true)
                    }
                } else {
                    TyreRepository.setKeepAlive(false)
                    TyreRepository.setKeepAliveExpirationMillis(0L)
                    finishBurstAndRelease("Released — Garmin free to connect", postNotification = false)
                }
            }

            ACTION_BURST_READ -> {
                val bikeIds = intent.getStringArrayExtra(EXTRA_BIKE_IDS)?.toSet()
                val wheels = BikeStore.pairedWheels(this, bikeIds)
                if (keepAlive) {
                    // Ignore burst requests while keep alive is active
                } else if (wheels.isEmpty()) {
                    if (!burstActive) finishBurstAndRelease("No sensors paired", postNotification = true)
                } else {
                    getSystemService(NotificationManager::class.java).cancel(BackgroundReader.FALLBACK_NOTIF_ID)
                    BackgroundReader.cancel()
                    TyreRepository.setStatus("Reading tyres...")
                    addWheels(wheels, reconnectFinished = false)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun addWheels(wheels: List<WheelId>, reconnectFinished: Boolean) {
        val fresh = wheels.filter { wheel ->
            wheel !in targets || (reconnectFinished && !activeGatts.containsKey(wheel))
        }
        if (fresh.isEmpty()) return

        val extending = burstActive
        if (!extending) {
            burstActive = true
            running = true
            burstStartMillis = System.currentTimeMillis()
            notified.clear()
            activeWarningNotifs.clear()
        }

        for (wheel in fresh) {
            targets.add(wheel)
            burstCompleted.remove(wheel)
            gaveUp.remove(wheel)
            attempts.remove(wheel)
        }

        fresh.forEachIndexed { index, wheel ->
            val delay = 400L * (if (extending) index + 1 else index)
            if (delay == 0L) connectWheel(wheel)
            else handler.postDelayed({ connectWheel(wheel) }, delay)
        }

        if (!keepAlive) {
            handler.removeCallbacks(timeoutRunnable)
            handler.postDelayed(timeoutRunnable, BURST_TIMEOUT_MS)
        }
    }

    private fun connectWheel(wheel: WheelId) {
        if (!burstActive || wheel !in targets) return
        if (activeGatts.containsKey(wheel)) return
        val mac = BikeStore.addressOf(this, wheel) ?: run {
            targets.remove(wheel)
            finishIfDone()
            return
        }

        val attempt = (attempts[wheel] ?: 0) + 1
        attempts[wheel] = attempt
        val device = bluetoothAdapter?.getRemoteDevice(mac) ?: return
        val gatt = device.connectGatt(this, false, createGattCallback(wheel), BluetoothDevice.TRANSPORT_LE) ?: return
        activeGatts[wheel] = gatt

        handler.removeCallbacks(watchdogFor(wheel))
        handler.postDelayed(watchdogFor(wheel), CONNECT_TIMEOUT_MS)
    }

    private fun onWatchdog(wheel: WheelId) {
        retryWheel(wheel, "watchdog timeout")
    }

    private fun retryWheel(wheel: WheelId, reason: String) {
        if (!burstActive || wheel !in targets) return
        if (!keepAlive && wheel in burstCompleted) return

        val attemptsSoFar = attempts[wheel] ?: 0
        disconnectWheel(wheel)

        if (attemptsSoFar >= MAX_CONNECT_ATTEMPTS) {
            gaveUp.add(wheel)
            finishIfDone()
            return
        }

        handler.postDelayed({
            if (keepAlive || wheel !in burstCompleted) connectWheel(wheel)
        }, RETRY_DELAY_MS)
    }

    private fun finishIfDone() {
        if (!burstActive || keepAlive) return
        if (targets.all { it in burstCompleted || it in gaveUp }) {
            // Log history for bikes read during this burst session[cite: 8]
            val distinctBikeIds = targets.map { it.bikeId }.toSet()
            for (bikeId in distinctBikeIds) {
                val bike = BikeStore.bike(this, bikeId)
                if (bike != null) {
                    val state = TyreRepository.state.value
                    val fPsi = freshPsi(state.reading(WheelId(bike.id, WheelPosition.FRONT)), burstStartMillis)
                    val rPsi = freshPsi(state.reading(WheelId(bike.id, WheelPosition.REAR)), burstStartMillis)
                    BikeStore.recordHistory(this, bike.id, fPsi, rPsi)
                }
            }

            handler.removeCallbacks(timeoutRunnable)
            finishBurstAndRelease("Tyres read — Garmin free to connect", postNotification = true)
        }
    }

    private fun createGattCallback(wheel: WheelId) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                handler.post { onLinkUp(wheel, gatt) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close()
                handler.post {
                    if (activeGatts[wheel] === gatt) {
                        activeGatts.remove(wheel)
                        TyreRepository.setConnectionState(wheel, false)
                        retryWheel(wheel, "disconnected")
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post { onServicesReady(wheel, gatt, status) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {}

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handler.post { handlePressureBytes(wheel, gatt, value) }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value?.copyOf() ?: return
            handler.post { handlePressureBytes(wheel, gatt, value) }
        }
    }

    private fun onLinkUp(wheel: WheelId, gatt: BluetoothGatt) {
        if (activeGatts[wheel] !== gatt) return
        handler.removeCallbacks(watchdogFor(wheel))
        handler.postDelayed(watchdogFor(wheel), READ_TIMEOUT_MS)

        TyreRepository.setConnectionState(wheel, true)
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        gatt.discoverServices()
    }

    private fun onServicesReady(wheel: WheelId, gatt: BluetoothGatt, status: Int) {
        if (activeGatts[wheel] !== gatt || status != BluetoothGatt.GATT_SUCCESS) {
            retryWheel(wheel, "services failed")
            return
        }

        val characteristic = findPressureCharacteristic(gatt) ?: run {
            retryWheel(wheel, "no char")
            return
        }

        val delayMillis = 100L + 300L * (targets.indexOf(wheel).coerceAtLeast(0) % 4)
        handler.postDelayed({
            if (activeGatts[wheel] === gatt) enableNotifications(gatt, characteristic, label(wheel))
        }, delayMillis)
    }

    private fun handlePressureBytes(wheel: WheelId, gatt: BluetoothGatt, bytes: ByteArray) {
        if (activeGatts[wheel] !== gatt) return
        val psi = parsePressure(bytes) ?: return
        handler.removeCallbacks(watchdogFor(wheel))
        attempts[wheel] = 0

        TyreRepository.updatePressure(wheel, psi)

        if (!keepAlive) {
            val firstForBike = notified.add(wheel.bikeId)
            showResultNotification(this, wheel.bikeId, burstStartMillis, freshAlert = firstForBike)
            burstCompleted.add(wheel)
            disconnectWheel(wheel)
            finishIfDone()
        }
    }

    private fun disconnectWheel(wheel: WheelId) {
        handler.removeCallbacks(watchdogFor(wheel))
        activeGatts.remove(wheel)?.let {
            it.disconnect()
            it.close()
        }
        TyreRepository.setConnectionState(wheel, false)
    }

    private fun releaseEverything() {
        burstActive = false
        running = false
        handler.removeCallbacksAndMessages(null)
        for (wheel in (activeGatts.keys + targets).toList()) disconnectWheel(wheel)
        targets.clear()
        burstCompleted.clear()
        gaveUp.clear()
        attempts.clear()
        notified.clear()
    }

    private fun finishBurstAndRelease(statusMsg: String, postNotification: Boolean) {
        if (!postNotification) {
            notified.clear()
        }
        releaseEverything()
        TyreRepository.setStatus(statusMsg)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(text: String) {
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_SERVICE_ID)
            .setSmallIcon(R.drawable.ic_pressure_ok)
            .setContentTitle("PaleRider")
            .setContentText(text)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(FOREGROUND_NOTIF_ID, notif)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(keepAliveExpiryRunnable)
        releaseEverything()
        TyreRepository.setKeepAlive(false)
        TyreRepository.setKeepAliveExpirationMillis(0L)
        super.onDestroy()
    }
}