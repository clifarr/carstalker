package com.carstalker.app.data

import com.carstalker.core.guided.Aspiration
import com.carstalker.core.guided.BaselineSnapshot
import com.carstalker.core.guided.FuelType
import com.carstalker.core.guided.VehicleProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Serializable form of [BaselineSnapshot] for the DataStore-backed settings file. */
@Serializable
data class StoredBaseline(
    val vehicleKey: String = "",
    val fuelType: String = "UNKNOWN",
    val aspiration: String = "UNKNOWN",
    val bankCount: Int = 1,
    val createdAt: Long = 0L,
    val definitionVersion: String = "1.0.0",
    val conditions: Map<String, Double> = emptyMap(),
    val metrics: Map<String, Double> = emptyMap(),
    val notes: List<String> = emptyList(),
)

object BaselineStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(snapshot: BaselineSnapshot): String = json.encodeToString(snapshot.toStored())

    fun decode(text: String?): BaselineSnapshot? = text
        ?.let { runCatching { json.decodeFromString<StoredBaseline>(it) }.getOrNull() }
        ?.toSnapshot()

    private fun BaselineSnapshot.toStored(): StoredBaseline = StoredBaseline(
        vehicleKey = this.vehicleKey,
        fuelType = profile.fuelType.name,
        aspiration = profile.aspiration.name,
        bankCount = profile.bankCount,
        createdAt = this.createdAt,
        definitionVersion = this.definitionVersion,
        conditions = conditions,
        metrics = metrics,
        notes = notes,
    )

    private fun StoredBaseline.toSnapshot(): BaselineSnapshot = BaselineSnapshot(
        vehicleKey = vehicleKey,
        profile = VehicleProfile(
            fuelType = runCatching { FuelType.valueOf(fuelType) }.getOrDefault(FuelType.UNKNOWN),
            aspiration = runCatching { Aspiration.valueOf(aspiration) }.getOrDefault(Aspiration.UNKNOWN),
            bankCount = bankCount,
        ),
        createdAt = createdAt,
        definitionVersion = definitionVersion,
        conditions = conditions,
        metrics = metrics,
        notes = notes,
    )
}
