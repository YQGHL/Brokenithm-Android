package com.github.brokenithm.ui

import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.view.View

/**
 * Extracted from MainActivity lines 476-480, 1177-1204.
 * Renders LED data onto a Bitmap-backed Canvas and posts invalidate on the render view.
 *
 * Byte-for-byte compatible: produces IDENTICAL visual output (same Canvas drawRect calls,
 * same color computation, same bitmap format).
 */
class LEDRenderer(private val renderView: View) {
    private lateinit var mLEDBitmap: Bitmap
    private lateinit var mLEDCanvas: Canvas
    private val mLEDPaint = Paint()
    private var buttonWidth = 0f
    private var gapWidth = 0f
    private var numOfButtons = 16
    private var numOfGaps = 16

    /**
     * Initialize the LED bitmap and canvas.
     * Must be called after window dimensions are known.
     */
    fun init(
        windowWidth: Int,
        buttonAreaHeight: Float,
        numOfButtons: Int,
        numOfGaps: Int,
        buttonWidth: Float,
        gapWidth: Float
    ) {
        this.numOfButtons = numOfButtons
        this.numOfGaps = numOfGaps
        this.buttonWidth = buttonWidth
        this.gapWidth = gapWidth

        mLEDBitmap = Bitmap.createBitmap(
            windowWidth,
            buttonAreaHeight.toInt(),
            Bitmap.Config.RGB_565
        )
        mLEDCanvas = Canvas(mLEDBitmap)
        renderView.background = BitmapDrawable(renderView.resources, mLEDBitmap)
    }

    /**
     * Return the background drawable for the render view.
     */
    fun getBackground(): BitmapDrawable {
        return renderView.background as BitmapDrawable
    }

    /**
     * Update LED display — byte-for-byte identical logic to original setLED (lines 1177-1204).
     */
    fun updateLED(status: ByteArray) {
        val blockCount = numOfButtons + numOfGaps
        val steps = 32 / blockCount
        val offset = 4

        var drawXOffset = 0f
        val drawHeight = mLEDBitmap.height

        for (i in (blockCount - 1).downTo(0)) {
            val index = offset + (i * steps * 3)
            val blue = status[index].toInt() and 0xff
            val red = status[index + 1].toInt() and 0xff
            val green = status[index + 2].toInt() and 0xff
            val color = 0xff000000 or (red.toLong() shl 16) or (green.toLong() shl 8) or blue.toLong()

            val left = drawXOffset
            val width = when (i.rem(2)) {
                0 -> buttonWidth
                1 -> gapWidth
                else -> continue
            }
            val right = left + width
            mLEDPaint.color = color.toInt()
            mLEDCanvas.drawRect(left, 0f, right, drawHeight.toFloat(), mLEDPaint)
            drawXOffset += width
        }
        renderView.postInvalidate()
    }
}
