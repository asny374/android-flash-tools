package com.dz.fastboototg.core

import android.content.Context

/** 一键脚本：名字 + 顺序执行的 fastboot 命令列表 */
data class FastbootScript(
    val name: String,
    val commands: List<String>
)

/**
 * 脚本持久化（JSON 存 SharedPreferences）。
 * 编解码逻辑拆到 [ScriptJsonCodec]（纯 JVM 手写实现，不依赖 org.json），可单元测试。
 */
class ScriptStore(context: Context) {

    private val sp = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    fun load(): List<FastbootScript> = ScriptJsonCodec.decode(
        sp.getString(KEY_SCRIPTS, "[]") ?: "[]"
    )

    fun save(scripts: List<FastbootScript>) {
        sp.edit().putString(KEY_SCRIPTS, ScriptJsonCodec.encode(scripts)).apply()
    }

    companion object {
        private const val KEY_SCRIPTS = "fastboot_scripts"
    }
}

/**
 * 脚本 ⇄ JSON 纯编解码。
 *
 * 之所以手写：org.json 是 Android 类，JVM 单元测试里只有空壳实现。
 * 只需要支持自己生成的结构：`[{"name":"…","commands":["…",…]},…]`。
 */
object ScriptJsonCodec {

    fun encode(scripts: List<FastbootScript>): String {
        val sb = StringBuilder("[")
        scripts.forEachIndexed { i, script ->
            if (i > 0) sb.append(',')
            sb.append("{\"name\":\"").append(escape(script.name)).append("\",\"commands\":[")
            script.commands.forEachIndexed { j, command ->
                if (j > 0) sb.append(',')
                sb.append('"').append(escape(command)).append('"')
            }
            sb.append("]}")
        }
        sb.append(']')
        return sb.toString()
    }

    fun decode(raw: String): List<FastbootScript> {
        return runCatching { parseArray(raw) }.getOrDefault(emptyList())
    }

    private fun escape(s: String): String = buildString {
        for (ch in s) when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }

    private fun parseArray(raw: String): List<FastbootScript> {
        val p = Parser(raw)
        p.expect('[')
        val scripts = mutableListOf<FastbootScript>()
        if (p.peek() == ']') { p.next(); return scripts }
        while (true) {
            p.expect('{')
            var name = ""
            val commands = mutableListOf<String>()
            while (true) {
                val key = p.readString()
                p.expect(':')
                when (key) {
                    "name" -> name = p.readString().trim()
                    "commands" -> {
                        p.expect('[')
                        if (p.peek() != ']') {
                            while (true) {
                                p.readString().trim().takeIf { it.isNotBlank() }
                                    ?.let { commands += it }
                                if (p.peek() == ',') p.next() else break
                            }
                        }
                        p.expect(']')
                    }
                    else -> p.skipValue()
                }
                if (p.peek() == ',') p.next() else break
            }
            p.expect('}')
            if (name.isNotBlank() && commands.isNotEmpty()) {
                scripts += FastbootScript(name, commands)
            }
            if (p.peek() == ',') p.next() else break
        }
        p.expect(']')
        return scripts
    }

    private class Parser(source: String) {
        private val src = source.trim()
        private var i = 0

        fun peek(): Char {
            skipWs()
            return src.getOrNull(i) ?: throw IllegalArgumentException("JSON 提前结束")
        }

        fun next(): Char {
            skipWs()
            return src[i++]
        }

        fun expect(c: Char) {
            skipWs()
            if (src.getOrNull(i) != c) throw IllegalArgumentException("期望 '$c'，实际 '${src.getOrNull(i)}'")
            i++
        }

        fun readString(): String {
            skipWs()
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= src.length) throw IllegalArgumentException("字符串未闭合")
                val ch = src[i++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= src.length) throw IllegalArgumentException("转义符悬空")
                        val e = src[i++]
                        sb.append(
                            when (e) {
                                '\\' -> '\\'
                                '"' -> '"'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'b' -> '\b'
                                'f' -> '\u000C'
                                '/' -> '/'
                                else -> e
                            }
                        )
                    }
                    else -> sb.append(ch)
                }
            }
        }

        fun skipValue() {
            skipWs()
            if (src.getOrNull(i) == '"') {
                readString()
                return
            }
            // 数字 / 布尔 / null：读到结构边界
            while (i < src.length) {
                when (val ch = src[i]) {
                    ',', '}', ']' -> return
                    '"', '{', '[' -> throw IllegalArgumentException("skipValue 不支持嵌套结构")
                    else -> { i++; ch.isWhitespace() }
                }
            }
        }

        private fun skipWs() {
            while (i < src.length && src[i].isWhitespace()) i++
        }
    }
}
