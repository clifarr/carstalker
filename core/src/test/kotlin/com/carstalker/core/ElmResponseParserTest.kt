package com.carstalker.core

import com.carstalker.core.elm.ElmResponseParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmResponseParserTest {

    @Test
    fun `parses plain payload`() {
        val response = ElmResponseParser.parse("41 0C 1A F8\r\r>")
        assertTrue(response.isData)
        assertArrayEquals(intArrayOf(0x41, 0x0C, 0x1A, 0xF8), response.payloads.single())
    }

    @Test
    fun `strips 11-bit CAN header and length byte`() {
        val response = ElmResponseParser.parse("7E8 04 41 0C 1A F8\r\r>")
        assertArrayEquals(intArrayOf(0x41, 0x0C, 0x1A, 0xF8), response.payloads.single())
    }

    @Test
    fun `strips 29-bit CAN header`() {
        val response = ElmResponseParser.parse("18DAF110 04 41 0D 32 00\r\r>")
        assertArrayEquals(intArrayOf(0x41, 0x0D, 0x32, 0x00), response.payloads.single())
    }

    @Test
    fun `strips a 29-bit header printed as four separate bytes`() {
        // Real output from an ELM327 clone on a 29-bit CAN bus.
        val response = ElmResponseParser.parse("18 DA F1 10 04 41 0C 0C 84\r\r>")
        assertArrayEquals(intArrayOf(0x41, 0x0C, 0x0C, 0x84), response.messages.single().payload)
        assertEquals(0x18DAF110, response.messages.single().source)
    }

    @Test
    fun `keeps short payloads after a split 29-bit header`() {
        val response = ElmResponseParser.parse("18 DA F1 10 03 41 0D 00\r\r>")
        assertArrayEquals(intArrayOf(0x41, 0x0D, 0x00), response.messages.single().payload)
    }

    @Test
    fun `strips a three-byte ISO 9141 header`() {
        val response = ElmResponseParser.parse("48 6B 10 41 0C 1A F8\r\r>")
        assertArrayEquals(intArrayOf(0x41, 0x0C, 0x1A, 0xF8), response.messages.single().payload)
    }

    @Test
    fun `strips a three-byte J1850 header`() {
        val response = ElmResponseParser.parse("41 6B 10 41 0D 32\r\r>")
        assertArrayEquals(intArrayOf(0x41, 0x0D, 0x32), response.messages.single().payload)
    }

    @Test
    fun `reassembles ISO-TP multi-frame VIN across first and consecutive frames`() {
        // Shape of a real 29-bit CAN VIN read: 0x14 bytes of payload across three frames.
        // 0x14 = 20 bytes: 49 02 01 followed by a 17-character VIN.
        val raw = listOf(
            "18 DA F1 10 10 14 49 02 01 31 48 47 43",
            "18 DA F1 10 21 4D 38 32 36 33 33 41",
            "18 DA F1 10 22 30 30 34 33 35 32 00",
        ).joinToString("\r") + "\r\r>"

        val response = ElmResponseParser.parse(raw)
        val message = response.messages.single()

        assertEquals(0x18DAF110, message.source)
        assertEquals(0x49, message.payload[0])
        assertEquals(
            "1HGCM82633A004352",
            com.carstalker.core.vehicle.Mode09Decoder.decodeVin(listOf(message.payload)),
        )
    }

    @Test
    fun `reassembles ELM multi-frame output`() {
        val raw = "0: 49 02 01 00 00 00 31\r1: 47 31 4A 43 35 34 34 34\r2: 52 31 32 33 34 35 36 37\r\r>"
        val response = ElmResponseParser.parse(raw)
        assertEquals(1, response.messages.size)
        val payload = response.payloads.first()
        assertArrayEquals(
            intArrayOf(0x49, 0x02, 0x01, 0x00, 0x00, 0x00, 0x31, 0x47, 0x31, 0x4A, 0x43, 0x35, 0x34, 0x34, 0x34, 0x52, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37),
            payload,
        )
    }

    @Test
    fun `ignores searching banner and keeps data`() {
        val response = ElmResponseParser.parse("SEARCHING...\r41 0D 32\r\r>")
        assertTrue(response.isData)
        assertArrayEquals(intArrayOf(0x41, 0x0D, 0x32), response.payloads.single())
    }

    @Test
    fun `handles multiple ecu responses`() {
        val response = ElmResponseParser.parse("41 0C 1A F8\r41 0C 1A F0\r\r>")
        assertEquals(2, response.messages.size)
    }

    @Test
    fun `recognises no data and errors`() {
        assertEquals(com.carstalker.core.elm.ElmResponse.Status.NO_DATA, ElmResponseParser.parse("NO DATA\r\r>").status)
        assertEquals(com.carstalker.core.elm.ElmResponse.Status.ERROR, ElmResponseParser.parse("CAN ERROR\r\r>").status)
        assertEquals(com.carstalker.core.elm.ElmResponse.Status.OK, ElmResponseParser.parse("OK\r\r>").status)
    }

    @Test
    fun `parses compact output without spaces`() {
        val response = ElmResponseParser.parse("410C1AF8\r\r>")
        assertArrayEquals(intArrayOf(0x41, 0x0C, 0x1A, 0xF8), response.payloads.single())
    }
}
