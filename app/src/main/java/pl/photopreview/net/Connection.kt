package pl.photopreview.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/** A connected TCP socket wrapped with buffered data streams. */
class Connection(private val socket: Socket) : Closeable {
    val input: DataInputStream = DataInputStream(BufferedInputStream(socket.getInputStream()))
    val output: DataOutputStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

    init {
        runCatching { socket.tcpNoDelay = true }
    }

    val peer: String
        get() = socket.inetAddress?.hostAddress ?: "?"

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { socket.close() }
    }
}
