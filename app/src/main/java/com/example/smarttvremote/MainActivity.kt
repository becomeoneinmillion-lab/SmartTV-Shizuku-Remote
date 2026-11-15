package com.example.smarttvremote

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.*
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var etHiddenKeyboard: EditText
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var socketWriter: PrintWriter? = null
    private var savedIp = ""
    private var savedPort = 8989
    private var activeToken = ""

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            getSharedPreferences("remote_prefs", MODE_PRIVATE).edit()
                .putString("last_qr", result.contents).apply()
            parseAndConnect(result.contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        if (uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            startActivity(Intent(this, TvReceiverActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        etHiddenKeyboard = findViewById(R.id.etHiddenKeyboard)

        val layoutMousePad = findViewById<LinearLayout>(R.id.layoutMousePad)
        val layoutTvRemote = findViewById<LinearLayout>(R.id.layoutTvRemote)
        val btnToggleView = findViewById<Button>(R.id.btnToggleView)

        // Switch between Mouse Trackpad and Full TV Remote
        btnToggleView.setOnClickListener {
            if (layoutMousePad.visibility == View.VISIBLE) {
                layoutMousePad.visibility = View.GONE
                layoutTvRemote.visibility = View.VISIBLE
                btnToggleView.text = "Switch: Mouse Pad"
            } else {
                layoutMousePad.visibility = View.VISIBLE
                layoutTvRemote.visibility = View.GONE
                btnToggleView.text = "Switch: TV Remote"
            }
        }

        findViewById<Button>(R.id.btnScanQr).setOnClickListener {
            val options = ScanOptions().apply {
                setPrompt("Scan the QR Code on your Smart TV")
                setBeepEnabled(false)
                setOrientationLocked(false)
            }
            qrScannerLauncher.launch(options)
        }

        findViewById<Button>(R.id.btnOpenTvMode).setOnClickListener {
            startActivity(Intent(this, TvReceiverActivity::class.java))
        }

        // High-Speed 60FPS Trackpad
        val trackpadArea = findViewById<FrameLayout>(R.id.trackpadArea)
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                sendCommand("MOUSE_CLICK")
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                sendCommand("KEY_OK")
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val dx = (-distanceX * 1.8f).toInt()
                val dy = (-distanceY * 1.8f).toInt()
                if (dx != 0 || dy != 0) {
                    sendCommand("MOUSE_MOVE:$dx,$dy")
                }
                return true
            }
        })

        trackpadArea.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        // Smooth Scroll Bar
        var lastScrollY = 0f
        findViewById<FrameLayout>(R.id.scrollBarArea).setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> lastScrollY = event.y
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.y - lastScrollY
                    if (abs(deltaY) > 18f) {
                        if (deltaY > 0) sendCommand("SCROLL_DOWN")
                        else sendCommand("SCROLL_UP")
                        lastScrollY = event.y
                    }
                }
            }
            true
        }

        // TV Remote Buttons
        findViewById<Button>(R.id.btnPower).setOnClickListener { sendCommand("KEY_POWER") }
        findViewById<Button>(R.id.btnMute).setOnClickListener { sendCommand("KEY_MUTE") }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { sendCommand("KEY_MENU") }
        findViewById<Button>(R.id.btnDpadUp).setOnClickListener { sendCommand("KEY_UP") }
        findViewById<Button>(R.id.btnDpadDown).setOnClickListener { sendCommand("KEY_DOWN") }
        findViewById<Button>(R.id.btnDpadLeft).setOnClickListener { sendCommand("KEY_LEFT") }
        findViewById<Button>(R.id.btnDpadRight).setOnClickListener { sendCommand("KEY_RIGHT") }
        findViewById<Button>(R.id.btnDpadCenter).setOnClickListener { sendCommand("KEY_OK") }
        findViewById<Button>(R.id.btnVolUp).setOnClickListener { sendCommand("KEY_VOL_UP") }
        findViewById<Button>(R.id.btnVolDown).setOnClickListener { sendCommand("KEY_VOL_DOWN") }
        findViewById<Button>(R.id.btnChUp).setOnClickListener { sendCommand("KEY_CH_UP") }
        findViewById<Button>(R.id.btnChDown).setOnClickListener { sendCommand("KEY_CH_DOWN") }

        // Bottom Bar Buttons
        findViewById<Button>(R.id.btnBack).setOnClickListener { sendCommand("KEY_BACK") }
        findViewById<Button>(R.id.btnHome).setOnClickListener { sendCommand("KEY_HOME") }
        findViewById<Button>(R.id.btnPlayPause).setOnClickListener { sendCommand("KEY_PLAY_PAUSE") }

        findViewById<Button>(R.id.btnKeyboard).setOnClickListener {
            etHiddenKeyboard.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etHiddenKeyboard, InputMethodManager.SHOW_FORCED)
        }

        setupKeyboardForwarding()

        // Auto-reconnect to last scanned TV on launch
        val lastQr = getSharedPreferences("remote_prefs", MODE_PRIVATE).getString("last_qr", null)
        if (lastQr != null) parseAndConnect(lastQr)
    }

    private fun parseAndConnect(qrString: String) {
        val parts = qrString.split("|")
        if (parts.size != 3) return
        savedIp = parts[0]
        savedPort = parts[1].toIntOrNull() ?: 8989
        activeToken = parts[2]
        connectSocket()
    }

    private fun connectSocket() {
        if (savedIp.isEmpty()) return
        ioScope.launch {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(savedIp, savedPort), 2500)
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socketWriter = PrintWriter(socket.getOutputStream(), true)
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Connected to TV ($savedIp) • 60FPS Direct Mode"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Disconnected: Tap Scan QR (${e.localizedMessage})"
                }
            }
        }
    }

    private fun setupKeyboardForwarding() {
        etHiddenKeyboard.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!s.isNullOrEmpty()) {
                    sendCommand("TEXT:${s.last()}")
                    etHiddenKeyboard.setText("")
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        etHiddenKeyboard.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DEL -> sendCommand("KEY_BACKSPACE")
                    KeyEvent.KEYCODE_ENTER -> sendCommand("KEY_OK")
                }
            }
            false
        }
    }

    private fun sendCommand(cmd: String) {
        ioScope.launch {
            try {
                val writer = socketWriter
                if (writer == null || writer.checkError()) {
                    connectSocket()
                } else {
                    writer.println("$activeToken|$cmd")
                }
            } catch (_: Exception) {
                connectSocket()
            }
        }
    }

    override fun onDestroy() {
        ioScope.cancel()
        super.onDestroy()
    }
}
