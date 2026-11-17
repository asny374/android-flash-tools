package com.dz.fastboototg.usb

import com.dz.fastboototg.core.AppException

/** fastboot 协议的五种响应帧 */
sealed interface FastbootResponse {
    data class Okay(val payload: String) : FastbootResponse
    data class Fail(val reason: String) : FastbootResponse
    data class Info(val text: String) : FastbootResponse
    data class Data(val maxChunk: Long) : FastbootResponse
    data class Unknown(val raw: String) : FastbootResponse
    data object Timeout : FastbootResponse
}

fun FastbootResponse.describe(): String = when (this) {
    is FastbootResponse.Okay -> "OKAY $payload".trim()
    is FastbootResponse.Fail -> "FAIL $reason"
    is FastbootResponse.Info -> "INFO $text"
    is FastbootResponse.Data -> "DATA(单次上限 $maxChunk)"
    is FastbootResponse.Unknown -> "未知响应 $raw"
    FastbootResponse.Timeout -> "超时无响应"
}

/** fastboot 流程中的可预期失败，hint 面向用户给出下一步建议 */
class FastbootException(
    message: String,
    hint: String = "",
    /**
     * true = 设备明确拒绝（FAIL 响应），传输链路是好的；
     * false = 传输层失败（超时 / 通道异常）。会话层只对后者重试。
     */
    val deviceRejected: Boolean = false
) : AppException(message, hint)
