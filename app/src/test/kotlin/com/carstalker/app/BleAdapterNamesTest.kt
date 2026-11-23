package com.carstalker.app

import com.carstalker.app.transport.ble.BleAdapterNames
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleAdapterNamesTest {

    @Test
    fun `recognises common adapter names`() {
        assertTrue(BleAdapterNames.looksLikeAdapter("OBDII"))
        assertTrue(BleAdapterNames.looksLikeAdapter("Vgate iCar Pro"))
        assertTrue(BleAdapterNames.looksLikeAdapter("ELM327"))
        assertTrue(BleAdapterNames.looksLikeAdapter("VEEPEAK"))
        assertTrue(BleAdapterNames.looksLikeAdapter("KONNWEI KW902"))
    }

    @Test
    fun `ignores unrelated bluetooth devices`() {
        assertFalse(BleAdapterNames.looksLikeAdapter("Sony WH-1000XM5"))
        assertFalse(BleAdapterNames.looksLikeAdapter("Tesla Model 3"))
        assertFalse(BleAdapterNames.looksLikeAdapter("Neighbour's car"))
        assertFalse(BleAdapterNames.looksLikeAdapter(""))
    }

    @Test
    fun `does not label watches or audio gear as adapters`() {
        assertFalse(BleAdapterNames.looksLikeAdapter("Xiaomi Watch S4 C4D2"))
        assertFalse(BleAdapterNames.looksLikeAdapter("Clifford's AirPods"))
        assertFalse(BleAdapterNames.looksLikeAdapter("Honda HFT"))
        assertFalse(BleAdapterNames.looksLikeAdapter("My Fiat SJ099777"))
    }

    @Test
    fun `filters BLE by advertised serial service`() {
        val serial = java.util.UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val unrelated = java.util.UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
        assertTrue(BleAdapterNames.isCompatibleBle("", listOf(serial)))
        assertTrue(BleAdapterNames.isCompatibleBle("BT-05", null))
        assertFalse(BleAdapterNames.isCompatibleBle("", listOf(unrelated)))
        assertFalse(BleAdapterNames.isCompatibleBle("Xiaomi Watch", listOf(serial)))
    }

    @Test
    fun `recognises tool brand adapters`() {
        assertTrue(BleAdapterNames.looksLikeAdapter("Autel MaxiAP"))
        assertTrue(BleAdapterNames.looksLikeAdapter("Thinkdiag"))
        assertTrue(BleAdapterNames.looksLikeAdapter("CARLY"))
    }
}
