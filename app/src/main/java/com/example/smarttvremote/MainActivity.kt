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
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.net.Socket
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var etManualIp: EditText
    private lateinit var etHiddenKeyboard: EditText
    private val ioScope = CoroutineScope(Dispatchers.IO)

    private var socketWriter: PrintWriter? = null
    private var activeToken: String = ""

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            etManualIp.setText(result.contents)
            parseAndConnect(result.contents)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Auto-launch TV QR Mode if running on an Android TV
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        if (uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            startActivity(Intent(this, TvReceiverActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        etManualIp = findViewById(R.id.etManualIp)
        etHiddenKeyboard = findViewById(R.id.etHiddenKeyboard)

        val trackpadArea = findViewById<FrameLayout>(R.id.trackpadArea)
        val scrollBarArea = findViewById<FrameLayout>(R.id.scrollBarArea)

        // 1. Scan QR Code Button
        findViewById<Button>(R.id.btnScanQr).setOnClickListener {
            val options = ScanOptions().apply {
                setPrompt("Scan the QR Code displayed on your Smart TV")
                setBeepEnabled(false)
                setOrientationLocked(false)
            }
            qrScannerLauncher.launch(options)
        }

        // Manual Switch to TV Mode
        findViewById<Button>(R.id.btnOpenTvMode).setOnClickListener {
            startActivity(Intent(this, TvReceiverActivity::class.java))
        }

        // Manual String Connect Fallback
        findViewById<Button>(R.id.btnManualConnect).setOnClickListener {
            parseAndConnect(etManualIp.text.toString().trim())
        }

        // 2. Trackpad Gestures (Move, Single Tap Click, Double-Tap -> TV OK)
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
                val dx = (-distanceX * 1.4f).toInt()
                val dy = (-distanceY * 1.4f).toInt()
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

        // 3. Dedicated Right-Side Scroll Bar
        var lastScrollY = 0f
        scrollBarArea.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> lastScrollY = event.y
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.y - lastScrollY
                    if (abs(deltaY) > 22f) {
                        if (deltaY > 0) sendCommand("SCROLL_DOWN")
                        else sendCommand("SCROLL_UP")
                        lastScrollY = event.y
                    }
                }
            }
            true
        }

        // 4. Bottom Navigation & Keyboard
        findViewById<Button>(R.id.btnBack).setOnClickListener { sendCommand("KEY_BACK") }
        findViewById<Button>(R.id.btnOk).setOnClickListener { sendCommand("KEY_OK") }
        findViewById<Button>(R.id.btnHome).setOnClickListener { sendCommand("KEY_HOME") }

        findViewById<Button>(R.id.btnKeyboard).setOnClickListener {
            etHiddenKeyboard.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etHiddenKeyboard, InputMethodManager.SHOW_FORCED)
        }

        setupKeyboardForwarding()
    }

    private fun parseAndConnect(qrString: String) {
        // Expected Format: IP|PORT|TOKEN
        val parts = qrString.split("|")
        if (parts.size != 3) {
            Toast.makeText(this, "Invalid QR format", Toast.LENGTH_SHORT).show()
            return
        }
        val ip = parts[0]
        val port = parts[1].toIntOrNull() ?: 8989
        activeToken = parts[2]

        tvStatus.text = "Status: Connecting to $ip:$port..."
        ioScope.launch {
            try {
                val socket = Socket(ip, port)
                socket.tcpNoDelay = true
                socketWriter = PrintWriter(socket.getOutputStream(), true)
                runOnUiThread {
                    tvStatus.text = "Status: Connected to $ip (Ready)"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Error: ${e.localizedMessage}"
                }
            }
        }
    }

    private fun setupKeyboardForwarding() {
        etHiddenKeyboard.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!s.isNullOrEmpty()) {
                    val lastChar = s.last()
                    sendCommand("TEXT:$lastChar")
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
        val writer = socketWriter ?: return
        ioScope.launch {
            writer.println("$activeToken|$cmd")
        }
    }
}

