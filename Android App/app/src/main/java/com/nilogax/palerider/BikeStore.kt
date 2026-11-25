package com.nilogax.palerider.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.Locale

data class ReadingHistoryItem(
    val timestampMillis: Long,
    val frontPsi: Float?,
    val rearPsi: Float?
)

data class Bike(
    val id: String,
    val name: String,
    val frontMac: String? = null,
    val rearMac: String? = null,
    val frontMinPsi: Float? = null,
    val frontMaxPsi: Float? = null,
    val rearMinPsi: Float? = null,
    val rearMaxPsi: Float? = null,
    val history: List<ReadingHistoryItem> = emptyList()
) {
    val pairedWheels: List<WheelId>
        get() = buildList {
            if (frontMac != null) add(WheelId(id, WheelPosition.FRONT))
            if (rearMac != null) add(WheelId(id, WheelPosition.REAR))
        }

    fun macFor(position: WheelPosition): String? = when (position) {
        WheelPosition.FRONT -> frontMac
        WheelPosition.REAR -> rearMac
    }

    fun minPsiFor(position: WheelPosition): Float? = when (position) {
        WheelPosition.FRONT -> frontMinPsi
        WheelPosition.REAR -> rearMinPsi
    }

    fun maxPsiFor(position: WheelPosition): Float? = when (position) {
        WheelPosition.FRONT -> frontMaxPsi
        WheelPosition.REAR -> rearMaxPsi
    }

    fun statusFor(position: WheelPosition, psi: Float?): PressureStatus {
        if (psi == null) return PressureStatus.NORMAL
        val min = minPsiFor(position)
        val max = maxPsiFor(position)
        return when {
            min != null && psi < min -> PressureStatus.LOW
            max != null && psi > max -> PressureStatus.HIGH
            else -> PressureStatus.NORMAL
        }
    }
}

object BikeStore {
    private const val PREFS_NAME = "palerider_bikes"
    private const val KEY_BIKES = "bikes_json"
    const val MAX_NAME_LENGTH = 30

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadBikes(context: Context): List<Bike> {
        val jsonStr = prefs(context).getString(KEY_BIKES, null) ?: return defaultBikes()
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<Bike>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val frontMac = obj.optString("frontMac", "").takeIf { it.isNotEmpty() }
                val rearMac = obj.optString("rearMac", "").takeIf { it.isNotEmpty() }
                val fMin = obj.optDouble("frontMinPsi", Double.NaN).toFloat().takeIf { !it.isNaN() }
                val fMax = obj.optDouble("frontMaxPsi", Double.NaN).toFloat().takeIf { !it.isNaN() }
                val rMin = obj.optDouble("rearMinPsi", Double.NaN).toFloat().takeIf { !it.isNaN() }
                val rMax = obj.optDouble("rearMaxPsi", Double.NaN).toFloat().takeIf { !it.isNaN() }

                val historyArr = obj.optJSONArray("history") ?: JSONArray()
                val historyList = mutableListOf<ReadingHistoryItem>()
                for (h in 0 until historyArr.length()) {
                    val hObj = historyArr.getJSONObject(h)
                    val ts = hObj.getLong("ts")
                    val fPsi = hObj.optDouble("fPsi", Double.NaN).toFloat().takeIf { !it.isNaN() }
                    val rPsi = hObj.optDouble("rPsi", Double.NaN).toFloat().takeIf { !it.isNaN() }
                    historyList.add(ReadingHistoryItem(ts, fPsi, rPsi))
                }

                list.add(Bike(id, name, frontMac, rearMac, fMin, fMax, rMin, rMax, historyList))
            }
            list
        } catch (e: Exception) {
            defaultBikes()
        }
    }

    private fun saveBikes(context: Context, bikes: List<Bike>) {
        val arr = JSONArray()
        for (bike in bikes) {
            val obj = JSONObject().apply {
                put("id", bike.id)
                put("name", bike.name)
                put("frontMac", bike.frontMac ?: "")
                put("rearMac", bike.rearMac ?: "")
                bike.frontMinPsi?.let { put("frontMinPsi", it.toDouble()) }
                bike.frontMaxPsi?.let { put("frontMaxPsi", it.toDouble()) }
                bike.rearMinPsi?.let { put("rearMinPsi", it.toDouble()) }
                bike.rearMaxPsi?.let { put("rearMaxPsi", it.toDouble()) }

                val historyArr = JSONArray()
                for (item in bike.history) {
                    val hObj = JSONObject().apply {
                        put("ts", item.timestampMillis)
                        item.frontPsi?.let { put("fPsi", it.toDouble()) }
                        item.rearPsi?.let { put("rPsi", it.toDouble()) }
                    }
                    historyArr.put(hObj)
                }
                put("history", historyArr)
            }
            arr.put(obj)
        }
        prefs(context).edit().putString(KEY_BIKES, arr.toString()).apply()
        syncToRepository(context)
    }

    private fun defaultBikes(): List<Bike> = listOf(
        Bike(id = UUID.randomUUID().toString(), name = "My Bike")
    )

    fun syncToRepository(context: Context) {
        TyreRepository.setBikes(loadBikes(context))
    }

    fun bike(context: Context, bikeId: String): Bike? =
        loadBikes(context).firstOrNull { it.id == bikeId }

    fun bikeForAddress(context: Context, mac: String): Bike? {
        val clean = mac.uppercase(Locale.ROOT)
        return loadBikes(context).firstOrNull { bike ->
            bike.frontMac?.uppercase(Locale.ROOT) == clean || bike.rearMac?.uppercase(Locale.ROOT) == clean
        }
    }

    fun addBike(context: Context, name: String): Bike {
        val bikes = loadBikes(context).toMutableList()
        val newBike = Bike(id = UUID.randomUUID().toString(), name = name.trim().take(MAX_NAME_LENGTH))
        bikes.add(newBike)
        saveBikes(context, bikes)
        return newBike
    }

    fun renameBike(context: Context, bikeId: String, newName: String) {
        val bikes = loadBikes(context).toMutableList()
        val index = bikes.indexOfFirst { it.id == bikeId }
        if (index != -1) {
            bikes[index] = bikes[index].copy(name = newName.trim().take(MAX_NAME_LENGTH))
            saveBikes(context, bikes)
        }
    }

    fun deleteBike(context: Context, bikeId: String) {
        val bikes = loadBikes(context).toMutableList()
        bikes.removeAll { it.id == bikeId }
        saveBikes(context, bikes)
    }

    fun setTargetRanges(context: Context, bikeId: String, fMin: Float?, fMax: Float?, rMin: Float?, rMax: Float?) {
        val bikes = loadBikes(context).toMutableList()
        val index = bikes.indexOfFirst { it.id == bikeId }
        if (index != -1) {
            bikes[index] = bikes[index].copy(
                frontMinPsi = fMin,
                frontMaxPsi = fMax,
                rearMinPsi = rMin,
                rearMaxPsi = rMax
            )
            saveBikes(context, bikes)
        }
    }

    fun recordHistory(context: Context, bikeId: String, frontPsi: Float?, rearPsi: Float?) {
        if (frontPsi == null && rearPsi == null) return
        val bikes = loadBikes(context).toMutableList()
        val index = bikes.indexOfFirst { it.id == bikeId }
        if (index == -1) return

        val bike = bikes[index]
        val newItem = ReadingHistoryItem(System.currentTimeMillis(), frontPsi, rearPsi)
        val updatedHistory = listOf(newItem) + bike.history.take(29)

        bikes[index] = bike.copy(history = updatedHistory)
        saveBikes(context, bikes)
    }

    fun setSensor(context: Context, wheel: WheelId, mac: String) {
        val bikes = loadBikes(context).toMutableList()
        for (i in bikes.indices) {
            val b = bikes[i]
            val fMac = if (wheel.position == WheelPosition.FRONT && b.id == wheel.bikeId) mac else if (b.frontMac.equals(mac, true)) null else b.frontMac
            val rMac = if (wheel.position == WheelPosition.REAR && b.id == wheel.bikeId) mac else if (b.rearMac.equals(mac, true)) null else b.rearMac
            bikes[i] = b.copy(frontMac = fMac, rearMac = rMac)
        }
        saveBikes(context, bikes)
    }

    fun forgetSensor(context: Context, wheel: WheelId) {
        val bikes = loadBikes(context).toMutableList()
        val index = bikes.indexOfFirst { it.id == wheel.bikeId }
        if (index != -1) {
            val b = bikes[index]
            bikes[index] = when (wheel.position) {
                WheelPosition.FRONT -> b.copy(frontMac = null)
                WheelPosition.REAR -> b.copy(rearMac = null)
            }
            saveBikes(context, bikes)
        }
    }

    fun pairedAddresses(context: Context): Set<String> {
        val set = mutableSetOf<String>()
        for (bike in loadBikes(context)) {
            bike.frontMac?.let { set.add(it) }
            bike.rearMac?.let { set.add(it) }
        }
        return set
    }

    fun pairedWheels(context: Context, filterBikeIds: Set<String>? = null): List<WheelId> {
        val list = mutableListOf<WheelId>()
        for (bike in loadBikes(context)) {
            if (filterBikeIds != null && bike.id !in filterBikeIds) continue
            if (bike.frontMac != null) list.add(WheelId(bike.id, WheelPosition.FRONT))
            if (bike.rearMac != null) list.add(WheelId(bike.id, WheelPosition.REAR))
        }
        return list
    }

    fun addressOf(context: Context, wheel: WheelId): String? {
        val bike = bike(context, wheel.bikeId) ?: return null
        return bike.macFor(wheel.position)
    }
}