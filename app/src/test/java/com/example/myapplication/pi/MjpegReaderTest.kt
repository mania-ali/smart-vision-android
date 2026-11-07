package com.example.myapplication.pi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream

class MjpegReaderTest {

    /** A fake JPEG: SOI, some body bytes (including a stuffed FF 00), EOI. */
    private fun jpeg(seed: Int, size: Int): ByteArray {
        val body = ByteArray(size) { ((it * 31 + seed) % 250).toByte() }   // never 0xFF
        body[size / 2] = 0xFF.toByte(); body[size / 2 + 1] = 0x00            // byte-stuffed FF
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + body + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    /** Returns at most [maxRead] bytes per read, so markers land across read boundaries. */
    private class Trickle(data: ByteArray, private val maxRead: Int) : InputStream() {
        private val inner = ByteArrayInputStream(data)
        override fun read() = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, minOf(len, maxRead))
    }

    private fun readAll(input: InputStream): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        try {
            MjpegReader(input).readFrames { buf, len -> frames += buf.copyOf(len) }
        } catch (e: EOFException) {
            // end of test stream
        }
        return frames
    }

    @Test
    fun splitsFramesAtEveryReadSize() {
        val sent = listOf(jpeg(1, 100), jpeg(2, 5000), jpeg(3, 300_000), jpeg(4, 7))
        val stream = sent.reduce { a, b -> a + b }
        for (maxRead in listOf(1, 2, 3, 7, 1000, 65536)) {
            val got = readAll(Trickle(stream, maxRead))
            assertEquals("frame count, maxRead=$maxRead", sent.size, got.size)
            sent.zip(got).forEach { (s, g) -> assertArrayEquals("maxRead=$maxRead", s, g) }
        }
    }

    @Test
    fun skipsGarbageAndCutOffFrames() {
        val good = jpeg(5, 200)
        val cutOff = jpeg(6, 200).copyOf(150)   // starts but never ends
        val stream = byteArrayOf(1, 2, 0xFF.toByte(), 9) + cutOff + good + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val got = readAll(Trickle(stream, 13))
        assertEquals(1, got.size)
        assertArrayEquals(good, got[0])
    }
}
