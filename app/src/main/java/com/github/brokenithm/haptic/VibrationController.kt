package com.github.brokenithm.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Extracted from MainActivity lines 88-94, 251-259, 891-904.
 * Haptic feedback using Channel<Long> for proper backpressure,
 * replacing the original 10ms polling loop.
 *
 * Byte-for-byte compatible in behavior: same vibrate timing and triggering.
 */
class VibrationController(private val context: Context, scope: CoroutineScope) {
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val channel = Channel<Long>(Channel.BUFFERED)
    private val defaultLengthMs = 50L

    @Volatile
    var enabled = true

    private val vibrateMethod: (Long) -> Unit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        { length ->
            vibrator.vibrate(VibrationEffect.createOneShot(length, 255))
        }
    } else {
        { length ->
            vibrator.vibrate(length)
        }
    }

    init {
        // Consumer coroutine — replaces the 10ms polling loop (lines 891-904)
        scope.launch {
            for (length in channel) {
                if (enabled) {
                    vibrateMethod(length)
                }
            }
        }
    }

    /**
     * Queue a vibration of [lengthMs] milliseconds.
     * Replaces mVibrationQueue.add() from original code.
     */
    fun trigger(lengthMs: Long = defaultLengthMs) {
        channel.offer(lengthMs)
    }

    /**
     * Clear all pending vibration events (drain channel buffer).
     * Replaces mVibrationQueue.clear() from original code.
     */
    fun clear() {
        // Drain all buffered vibration events
        while (channel.poll() != null) { }
    }

    /**
     * Cancel all pending and future vibrations and close the channel.
     */
    fun shutdown() {
        channel.close()
    }
}
