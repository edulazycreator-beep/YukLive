@file:OptIn(UnstableApi::class)

package com.yuklive.app.video.network

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.github.thibaultbee.srtdroid.core.Srt
import io.github.thibaultbee.srtdroid.core.enums.SockOpt
import io.github.thibaultbee.srtdroid.core.enums.Transtype
import io.github.thibaultbee.srtdroid.core.models.SrtSocket
import java.io.IOException
import java.net.InetSocketAddress

/**
 * DataSource Media3 untuk SRT (MPEG-TS di atas SRT) memakai srtdroid.
 *
 * URI: srt://host:port?mode=caller|listener&latency=120&passphrase=...&streamid=...
 * - caller  : menyambung ke server/HP lain.
 * - listener: menunggu HP lain mengirim ke IP:port HP ini ("Terima dari HP lain").
 */
class SrtDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var server: SrtSocket? = null
    private var socket: SrtSocket? = null
    private var uri: Uri? = null

    private val packet = ByteArray(PACKET_SIZE)
    private var packetLen = 0
    private var packetPos = 0

    @Volatile private var closed = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        closed = false
        transferInitializing(dataSpec)
        ensureStartUp()

        val u = dataSpec.uri
        val mode = u.getQueryParameter("mode") ?: "caller"
        val latency = u.getQueryParameter("latency")?.toIntOrNull() ?: 120
        val passphrase = u.getQueryParameter("passphrase")
        val streamId = u.getQueryParameter("streamid")
        val port = if (u.port > 0) u.port else 9000

        try {
            if (mode.equals("listener", ignoreCase = true)) {
                val srv = SrtSocket()
                server = srv
                srv.configure(latency, passphrase, null)
                srv.bind(InetSocketAddress("0.0.0.0", port))
                srv.listen(1)
                val client = srv.accept().first // menunggu HP lain menyambung
                if (closed) throw IOException("closed")
                socket = client
            } else {
                val s = SrtSocket()
                socket = s
                s.configure(latency, passphrase, streamId)
                s.connect(InetSocketAddress(u.host ?: throw IOException("host kosong"), port))
            }
        } catch (e: Exception) {
            closeSockets()
            throw IOException("SRT: ${e.message}", e)
        }

        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    private fun SrtSocket.configure(latency: Int, passphrase: String?, streamId: String?) {
        setSockFlag(SockOpt.TRANSTYPE, Transtype.LIVE)
        setSockFlag(SockOpt.LATENCY, latency)
        if (!passphrase.isNullOrEmpty()) setSockFlag(SockOpt.PASSPHRASE, passphrase)
        if (!streamId.isNullOrEmpty()) setSockFlag(SockOpt.STREAMID, streamId)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (packetPos >= packetLen) {
            val s = socket ?: return C.RESULT_END_OF_INPUT
            packetLen = try {
                s.recv(packet, 0, packet.size)
            } catch (e: Exception) {
                if (closed) return C.RESULT_END_OF_INPUT
                throw IOException("SRT terputus: ${e.message}", e)
            }
            packetPos = 0
        }
        val n = minOf(length, packetLen - packetPos)
        System.arraycopy(packet, packetPos, buffer, offset, n)
        packetPos += n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closed = true
        val wasOpen = socket != null
        closeSockets()
        packetLen = 0
        packetPos = 0
        if (wasOpen) transferEnded()
    }

    private fun closeSockets() {
        runCatching { socket?.close() }
        runCatching { server?.close() }
        socket = null
        server = null
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SrtDataSource()
    }

    private companion object {
        const val PACKET_SIZE = 1316 * 2

        @Volatile var started = false

        @Synchronized
        fun ensureStartUp() {
            if (!started) {
                Srt.startUp()
                started = true
            }
        }
    }
}
