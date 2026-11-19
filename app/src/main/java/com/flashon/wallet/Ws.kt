package com.flashon.wallet

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A minimal WebSocket client (text messages, ping/pong, close), only what the quiet connection to
 * w.flashoneg.com needs, so the app stays without libraries. Plain Java: it's tested on the PC too.
 * TLS with the system's certificates and a hostname check, like HttpsURLConnection.
 */
class Ws private constructor(val socket: Socket, private val inp: InputStream, private val out: OutputStream) {
    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private val rnd = SecureRandom()

        /** Connects and upgrades. [readTimeoutMs]: how long read() waits for anything before it gives up. */
        fun open(url: String, headers: Map<String, String>, readTimeoutMs: Int): Ws {
            val u = URI(url)
            val tls = u.scheme == "wss" || u.scheme == "https"
            val host = u.host ?: throw IOException("host")
            val port = if (u.port > 0) u.port else if (tls) 443 else 80
            val raw = Socket()
            raw.connect(InetSocketAddress(host, port), 15_000)
            val s: Socket = if (tls) {
                val ss = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
                try { ss.sslParameters = ss.sslParameters.apply { serverNames = listOf(SNIHostName(host)) } } catch (_: Exception) { }
                ss.soTimeout = 20_000
                ss.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ss.session)) { ss.close(); throw SSLException("hostname") }
                ss
            } else raw
            s.soTimeout = 20_000
            val inp = s.getInputStream()
            val out = s.getOutputStream()
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also { rnd.nextBytes(it) })
            val path = (u.rawPath ?: "/").ifEmpty { "/" } + (u.rawQuery?.let { "?$it" } ?: "")
            val req = StringBuilder()
                .append("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append(if (u.port > 0) ":$port" else "").append("\r\n")
                .append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: ").append(key).append("\r\nSec-WebSocket-Version: 13\r\n")
            for ((k, v) in headers) req.append(k).append(": ").append(v).append("\r\n")
            req.append("\r\n")
            out.write(req.toString().toByteArray(Charsets.UTF_8)); out.flush()
            val head = readHead(inp)
            val status = head.substringBefore("\r\n")
            if (!status.matches(Regex("HTTP/1\\.[01] 101.*"))) { s.close(); throw IOException("ws " + status.take(60)) }
            val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)))
            val got = head.lineSequence().firstOrNull { it.lowercase().startsWith("sec-websocket-accept:") }?.substringAfter(':')?.trim()
            if (got != accept) { s.close(); throw IOException("ws accept") }
            s.soTimeout = readTimeoutMs
            return Ws(s, inp, out)
        }

        private fun readHead(inp: InputStream): String {
            val b = ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val c = inp.read()
                if (c < 0) throw IOException("ws closed")
                b.write(c)
                last4 = (last4 shl 8) or c
                if (last4 == 0x0D0A0D0A) break
                if (b.size() > 16_384) throw IOException("ws head")
            }
            return b.toString("UTF-8")
        }
    }

    @Volatile var lastRx = System.currentTimeMillis()
        private set

    /** Sends a text message (masked, as a client must). */
    fun send(text: String) = frame(0x1, text.toByteArray(Charsets.UTF_8))

    @Synchronized private fun frame(op: Int, data: ByteArray) {
        val h = ByteArrayOutputStream()
        h.write(0x80 or op)
        when {
            data.size < 126 -> h.write(0x80 or data.size)
            data.size < 65536 -> { h.write(0x80 or 126); h.write(data.size shr 8); h.write(data.size and 0xff) }
            else -> { h.write(0x80 or 127); for (i in 7 downTo 0) h.write(((data.size.toLong() shr (8 * i)) and 0xff).toInt()) }
        }
        val mask = ByteArray(4).also { rnd.nextBytes(it) }
        h.write(mask)
        val body = ByteArray(data.size) { (data[it].toInt() xor mask[it % 4].toInt()).toByte() }
        h.write(body)
        out.write(h.toByteArray()); out.flush()
    }

    private fun readFully(n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) { val r = inp.read(b, off, n - off); if (r < 0) throw IOException("ws closed"); off += r }
        return b
    }

    /** The next text message; null when the server closed. Answers pings by itself. Throws on timeout. */
    fun read(): String? {
        val msg = ByteArrayOutputStream()
        while (true) {
            val h = readFully(2)
            lastRx = System.currentTimeMillis()
            val fin = h[0].toInt() and 0x80 != 0
            val op = h[0].toInt() and 0x0f
            val masked = h[1].toInt() and 0x80 != 0
            var len = (h[1].toInt() and 0x7f).toLong()
            if (len == 126L) { val e = readFully(2); len = ((e[0].toLong() and 0xff) shl 8) or (e[1].toLong() and 0xff) }
            else if (len == 127L) { val e = readFully(8); len = 0; for (x in e) len = (len shl 8) or (x.toLong() and 0xff) }
            if (len > 1_000_000) throw IOException("ws big")
            val mask = if (masked) readFully(4) else null
            val data = readFully(len.toInt())
            if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (op) {
                0x8 -> { try { frame(0x8, ByteArray(0)) } catch (_: Exception) { }; return null }
                0x9 -> frame(0xA, data)
                0xA -> { }
                0x0, 0x1, 0x2 -> { msg.write(data); if (fin) return msg.toString("UTF-8") }
            }
        }
    }

    fun close() {
        try { frame(0x8, ByteArray(0)) } catch (_: Exception) { }
        try { socket.close() } catch (_: Exception) { }
    }
}
