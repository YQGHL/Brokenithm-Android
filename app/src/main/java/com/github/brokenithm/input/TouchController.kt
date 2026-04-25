package com.github.brokenithm.input

import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * Extracted from MainActivity lines 481-604.
 * Handles touch event processing: 32-key bit-packed Long computation,
 * touch size detection, and air height from touch Y position.
 *
 * Byte-for-byte compatible: produces IDENTICAL [touchedButtons: Long] values.
 */
class TouchController(
    private val onNewKeyPress: () -> Unit,
    private val onAllKeysReleased: () -> Unit
) {
    // Layout constants (identical to original MainActivity)
    private val numOfButtons = 16
    private val numOfGaps = 16
    private val buttonWidthToGap = 7.428571f
    private val numOfAirBlock = 6

    // Settings (set from MainActivity via updateSettings)
    var enableTouchSize = false
    var fatTouchSizeThreshold = 0.027f
    var extraFatTouchSizeThreshold = 0.035f
    var airSource = 3
    var simpleAir = false
    var debugInfoEnabled = false

    // Callbacks
    var debugInfoCallback: ((String) -> Unit)? = null
    var onTouchUpdate: ((buttons: Long, airHeight: Int, maxTouchedSize: Float) -> Unit)? = null

    // Computed layout geometry
    private var windowWidth = 0f
    private var windowHeight = 0f
    private var windowLeft = 0
    private var windowTop = 0
    var gapWidth = 0f
    var buttonWidth = 0f
    private var buttonBlockWidth = 0f
    private var airAreaHeight = 0f
    private var buttonAreaHeight = 0f
    private var airBlockHeight = 0f

    // State
    private var mTouchAreaRect: Rect? = null
    private var mLastButtons: Long = 0L

    // Optional auto-collapse for expand control
    var onAutoCollapseExpandable: (() -> Unit)? = null

    /**
     * Initializes touch area geometry and sets up the touch listener on [view].
     * Called from MainActivity after layout is complete (onGlobalLayout).
     */
    fun setup(view: View, ww: Float, wh: Float, wl: Int, wt: Int) {
        windowWidth = ww
        windowHeight = wh
        windowLeft = wl
        windowTop = wt

        gapWidth = windowWidth / (numOfButtons * buttonWidthToGap + numOfGaps)
        buttonWidth = gapWidth * buttonWidthToGap
        buttonBlockWidth = buttonWidth + gapWidth
        buttonAreaHeight = windowHeight * 0.5f
        airAreaHeight = windowHeight * 0.35f
        airBlockHeight = (buttonAreaHeight - airAreaHeight) / numOfAirBlock

        view.setOnTouchListener { v, event ->
            onTouch(v, event)
        }
    }

    /**
     * Called when any touch-related setting changes.
     */
    fun updateSettings(
        airSource: Int,
        simpleAir: Boolean,
        enableTouchSize: Boolean,
        fatThreshold: Float,
        extraFatThreshold: Float
    ) {
        this.airSource = airSource
        this.simpleAir = simpleAir
        this.enableTouchSize = enableTouchSize
        this.fatTouchSizeThreshold = fatThreshold
        this.extraFatTouchSizeThreshold = extraFatThreshold
    }

    /**
     * The main touch handler — byte-for-byte identical logic to the original
     * MainActivity touch_area listener (lines 481-603).
     */
    private fun onTouch(view: View, event: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            view.requestUnbufferedDispatch(event)
        }
        onAutoCollapseExpandable?.invoke()

        view ?: return view.performClick()
        event ?: return view.performClick()

        if (mTouchAreaRect == null) {
            val arr = IntArray(2)
            view.getLocationOnScreen(arr)
            mTouchAreaRect = Rect(arr[0], arr[1], arr[0] + view.width, arr[1] + view.height)
        }

        val currentAirAreaHeight = if (airSource != 3) 0f else airAreaHeight
        val currentButtonAreaHeight = if (airSource != 3) 0f else buttonAreaHeight
        val totalTouches = event.pointerCount
        var touchedButtons: Long = 0L
        var thisAirHeight = 6
        var maxTouchedSize = 0f

        if (event.action != KeyEvent.ACTION_UP && event.action != MotionEvent.ACTION_CANCEL) {
            var ignoredIndex = -1
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP)
                ignoredIndex = event.actionIndex
            for (i in 0 until totalTouches) {
                if (i == ignoredIndex)
                    continue
                val x = event.getX(i) + mTouchAreaRect!!.left - windowLeft
                val y = event.getY(i) + mTouchAreaRect!!.top - windowTop
                when {
                    y <= currentAirAreaHeight -> {
                        thisAirHeight = 0
                    }
                    y <= currentButtonAreaHeight -> {
                        val curAir = ((y - airAreaHeight) / airBlockHeight).toInt()
                        thisAirHeight = if (simpleAir) 0 else thisAirHeight.coerceAtMost(curAir)
                    }
                    else -> {
                        val pointPos = x / buttonBlockWidth
                        var index = pointPos.toInt()
                        if (index > numOfButtons) index = numOfButtons

                        if (enableTouchSize) {
                            var centerButton = index * 2
                            if ((touchedButtons and (1L shl centerButton)) != 0L) centerButton++
                            var leftButton = ((index - 1) * 2).coerceAtLeast(0)
                            if ((touchedButtons and (1L shl leftButton)) != 0L) leftButton++
                            var rightButton = ((index + 1) * 2).coerceAtMost(numOfButtons * 2)
                            if ((touchedButtons and (1L shl rightButton)) != 0L) rightButton++
                            var left2Button = ((index - 2) * 2).coerceAtLeast(0)
                            if ((touchedButtons and (1L shl left2Button)) != 0L) left2Button++
                            var right2Button = ((index + 2) * 2).coerceAtMost(numOfButtons * 2)
                            if ((touchedButtons and (1L shl right2Button)) != 0L) right2Button++

                            val currentSize = event.getSize(i)
                            maxTouchedSize = maxTouchedSize.coerceAtLeast(currentSize)

                            touchedButtons = touchedButtons or (1L shl centerButton)
                            val offsetRatio = (pointPos - index) * 4
                            when {
                                offsetRatio <= 1f -> {
                                    touchedButtons = touchedButtons or (1L shl leftButton)
                                    if (currentSize >= extraFatTouchSizeThreshold) {
                                        touchedButtons = touchedButtons or (1L shl left2Button)
                                        touchedButtons = touchedButtons or (1L shl rightButton)
                                    }
                                }
                                offsetRatio <= 3f -> {
                                    if (currentSize >= fatTouchSizeThreshold) {
                                        touchedButtons = touchedButtons or (1L shl leftButton)
                                        touchedButtons = touchedButtons or (1L shl rightButton)
                                    }
                                    if (currentSize >= extraFatTouchSizeThreshold) {
                                        touchedButtons = touchedButtons or (1L shl left2Button)
                                        touchedButtons = touchedButtons or (1L shl right2Button)
                                    }
                                }
                                else -> {
                                    touchedButtons = touchedButtons or (1L shl rightButton)
                                    if (currentSize >= extraFatTouchSizeThreshold) {
                                        touchedButtons = touchedButtons or (1L shl leftButton)
                                        touchedButtons = touchedButtons or (1L shl right2Button)
                                    }
                                }
                            }
                        } else {
                            if (index > 15) index = 15
                            var targetIndex = index * 2
                            if ((touchedButtons and (1L shl targetIndex)) != 0L) targetIndex++
                            touchedButtons = touchedButtons or (1L shl targetIndex)
                            if (index > 0) {
                                if ((pointPos - index) * 4 < 1) {
                                    targetIndex = (index - 1) * 2
                                    if ((touchedButtons and (1L shl targetIndex)) != 0L) targetIndex++
                                    touchedButtons = touchedButtons or (1L shl targetIndex)
                                }
                            } else if (index < 31) {
                                if ((pointPos - index) * 4 > 3) {
                                    targetIndex = (index + 1) * 2
                                    if ((touchedButtons and (1L shl targetIndex)) != 0L) targetIndex++
                                    touchedButtons = touchedButtons or (1L shl targetIndex)
                                }
                            }
                        }
                    }
                }
            }
        } else
            thisAirHeight = 6

        // Vibration trigger detection (identical to original)
        if (hasNewKeys(mLastButtons, touchedButtons))
            onNewKeyPress()
        else if (touchedButtons == 0L)
            onAllKeysReleased()
        mLastButtons = touchedButtons

        // Callback with touch data
        if (airSource == 3) {
            onTouchUpdate?.invoke(touchedButtons, thisAirHeight, maxTouchedSize)
        } else {
            onTouchUpdate?.invoke(touchedButtons, -1, maxTouchedSize)
        }

        if (debugInfoEnabled) {
            debugInfoCallback?.invoke(
                "Air: $thisAirHeight\nButtons: ${touchedButtons.toString()}\nSize: $maxTouchedSize\n$event"
            )
        }

        view.performClick()
        return true
    }

    companion object {
        private fun hasNewKeys(oldKeys: Long, newKeys: Long): Boolean {
            return (newKeys and oldKeys.inv()) != 0L
        }
    }
}
