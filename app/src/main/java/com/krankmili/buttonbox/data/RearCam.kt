package com.krankmili.buttonbox.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Reverse camera feed: the PC streams JPEG frames of the game's reverse-camera screen as
 * [int32 big-endian length][jpeg] over a second TCP port (first line sent = token).
 */
class RearCam(private val scope: CoroutineScope) {

    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on.asStateFlow()

    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    private var job: Job? = null
    @Volatile private var sock: Socket? = null

    fun show(host: String, port: Int, token: String) {
        _on.value = true
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive && _on.value) {
                try {
                    stream(host, port, token)
                } catch (_: IOException) {
                    // retry below while the window is still wanted
                }
                if (isActive && _on.value) delay(1000)
            }
        }
    }

    fun hide() {
        _on.value = false
        job?.cancel()
        job = null
        try { sock?.close() } catch (_: IOException) {}
        sock = null
        _frame.value = null
    }

    private fun stream(host: String, port: Int, token: String) {
        val s = Socket()
        sock = s
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 3000)
            s.soTimeout = 8000
            s.getOutputStream().apply { write((token + "\n").toByteArray(Charsets.UTF_8)); flush() }
            val input = DataInputStream(s.getInputStream().buffered())
            while (_on.value) {
                val len = input.readInt()
                if (len <= 0 || len > MAX_FRAME) throw IOException("bad frame")
                val buf = ByteArray(len)
                input.readFully(buf)
                BitmapFactory.decodeByteArray(buf, 0, len)?.let { _frame.value = it }
            }
        } finally {
            try { s.close() } catch (_: IOException) {}
        }
    }

    private companion object { const val MAX_FRAME = 4 * 1024 * 1024 }
}
