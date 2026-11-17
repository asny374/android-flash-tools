package com.dz.fastboototg.usb.adb
import org.junit.Test
import org.junit.Assert.*
class NtpHostTest {
    @Test fun acceptsHostAndIpv4() { assertTrue(NtpHost.isValid("ntp.aliyun.com")); assertTrue(NtpHost.isValid("192.168.1.1")) }
    @Test fun rejectsShellInjectionAndUrls() {
        listOf("a;reboot", "a'", "a b", "https://time.android.com", "a\nreboot", "", "-a", "a..b").forEach { assertFalse(it, NtpHost.isValid(it)) }
    }
}
