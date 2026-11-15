package com.example.smarttvremote

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.wifi.WifiManager
import android.os.*
import android.view.*
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.net.ServerSocket

class TvReceiverService : Service() {

    companion object {
        var sessionToken: String = "123456"
        var isRunning = false
    }

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var serverSocket: ServerSocket? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Visible TV Cursor Overlay
    private var windowManager: WindowManager? = null
    private var cursorView: ImageView? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var cursorX = 960f
    private var cursorY = 540f
    private var screenWidth = 1920
    private var screenHeight = 1080

    // Direct Shizuku Binder (Zero-Lag InputManager)
    private var inputManagerInstance: Any? = null
    private var injectInputEventMethod: Method? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundServiceNotification()
        acquireLocks()
        initShizukuDirectBinder()
        setupVisibleCursorOverlay()
        startSocketServer()
    }

    private fun startForegroundServiceNotification() {
        val channelId = "tv_remote_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Smart TV Remote Receiver",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("TV Mouse & Remote Active")
            .setContentText("Listening for phone commands...")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartTVRemote::WakeLock").apply {
            acquire()
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SmartTVRemote::WifiLock").apply {
            acquire()
        }
    }

    private fun initShizukuDirectBinder() {
        try {
            if (!Shizuku.pingBinder()) return

            // 1. Auto-grant Overlay Permission via Shizuku so we can draw the Mouse Cursor on TV
            val newProcess = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            ).apply { isAccessible = true }
            val proc = newProcess.invoke(
                null,
                arrayOf("sh", "-c", "appops set $packageName SYSTEM_ALERT_WINDOW allow"),
                null,
                null
            ) as java.lang.Process
            proc.waitFor()

            // 2. Bind directly to Android's internal IInputManager for 0ms latency injection
            val rawBinder = SystemServiceHelper.getSystemService(Context.INPUT_SERVICE)
            val wrappedBinder = ShizukuBinderWrapper(rawBinder)
            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            inputManagerInstance = asInterface.invoke(null, wrappedBinder)

            injectInputEventMethod = inputManagerInstance?.javaClass?.getMethod(
                "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupVisibleCursorOverlay() {
        mainHandler.post {
            try {
                windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val metrics = resources.displayMetrics
                screenWidth = metrics.widthPixels
                screenHeight = metrics.heightPixels
                cursorX = screenWidth / 2f
                cursorY = screenHeight / 2f

                cursorView = ImageView(this).apply {
                    setImageBitmap(drawMousePointerBitmap())
                }

                val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                }

                cursorParams = WindowManager.LayoutParams(
                    48, 48,
                    overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = cursorX.toInt()
                    y = cursorY.toInt()
                }

                windowManager?.addView(cursorView, cursorParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun drawMousePointerBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, 38f)
            lineTo(11f, 28f)
            lineTo(19f, 44f)
            lineTo(25f, 41f)
            lineTo(17f, 25f)
            lineTo(30f, 25f)
            close()
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
        }
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
        return bmp
    }

    private fun updateCursorPosition(dx: Int, dy: Int) {
        cursorX = (cursorX + dx).coerceIn(0f, (screenWidth - 10).toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, (screenHeight - 10).toFloat())
        mainHandler.post {
            cursorParams?.let { params ->
                params.x = cursorX.toInt()
                params.y = cursorY.toInt()
                try {
                    windowManager?.updateViewLayout(cursorView, params)
                } catch (_: Exception) {}
            }
        }
        injectHoverMove(cursorX, cursorY)
    }

    private fun injectEventFast(event: InputEvent) {
        try {
            injectInputEventMethod?.invoke(inputManagerInstance, event, 0)
        } catch (_: Exception) {}
    }

    private fun injectKeyFast(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent(now, now + 5, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
        injectEventFast(down)
        injectEventFast(up)
    }

    private fun injectClickFast(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val up = MotionEvent.obtain(now, now + 15, MotionEvent.ACTION_UP, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        injectEventFast(down)
        injectEventFast(up)
        down.recycle()
        up.recycle()
    }

    private fun injectHoverMove(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val move = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0).apply {
            source = InputDevice.SOURCE_MOUSE
        }
        injectEventFast(move)
        move.recycle()
    }

    private fun injectScrollFast(vScroll: Float) {
        val now = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = cursorX
            y = cursorY
            setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
        })
        val scrollEvent = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        injectEventFast(scrollEvent)
        scrollEvent.recycle()
    }

    private fun injectTextFast(text: String) {
        val keyCharMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = keyCharMap.getEvents(text.toCharArray())
        events?.forEach { injectEventFast(it) }
    }

    private fun startSocketServer() {
        ioScope.launch {
            try {
                serverSocket = ServerSocket(8989)
                while (isActive) {
                    val client = serverSocket!!.accept()
                    client.tcpNoDelay = true
                    launch {
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                handlePacket(line!!)
                            }
                        } catch (_: Exception) {
                        } finally {
                            try { client.close() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun handlePacket(packet: String) {
        val parts = packet.split("|", limit = 2)
        if (parts.size < 2 || parts[0] != sessionToken) return

        val cmd = parts[1]
        when {
            cmd.startsWith("MOUSE_MOVE:") -> {
                val coords = cmd.removePrefix("MOUSE_MOVE:").split(",")
                updateCursorPosition(coords[0].toInt(), coords[1].toInt())
            }
            cmd == "MOUSE_CLICK" -> injectClickFast(cursorX, cursorY)
            cmd == "SCROLL_UP" -> injectScrollFast(2.0f)
            cmd == "SCROLL_DOWN" -> injectScrollFast(-2.0f)
            cmd.startsWith("TEXT:") -> injectTextFast(cmd.removePrefix("TEXT:"))

            // Full TV Remote Keycodes
            cmd == "KEY_OK" -> injectKeyFast(KeyEvent.KEYCODE_DPAD_CENTER)
            cmd == "KEY_UP" -> injectKeyFast(KeyEvent.KEYCODE_DPAD_UP)
            cmd == "KEY_DOWN" -> injectKeyFast(KeyEvent.KEYCODE_DPAD_DOWN)
            cmd == "KEY_LEFT" -> injectKeyFast(KeyEvent.KEYCODE_DPAD_LEFT)
            cmd == "KEY_RIGHT" -> injectKeyFast(KeyEvent.KEYCODE_DPAD_RIGHT)
            cmd == "KEY_BACK" -> injectKeyFast(KeyEvent.KEYCODE_BACK)
            cmd == "KEY_HOME" -> injectKeyFast(KeyEvent.KEYCODE_HOME)
            cmd == "KEY_MENU" -> injectKeyFast(KeyEvent.KEYCODE_MENU)
            cmd == "KEY_VOL_UP" -> injectKeyFast(KeyEvent.KEYCODE_VOLUME_UP)
            cmd == "KEY_VOL_DOWN" -> injectKeyFast(KeyEvent.KEYCODE_VOLUME_DOWN)
            cmd == "KEY_MUTE" -> injectKeyFast(KeyEvent.KEYCODE_VOLUME_MUTE)
            cmd == "KEY_POWER" -> injectKeyFast(KeyEvent.KEYCODE_POWER)
            cmd == "KEY_CH_UP" -> injectKeyFast(KeyEvent.KEYCODE_CHANNEL_UP)
            cmd == "KEY_CH_DOWN" -> injectKeyFast(KeyEvent.KEYCODE_CHANNEL_DOWN)
            cmd == "KEY_PLAY_PAUSE" -> injectKeyFast(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            cmd == "KEY_BACKSPACE" -> injectKeyFast(KeyEvent.KEYCODE_DEL)
        }
    }

    override fun onDestroy() {
        isRunning = false
        ioScope.cancel()
        try { serverSocket?.close() } catch (_: Exception) {}
        mainHandler.post {
            if (cursorView != null) {
                try { windowManager?.removeView(cursorView) } catch (_: Exception) {}
            }
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
