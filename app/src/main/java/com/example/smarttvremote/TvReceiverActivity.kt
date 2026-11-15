package com.example.smarttvremote

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.UUID

class TvReceiverActivity : AppCompatActivity() {

    private lateinit var ivQrCode: ImageView
    private lateinit var tvStatus: TextView
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val sessionToken = UUID.randomUUID().toString().substring(0, 8)
    private val serverPort = 8989

    private var shizukuShellIn: OutputStream? = null
    private var serverSocket: ServerSocket? = null

    private var cursorX = 960
    private var cursorY = 540

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            initShizukuShell()
            startQrServer()
        } else {
            tvStatus.text = "Shizuku Permission Denied. Please allow in Shizuku app."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_receiver)

        ivQrCode = findViewById(R.id.ivQrCode)
        tvStatus = findViewById(R.id.tvTvStatus)

        Shizuku.addRequestPermissionResultListener(permissionListener)

        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                initShizukuShell()
                startQrServer()
            } else {
                Shizuku.requestPermission(1001)
            }
        } else {
            tvStatus.text = "Shizuku is not running on TV!\nStart Shizuku first, then reopen this screen."
            // Still show QR for testing connection even if Shizuku isn't active yet
            startQrServer()
        }
    }

    private fun initShizukuShell() {
        try {
            val process = Shizuku.newProcess(arrayOf("sh"), null, null)
            shizukuShellIn = process.outputStream
        } catch (e: Exception) {
            tvStatus.text = "Shizuku Shell Error: ${e.localizedMessage}"
        }
    }

    private fun execShizukuInput(cmd: String) {
        try {
            shizukuShellIn?.write("$cmd\n".toByteArray())
            shizukuShellIn?.flush()
        } catch (_: Exception) {}
    }

    private fun startQrServer() {
        val ip = getLocalIpAddress() ?: "127.0.0.1"
        val qrPayload = "$ip|$serverPort|$sessionToken"

        ivQrCode.setImageBitmap(generateQrBitmap(qrPayload))
        tvStatus.text = "Scan QR Code with Phone App to Connect\nTV IP: $ip:$serverPort"

        ioScope.launch {
            try {
                serverSocket = ServerSocket(serverPort)
                while (!isFinishing) {
                    val client = serverSocket!!.accept()
                    runOnUiThread {
                        tvStatus.text = "Phone Connected! You can now minimize this screen (Press Home)."
                    }
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        processCommand(line!!)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun processCommand(packet: String) {
        val parts = packet.split("|", limit = 2)
        if (parts.size < 2 || parts[0] != sessionToken) return

        val cmd = parts[1]
        when {
            cmd == "KEY_OK" -> execShizukuInput("input keyevent 23")
            cmd == "MOUSE_CLICK" -> execShizukuInput("input mouse tap $cursorX $cursorY")
            cmd.startsWith("MOUSE_MOVE:") -> {
                val coords = cmd.removePrefix("MOUSE_MOVE:").split(",")
                cursorX = (cursorX + coords[0].toInt()).coerceIn(0, 1920)
                cursorY = (cursorY + coords[1].toInt()).coerceIn(0, 1080)
                execShizukuInput("input mouse move $cursorX $cursorY")
            }
            cmd == "SCROLL_UP" -> execShizukuInput("input roll 0 -5")
            cmd == "SCROLL_DOWN" -> execShizukuInput("input roll 0 5")
            cmd.startsWith("TEXT:") -> {
                val raw = cmd.removePrefix("TEXT:")
                val escaped = if (raw == " ") "%s" else raw.replace("\"", "\\\"")
                execShizukuInput("input text \"$escaped\"")
            }
            cmd == "KEY_BACK" -> execShizukuInput("input keyevent 4")
            cmd == "KEY_HOME" -> execShizukuInput("input keyevent 3")
            cmd == "KEY_BACKSPACE" -> execShizukuInput("input keyevent 67")
        }
    }

    private fun generateQrBitmap(content: String): Bitmap {
        val size = 512
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    private fun getLocalIpAddress(): String? {
        NetworkInterface.getNetworkInterfaces().toList().forEach { intf ->
            intf.inetAddresses.toList().forEach { addr ->
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    return addr.hostAddress
                }
            }
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        try { serverSocket?.close() } catch (_: Exception) {}
    }
}

