package com.github.brokenithm.activity

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.lifecycleScope
import com.github.brokenithm.BrokenithmApplication
import com.github.brokenithm.R
import com.github.brokenithm.haptic.VibrationController
import com.github.brokenithm.input.TouchController
import com.github.brokenithm.network.*
import com.github.brokenithm.nfc.NFCManager
import com.github.brokenithm.sensor.SensorController
import com.github.brokenithm.ui.LEDRenderer
import net.cachapa.expandablelayout.ExpandableLayout
import java.net.InetSocketAddress

class MainActivity : AppCompatActivity() {
    // Managers
    private lateinit var networkManager: NetworkManager
    private lateinit var touchController: TouchController
    private lateinit var sensorController: SensorController
    private lateinit var ledRenderer: LEDRenderer
    private lateinit var nfcManager: NFCManager
    private lateinit var vibrationController: VibrationController

    // Shared state objects
    private lateinit var inputState: InputState
    private lateinit var cardState: CardState

    // App
    private lateinit var app: BrokenithmApplication

    // Connection state
    @Volatile private var mExitFlag = true
    private var mTCPMode = false
    private var mAirSource = 3
    private var mSimpleAir = false
    private var mDebugInfo = false
    private var mEnableAir = true
    private var mShowDelay = false

    // Layout geometry (set once during onCreate)
    private var windowWidth = 0f
    private var windowHeight = 0f
    private var windowLeft = 0
    private var windowTop = 0

    // UI references
    private lateinit var mDelayText: TextView
    private lateinit var textInfo: TextView
    private lateinit var textExpand: TextView
    private lateinit var expandControl: ExpandableLayout
    private var exitTime: Long = 0

    // Constants (identical to original)
    private val serverPort = 52468
    private val numOfButtons = 16
    private val numOfGaps = 16
    private val buttonWidthToGap = 7.428571f
    private val mAirIdx = listOf(4, 5, 2, 3, 0, 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setImmersive()
        app = application as BrokenithmApplication

        // Create shared state
        inputState = InputState()
        cardState = CardState()

        // Create managers in dependency order
        vibrationController = VibrationController(this, lifecycleScope)
        networkManager = NetworkManager(
            lifecycleScope, inputState, cardState,
            onLEDData = { ledRenderer.updateLED(it) },
            onDelayUpdate = { networkManager.currentDelay = it },
            onDelayDisplay = { delay ->
                runOnUiThread {
                    mDelayText.text = getString(R.string.current_latency, delay)
                }
            }
        )
        networkManager.apply {
            enableAir = mEnableAir
            enableNFC = app.enableNFC.value()
            tcpMode = mTCPMode
            showDelay = mShowDelay
            airIdx = mAirIdx
            init()
        }

        touchController = TouchController(
            onNewKeyPress = { vibrationController.trigger() },
            onAllKeysReleased = { vibrationController.clear() }
        )

        sensorController = SensorController { airHeight ->
            inputState.airHeight = airHeight
        }

        ledRenderer = LEDRenderer(findViewById(R.id.button_render_area))

        nfcManager = NFCManager(this, cardState, lifecycleScope)

        // ── UI wiring ──

        mDelayText = findViewById(R.id.text_delay)
        textInfo = findViewById(R.id.text_info)
        textExpand = findViewById(R.id.text_expand)
        expandControl = findViewById(R.id.expand_control)

        textExpand.setOnClickListener {
            if (expandControl.isExpanded) {
                (it as TextView).setText(R.string.expand)
                expandControl.collapse()
            } else {
                (it as TextView).setText(R.string.collapse)
                expandControl.expand()
            }
        }

        touchController.onAutoCollapseExpandable = {
            if (expandControl.isExpanded) textExpand.callOnClick()
        }

        // Debug checkbox
        findViewById<CheckBox>(R.id.check_debug).setOnCheckedChangeListener { _, isChecked ->
            mDebugInfo = isChecked
            textInfo.visibility = if (isChecked) View.VISIBLE else View.GONE
            touchController.debugInfoEnabled = isChecked
        }

        touchController.debugInfoCallback = { debugStr ->
            textInfo.text = debugStr
        }

        touchController.onTouchUpdate = { buttons, airHeight, _ ->
            inputState.buttons = buttons
            if (mAirSource == 3) {
                inputState.airHeight = airHeight
            }
        }

        // Settings button
        findViewById<Button>(R.id.button_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Server address
        val editServer = findViewById<EditText>(R.id.edit_server).apply {
            setText(app.lastServer.value())
        }

        // Start/Stop button
        findViewById<Button>(R.id.button_start).setOnClickListener {
            val server = editServer.text.toString()
            if (server.isBlank()) return@setOnClickListener
            if (mExitFlag) {
                if (!networkManager.exitFlag) return@setOnClickListener
                mExitFlag = false
                (it as Button).setText(R.string.stop)
                editServer.isEnabled = false
                findViewById<Button>(R.id.button_settings).isEnabled = false
                app.lastServer.update(server)
                val address = parseAddress(server) ?: return@setOnClickListener
                networkManager.exitFlag = false
                networkManager.start(address)
            } else {
                mExitFlag = true
                (it as Button).setText(R.string.start)
                editServer.isEnabled = true
                findViewById<Button>(R.id.button_settings).isEnabled = true
                networkManager.exitFlag = true
                networkManager.stop(parseAddress(editServer.text.toString()))
            }
        }

        // Coin / Card function buttons
        findViewById<Button>(R.id.button_coin).setOnClickListener {
            if (!mExitFlag)
                networkManager.sendFunctionKey(
                    parseAddress(editServer.text.toString()),
                    FunctionButton.FUNCTION_COIN
                )
        }
        findViewById<Button>(R.id.button_card).setOnClickListener {
            if (!mExitFlag)
                networkManager.sendFunctionKey(
                    parseAddress(editServer.text.toString()),
                    FunctionButton.FUNCTION_CARD
                )
        }

        // Test button
        findViewById<View>(R.id.button_test).setOnTouchListener { view, event ->
            inputState.testButton = when (event.actionMasked) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN -> true
                else -> false
            }
            view.performClick()
        }
        // Service button
        findViewById<View>(R.id.button_service).setOnTouchListener { view, event ->
            inputState.serviceButton = when (event.actionMasked) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN -> true
                else -> false
            }
            view.performClick()
        }

        // Air source toggle
        mAirSource = app.airSource.value()
        val checkSimpleAir = findViewById<CheckBox>(R.id.check_simple_air)
        checkSimpleAir.isEnabled = mAirSource == 1 || mAirSource == 3
        findViewById<TextView>(R.id.text_switch_air).apply {
            setOnClickListener {
                mAirSource = when (mAirSource) {
                    0 -> { text = getString(R.string.gyro_air); mEnableAir = true; checkSimpleAir.isEnabled = true; 1 }
                    1 -> { text = getString(R.string.accel_air); checkSimpleAir.isEnabled = false; 2 }
                    2 -> { text = getString(R.string.touch_air); checkSimpleAir.isEnabled = true; 3 }
                    else -> { text = getString(R.string.disable_air); mEnableAir = false; checkSimpleAir.isEnabled = false; 0 }
                }
                app.airSource.update(mAirSource)
                networkManager.enableAir = mEnableAir
                touchController.airSource = mAirSource
            }
            text = getString(when (mAirSource) {
                0 -> R.string.disable_air
                1 -> R.string.gyro_air
                2 -> R.string.accel_air
                else -> R.string.touch_air
            })
        }

        // Simple air checkbox
        findViewById<CheckBox>(R.id.check_simple_air).apply {
            setOnCheckedChangeListener { _, isChecked ->
                mSimpleAir = isChecked
                app.simpleAir.update(isChecked)
                touchController.simpleAir = isChecked
                sensorController.updateSettings(
                    app.gyroAirLowestBound.value(),
                    app.gyroAirHighestBound.value(),
                    app.accelAirThreshold.value(),
                    mSimpleAir
                )
            }
            isChecked = app.simpleAir.value()
        }

        mSimpleAir = app.simpleAir.value()
        mEnableAir = app.enableAir.value()
        networkManager.enableAir = mEnableAir

        // Show delay checkbox
        findViewById<CheckBox>(R.id.check_show_delay).apply {
            setOnCheckedChangeListener { _, isChecked ->
                mShowDelay = isChecked
                mDelayText.visibility = if (isChecked) View.VISIBLE else View.GONE
                app.showDelay.update(isChecked)
                networkManager.showDelay = isChecked
            }
            isChecked = app.showDelay.value()
        }

        // TCP/UDP mode toggle
        mTCPMode = app.tcpMode.value()
        findViewById<TextView>(R.id.text_mode).apply {
            text = getString(if (mTCPMode) R.string.tcp else R.string.udp)
            setOnClickListener {
                if (!mExitFlag) return@setOnClickListener
                text = getString(if (mTCPMode) {
                    mTCPMode = false
                    R.string.udp
                } else {
                    mTCPMode = true
                    R.string.tcp
                })
                app.tcpMode.update(mTCPMode)
                networkManager.tcpMode = mTCPMode
            }
        }

        // Layout listener — compute window geometry, init touch/LED areas
        val contentView = findViewById<ViewGroup>(android.R.id.content)
        contentView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                contentView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val arr = IntArray(2)
                contentView.getLocationOnScreen(arr)
                windowWidth = contentView.width.toFloat()
                windowHeight = contentView.height.toFloat()
                windowLeft = arr[0]
                windowTop = arr[1]
                initTouchArea()
            }
        })
    }

    private fun initTouchArea() {
        val touchView = findViewById<View>(R.id.touch_area)
        touchController.setup(touchView, windowWidth, windowHeight, windowLeft, windowTop)

        val gapWidth = touchController.gapWidth
        val buttonWidth = touchController.buttonWidth
        val buttonBlockWidth = buttonWidth + gapWidth
        val buttonAreaHeight = windowHeight * 0.5f

        ledRenderer.init(
            windowWidth.toInt(), buttonAreaHeight,
            numOfButtons, numOfGaps, buttonWidth, gapWidth
        )
    }

    override fun onResume() {
        super.onResume()
        sensorController.start(this, mAirSource)
        nfcManager.enable()
        loadPreference()
    }

    override fun onPause() {
        nfcManager.disable()
        sensorController.stop()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        nfcManager.handleIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setImmersive()
    }

    override fun onBackPressed() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - exitTime > 1500) {
            Toast.makeText(this, R.string.press_again_to_exit, Toast.LENGTH_SHORT).show()
            exitTime = currentTime
        } else {
            finish()
        }
    }

    private fun setImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    private fun loadPreference() {
        touchController.enableTouchSize = app.enableTouchSize.value()
        touchController.fatTouchSizeThreshold = app.fatTouchThreshold.value()
        touchController.extraFatTouchSizeThreshold = app.extraFatTouchThreshold.value()
        nfcManager.enabled = app.enableNFC.value()
        vibrationController.enabled = app.enableVibrate.value()
        sensorController.updateSettings(
            app.gyroAirLowestBound.value(),
            app.gyroAirHighestBound.value(),
            app.accelAirThreshold.value(),
            mSimpleAir
        )
    }

    private fun parseAddress(address: String): InetSocketAddress? {
        val parts = address.split(":")
        return when (parts.size) {
            1 -> InetSocketAddress(parts[0], serverPort)
            2 -> InetSocketAddress(parts[0], parts[1].toInt())
            else -> null
        }
    }

    companion object {
        private const val TAG = "Brokenithm"
    }
}
