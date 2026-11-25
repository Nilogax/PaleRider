package com.nilogax.palerider.ble

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.nilogax.palerider.data.BikeStore
import com.nilogax.palerider.data.NotificationGapPrefs
import com.nilogax.palerider.data.TyreRepository
import java.util.Locale

/**
 * Receives background scan results for the paired sensors of every bike.
 *
 * Android sometimes refuses to start the foreground service from this receiver while the app
 * is in the background. When that happens, BackgroundReader reads the tyres itself (no
 * foreground service needed) and posts a pressure notification per bike.
 */
class SensorScanReceiver : BroadcastReceiver() {

    /** What we know about one bike's current wake-up. */
    private class WakeSession {
        var lastAdvertTime = 0L
        var startTime = 0L
        var attempts = 0
        var lastAttemptTime = 0L
        var denied = false
        // This wake-up began inside the notification gap, so it is ignored for its whole length
        var suppressed = false
    }

    companion object {
        private const val TAG = "PR"

        private const val SESSION_GAP_MS = 60_000L

        // If a read fails to get every tyre of a bike, retry (while adverts continue) at most
        // this many times per session, no sooner than RETRY_COOLDOWN_MS apart.
        private const val MAX_ATTEMPTS_PER_SESSION = 3
        private const val RETRY_COOLDOWN_MS = 30_000L

        // Rate limit for the "still awake, ignoring" log line
        private const val ALIVE_LOG_INTERVAL_MS = 15_000L

        // In-memory session state per bike id (lives as long as the app process)
        private val sessions = mutableMapOf<String, WakeSession>()
        private var lastAliveLogTime = 0L
        // Last advert time per sensor address, to spot a different sensor waking mid-session
        private val lastHeard = mutableMapOf<String, Long>()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val errorCode = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (errorCode != 0) {
            Log.w(TAG, "Receiver: scan error code=$errorCode")
            return
        }

        val results: List<ScanResult>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(
                BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT,
                ScanResult::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)
        }

        if (results.isNullOrEmpty()) {
            Log.w(TAG, "Receiver: no scan results in intent")
            return
        }

        // Pairing in progress: don't start reads that would fight the pairing connection
        if (SensorDiscovery.busy) return

        // (bike, sensor address) for every paired sensor heard in this batch. Stale
        // registrations for sensors that have since been forgotten are ignored.
        val heard = results.mapNotNull { r ->
            val address = r.device.address
            BikeStore.bikeForAddress(context, address)?.let { it to address.uppercase(Locale.ROOT) }
        }
        val bikes = heard.map { it.first }.distinctBy { it.id }
        if (bikes.isEmpty()) {
            Log.d(TAG, "Receiver: adverts from sensors not paired to any bike, ignoring")
            return
        }

        val now = System.currentTimeMillis()
        val holding = TyreRepository.state.value.keepAlive
        val toRead = mutableListOf<String>()

        for (bike in bikes) {
            val session = sessions.getOrPut(bike.id) { WakeSession() }
            val addresses = heard.filter { it.first.id == bike.id }.map { it.second }

            // Sensors of this bike that were silent long enough to have been asleep
            val wokeSensors = addresses.filter { now - (lastHeard[it] ?: 0L) > SESSION_GAP_MS }
            addresses.forEach { lastHeard[it] = now }

            // A long silence before this advert means the sensors had gone to sleep: new wake-up
            val isNewSession = now - session.lastAdvertTime > SESSION_GAP_MS
            session.lastAdvertTime = now
            if (isNewSession) {
                session.startTime = now
                session.attempts = 0
                session.lastAttemptTime = 0L
                session.denied = false
                session.suppressed = NotificationGapPrefs.isWithinGap(context, bike.id, now)
                Log.d(
                    TAG,
                    "Receiver: new wake-up session for \"${bike.name}\"" +
                            if (session.suppressed) " (inside notification gap, will not read)" else ""
                )
            } else if (!session.suppressed) {
                // Same session, but a sensor that had been asleep (e.g. the other wheel) has
                // just woken and has no reading yet: allow another read for it.
                val unreadWoke = wokeSensors.any { address ->
                    val wheel = bike.pairedWheels.firstOrNull {
                        BikeStore.addressOf(context, it)?.uppercase(Locale.ROOT) == address
                    }
                    wheel != null &&
                            TyreRepository.state.value.reading(wheel).lastUpdatedMillis < session.startTime
                }
                if (unreadWoke) {
                    session.attempts = 0
                    session.lastAttemptTime = 0L
                    session.denied = false
                    Log.d(TAG, "Receiver: another sensor of \"${bike.name}\" woke, allowing a new read")
                }
            }

            // If Hold Connection is active, the service is already connected to everything
            if (holding) {
                logStillAwake(now, "hold connection active")
                continue
            }

            // Notified for this bike too recently: skip the whole wake-up
            if (session.suppressed) {
                logStillAwake(now, "\"${bike.name}\" notified within the minimum gap")
                continue
            }

            // A background start was refused this session; the background read / tap-to-read
            // fallback is already under way for this bike.
            if (session.denied) {
                logStillAwake(now, "background start already handled for \"${bike.name}\"")
                continue
            }

            // Every paired tyre of this bike already read during this session (by us or manually)
            if (isSessionComplete(context, bike.id, session.startTime)) {
                logStillAwake(now, "\"${bike.name}\" already read this session")
                continue
            }

            if (session.attempts >= MAX_ATTEMPTS_PER_SESSION) {
                logStillAwake(now, "max attempts reached for \"${bike.name}\"")
                continue
            }
            if (session.attempts > 0 && now - session.lastAttemptTime < RETRY_COOLDOWN_MS) {
                logStillAwake(now, "waiting before retrying \"${bike.name}\"")
                continue
            }

            session.attempts++
            session.lastAttemptTime = now
            Log.d(
                TAG,
                "Receiver: \"${bike.name}\" needs a read (attempt ${session.attempts} of $MAX_ATTEMPTS_PER_SESSION this session)"
            )
            toRead.add(bike.id)
        }

        if (toRead.isEmpty()) return
        startRead(context, toRead)
    }

    /** Starts a read of the given bikes: the service if allowed, otherwise BackgroundReader. */
    private fun startRead(context: Context, bikeIds: List<String>) {
        // A background read is already in flight (the service was refused earlier). Join it
        // rather than starting the service, which would cancel it.
        if (BackgroundReader.isActive) {
            Log.d(TAG, "Receiver: adding ${bikeIds.size} bike(s) to the running background read")
            markDenied(bikeIds)
            BackgroundReader.start(context, bikeIds)
            return
        }

        Log.d(TAG, "Receiver: starting burst read for ${bikeIds.size} bike(s)")
        val serviceIntent = Intent(context, TyreReaderService::class.java).apply {
            action = TyreReaderService.ACTION_BURST_READ
            putExtra(TyreReaderService.EXTRA_BIKE_IDS, bikeIds.toTypedArray())
        }
        try {
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            // Android 12+ blocks starting a foreground service from the background unless the
            // app is exempt. Read the tyres directly instead. If nothing at all can be read,
            // BackgroundReader falls back to a tap-to-read notification.
            Log.w(TAG, "Receiver: startForegroundService refused, reading in the background", e)
            markDenied(bikeIds)
            BackgroundReader.start(context, bikeIds)
        }
    }

    private fun markDenied(bikeIds: List<String>) {
        for (id in bikeIds) sessions[id]?.denied = true
    }

    /** True once every paired wheel of the bike has a reading newer than the start of its session. */
    private fun isSessionComplete(context: Context, bikeId: String, sessionStart: Long): Boolean {
        val bike = BikeStore.bike(context, bikeId) ?: return true
        val wheels = bike.pairedWheels
        if (wheels.isEmpty()) return true
        val state = TyreRepository.state.value
        return wheels.all { state.reading(it).lastUpdatedMillis >= sessionStart }
    }

    private fun logStillAwake(now: Long, reason: String) {
        if (now - lastAliveLogTime >= ALIVE_LOG_INTERVAL_MS) {
            lastAliveLogTime = now
            Log.d(TAG, "Receiver: sensors still awake, ignoring adverts ($reason)")
        }
    }
}