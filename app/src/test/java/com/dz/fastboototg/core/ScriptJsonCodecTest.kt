package com.dz.fastboototg.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 脚本 JSON 编解码测试 */
class ScriptJsonCodecTest {

    @Test
    fun `编解码往返`() {
        val scripts = listOf(
            FastbootScript("常规流程", listOf("getvar:unlocked", "reboot-bootloader")),
            FastbootScript("带引号的名字", listOf("flash:boot"))
        )
        val decoded = ScriptJsonCodec.decode(ScriptJsonCodec.encode(scripts))
        assertEquals(scripts, decoded)
    }

    @Test
    fun `空列表往返`() {
        assertEquals(emptyList<FastbootScript>(), ScriptJsonCodec.decode(ScriptJsonCodec.encode(emptyList())))
    }

    @Test
    fun `坏输入不抛异常返回空`() {
        assertTrue(ScriptJsonCodec.decode("not json").isEmpty())
        assertTrue(ScriptJsonCodec.decode("{\"name\":\"x\"}").isEmpty())
        assertTrue(ScriptJsonCodec.decode("[]").isEmpty())
    }

    @Test
    fun `命令去空白行且空命令列表整体丢弃`() {
        val raw = "[{\"name\":\"a\",\"commands\":[\"  \",\"getvar:unlocked\"]}," +
            "{\"name\":\"b\",\"commands\":[\" \"]}]"
        val decoded = ScriptJsonCodec.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("a", decoded[0].name)
        assertEquals(listOf("getvar:unlocked"), decoded[0].commands)
    }

    @Test
    fun `同名脚本覆盖语义由上层保证`() {
        // 编解码层不合并同名，直接保留顺序
        val scripts = listOf(
            FastbootScript("same", listOf("a")),
            FastbootScript("same", listOf("b"))
        )
        assertEquals(2, ScriptJsonCodec.decode(ScriptJsonCodec.encode(scripts)).size)
    }
}
