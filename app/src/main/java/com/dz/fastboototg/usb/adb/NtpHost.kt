package com.dz.fastboototg.usb.adb

internal object NtpHost {
    fun isValid(value: String): Boolean {
        if (value.length !in 1..253) return false
        return value.split('.').all { label ->
            label.length in 1..63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        }
    }
}
