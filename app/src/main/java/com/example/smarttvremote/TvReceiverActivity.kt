package com.example.smarttvremote

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import rikka.shizuku.Shizuku
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

class TvReceiverActivity : AppCompatActivity() {

    private lateinit var ivQrCode: ImageView
    private lateinit var tvStatus: TextView

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            launchBackgroundService()
        } else {
            tvStatus.text = "Shizuku Permission Denied! Allow in Shizuku App."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_receiver)

        ivQrCode = findViewById(R.id.ivQrCode)
        tvStatus = findViewById(R.id.tvTvStatus)

        Shizuku.addRequestPermissionResultListener(permissionListener)

        if (!TvReceiverService.isRunning) {
            TvReceiverService.sessionToken = UUID.randomUUID().toString().substring(0, 6)
        }

        val ip = getLocalIpAddress() ?: "127.0.0.1"
        val qrPayload = "$ip|8989|${TvReceiverService.sessionToken}"
        ivQrCode.setImageBitmap(generateQrBitmap(qrPayload))

        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                launchBackgroundService()
            } else {
                Shizuku.requestPermission(1001)
            }
        } else {
            tvStatus.text = "Shizuku not running on TV! Start Shizuku first.\nIP: $ip"
        }
    }

    private fun launchBackgroundService() {
        val serviceIntent = Intent(this, TvReceiverService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        val ip = getLocalIpAddress() ?: "127.0.0.1"
        tvStatus.text = "Cursor & Remote Active in Background!\nScan QR with Phone, then press TV HOME button.\nTV IP: $ip:8989"
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
    }
}
