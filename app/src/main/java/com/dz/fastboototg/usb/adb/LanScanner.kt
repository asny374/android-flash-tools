package com.dz.fastboototg.usb.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** Scan actual LAN prefixes on Wi-Fi/Ethernet, bypassing cellular and VPN routes. */
object LanScanner {
    private data class Subnet(val network: Network, val address: Long, val prefix: Int)

    suspend fun scanTcpip(
        context: Context,
        port: Int = 5555,
        timeoutMs: Int = 600,
        onProgress: (checked: Int, total: Int) -> Unit = { _, _ -> }
    ): List<String> = withContext(Dispatchers.IO) {
        require(port in 1..65535) { "端口必须在 1–65535 之间" }
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val subnets = manager.allNetworks.flatMap { network ->
            val caps = manager.getNetworkCapabilities(network)
            if (caps == null || (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                emptyList()
            } else {
                manager.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull { link ->
                    val address = link.address as? Inet4Address ?: return@mapNotNull null
                    if (address.isLoopbackAddress || address.isLinkLocalAddress) return@mapNotNull null
                    Subnet(network, ipv4(address), link.prefixLength)
                }
            }
        }.distinct()
        if (subnets.isEmpty()) throw AdbException("没有可扫描的局域网", "请连接 Wi-Fi 或以太网后重试。")
        if (subnets.any { it.prefix < 16 }) throw AdbException(
            "局域网网段过大，无法自动扫描", "请手动输入目标机 IP 和 ADB 端口连接。"
        )
        val targets = subnets.flatMap { subnet ->
            val mask = (0xffffffffL shl (32 - subnet.prefix)) and 0xffffffffL
            val first = subnet.address and mask
            val last = first or (mask xor 0xffffffffL)
            val range = if (subnet.prefix <= 30) (first + 1) until last else first..last
            range.filter { it != subnet.address }.map { subnet.network to it }
        }.distinct()
        val checked = AtomicInteger()
        val found = coroutineScope {
            // A fixed worker count also bounds coroutine and socket allocation on large LANs.
            (0 until minOf(96, targets.size)).map { worker ->
                async {
                    val results = mutableListOf<String>()
                    var index = worker
                    while (index < targets.size) {
                        coroutineContext.ensureActive()
                        val (network, value) = targets[index]
                        val address = InetAddress.getByAddress(byteArrayOf(
                            (value shr 24).toByte(), (value shr 16).toByte(),
                            (value shr 8).toByte(), value.toByte()
                        ))
                        val open = try {
                            network.socketFactory.createSocket().use { socket ->
                                socket.connect(InetSocketAddress(address, port), timeoutMs)
                            }
                            true
                        } catch (_: java.io.IOException) {
                            false
                        }
                        if (open) results += address.hostAddress!!
                        onProgress(checked.incrementAndGet(), targets.size)
                        index += 96
                    }
                    results
                }
            }.awaitAll().flatten()
        }
        found.distinct().sorted()
    }

    private fun ipv4(address: Inet4Address): Long = address.address.fold(0L) { result, byte ->
        (result shl 8) or (byte.toLong() and 0xff)
    }
}
