package com.dz.fastboototg.core.io

import android.content.Context
import android.net.Uri
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

/**
 * 可随机读取的数据源。
 *
 * 之所以不直接把文件读成 ByteArray：super 分区镜像动辄 4~8GB，
 * 整体加载必然 OOM。刷写是「按块 seek + read」的流式过程，抽象成这个接口即可。
 * 放在 core/io：fastboot 刷写与 ADB 文件推送共用同一种来源抽象。
 */
interface RandomAccessSource : Closeable {
    val length: Long
    fun readAt(position: Long, buffer: ByteArray, offset: Int, count: Int)
}

fun File.asSource(): RandomAccessSource = FileSource(RandomAccessFile(this, "r"))

/** 内存字节数组的数据源（vbmeta 补丁等小镜像直接从内存刷写） */
fun ByteArray.asSource(): RandomAccessSource = ByteArraySource(this)

class ByteArraySource(private val data: ByteArray) : RandomAccessSource {
    override val length: Long get() = data.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, count: Int) {
        System.arraycopy(data, position.toInt(), buffer, offset, count)
    }

    override fun close() {}
}

/**
 * 通过 SAF 选中的文件。用文件描述符 + FileChannel 随机读，
 * 避免先复制一份到 cacheDir（4GB 文件复制一次就是几分钟）。
 */
class UriSource(context: Context, uri: Uri) : RandomAccessSource {

    private val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
        ?: error("无法打开所选文件")
    private val channel = FileInputStream(descriptor.fileDescriptor).channel

    override val length: Long = descriptor.statSize.also {
        if (it <= 0L) error("无法获取文件大小（statSize=$it），该来源不支持随机读取")
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, count: Int) {
        val wrapper = java.nio.ByteBuffer.wrap(buffer, offset, count)
        var read = 0
        while (read < count) {
            val n = channel.read(wrapper, position + read)
            if (n <= 0) break
            read += n
        }
        if (read < count) error("读取镜像不足：期望 $count 实得 $read（文件被截断？）")
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { descriptor.close() }
    }
}

private class FileSource(private val raf: RandomAccessFile) : RandomAccessSource {
    override val length: Long get() = raf.length()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, count: Int) {
        raf.seek(position)
        raf.readFully(buffer, offset, count)
    }

    override fun close() {
        runCatching { raf.close() }
    }
}
