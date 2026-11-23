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
