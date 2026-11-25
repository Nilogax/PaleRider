package com.nilogax.palerider.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

object OutriderConfig {
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val PSI_PER_BIT = 0.1f
    const val ATMOSPHERIC_OFFSET_PSI = 14.2f
    const val PSI_TO_BAR = 0.0689476f
}

data class WheelId(
    val bikeId: String,
    val position: WheelPosition
)

enum class WheelPosition { FRONT, REAR }

enum class PressureStatus { NORMAL, LOW, HIGH }

data class WheelReading(
    val psi: Float? = null,
    val isConnected: Boolean = false,
    val lastUpdatedMillis: Long = 0L
)

fun PressureUnit.toPsi(valueInUnit: Float): Float = when (this) {
    PressureUnit.PSI -> valueInUnit
    PressureUnit.BAR -> valueInUnit / OutriderConfig.PSI_TO_BAR
}

fun PressureUnit.formatRange(minPsi: Float?, maxPsi: Float?): String? {
    if (minPsi == null && maxPsi == null) return null
    val minStr = minPsi?.let { format(it) }
    val maxStr = maxPsi?.let { format(it) }
    return when {
        minStr != null && maxStr != null -> "Target $minStr–$maxStr$label"
        minStr != null -> "Min $minStr$label"
        else -> "Max $maxStr$label"
    }
}

data class PaleRiderUiState(
    val bikes: List<Bike> = emptyList(),
    val readings: Map<WheelId, WheelReading> = emptyMap(),
    val keepAlive: Boolean = false,
    val keepAliveExpirationMillis: Long = 0L,
    val backgroundWatchActive: Boolean = false,
    val unit: PressureUnit = PressureUnit.PSI,
    val statusMessage: String = "Idle — spin a wheel or tap Read All"
) {
    val reading: (WheelId) -> WheelReading = { readings[it] ?: WheelReading() }
}

object TyreRepository {
    private val _state = MutableStateFlow(PaleRiderUiState())
    val state: StateFlow<PaleRiderUiState> = _state.asStateFlow()

    fun updatePressure(wheel: WheelId, psi: Float) {
        _state.update { current ->
            val updated = WheelReading(
                psi = psi,
                isConnected = true,
                lastUpdatedMillis = System.currentTimeMillis()
            )
            current.copy(readings = current.readings + (wheel to updated))
        }
    }

    fun setConnectionState(wheel: WheelId, connected: Boolean) {
        _state.update { current ->
            val existing = current.readings[wheel]
            if (existing == null && !connected) {
                current
            } else {
                val updated = (existing ?: WheelReading()).copy(isConnected = connected)
                current.copy(readings = current.readings + (wheel to updated))
            }
        }
    }

    private fun currentReadings(readings: Map<WheelId, WheelReading>, wheel: WheelId) =
        readings - wheel

    fun setBikes(bikes: List<Bike>) {
        _state.update { it.copy(bikes = bikes) }
    }

    fun setUnit(unit: PressureUnit) {
        _state.update { it.copy(unit = unit) }
    }

    fun setKeepAlive(active: Boolean) {
        _state.update { it.copy(keepAlive = active) }
    }

    fun setKeepAliveExpirationMillis(millis: Long) {
        _state.update { it.copy(keepAliveExpirationMillis = millis) }
    }

    fun setBackgroundWatch(active: Boolean) {
        _state.update { it.copy(backgroundWatchActive = active) }
    }

    fun setStatus(message: String) {
        _state.update { it.copy(statusMessage = message) }
    }
}