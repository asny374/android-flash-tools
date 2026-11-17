package com.dz.fastboototg.usb.adb
import org.junit.Test
import org.junit.Assert.*
class PreviewRegressionTest {
    @Test fun full4096PayloadDoesNotWaitFor16384() {
        assertEquals(4096, AdbReadSizing.requestSize(4096))
        assertEquals(1024, AdbReadSizing.requestSize(24))
        assertEquals(16384, AdbReadSizing.requestSize(100000))
        assertEquals(5120, AdbReadSizing.requestSize(4097))
    }
    @Test fun ignoresDiagnosticPrefixBeforePng() {
        val png = byteArrayOf(-119,80,78,71,13,10,26,10,1,2)
        assertArrayEquals(png, ScreenPng.extract("warning\n".toByteArray() + png))
        assertArrayEquals(png, ScreenPng.extract(png))
    }
    @Test(expected = AdbException::class) fun rejectsEmptyImage() { ScreenPng.extract(byteArrayOf()) }
}
