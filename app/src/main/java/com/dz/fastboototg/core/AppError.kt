package com.dz.fastboototg.core

import java.io.IOException

/**
 * 带排查建议的业务异常基类。
 *
 * USB 传输、fastboot 协议、ADB 协议层的所有可预期失败都应抛这个体系下的子类，
 * 携带两段信息：`message` 给用户看结果，`hint` 给用户看下一步怎么办。
 */
open class AppException(
    message: String,
    val hint: String = ""
) : IOException(message)

/**
 * 统一错误呈现模型。
 *
 * 之前错误标题和提示分散在各处的字符串拼接里，
 * 现在「业务异常 → 展示模型」的转换集中到一处，UI 层只认 AppError。
 */
data class AppError(
    val title: String,
    val hint: String? = null
) {
    companion object {

        /** 把任意异常转成可展示的错误。业务异常取 hint，其他异常只给标题。 */
        fun from(t: Throwable, prefix: String? = null): AppError {
            val message = t.message ?: t.javaClass.simpleName
            val title = if (prefix.isNullOrBlank()) message else "$prefix$message"
            val hint = (t as? AppException)?.hint?.takeIf { it.isNotBlank() }
            return AppError(title, hint)
        }
    }
}
