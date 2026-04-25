package com.github.brokenithm.nfc

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.github.brokenithm.network.CardState
import com.github.brokenithm.network.CardType
import com.github.brokenithm.util.FeliCa
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Extracted from MainActivity lines 156-238, 635-656.
 * Manages NFC card detection and reading (Aime via MifareClassic, FeliCa via NfcF).
 *
 * Byte-for-byte compatible: IDENTICAL card detection, ID extraction, and authentication flow.
 */
class NFCManager(
    private val activity: Activity,
    private val cardState: CardState,
    private val scope: CoroutineScope
) {
    private var adapter: NfcAdapter? = null
    var enabled = true

    private val mAimeKey = byteArrayOf(0x57, 0x43, 0x43, 0x46, 0x76, 0x32)
    private val mBanaKey = byteArrayOf(0x60, -0x70, -0x30, 0x06, 0x32, -0x0b)

    init {
        val nfcManager = activity.getSystemService(Activity.NFC_SERVICE) as NfcManager
        adapter = nfcManager.defaultAdapter
    }

    /**
     * Enable NFC foreground dispatch.
     * Call from Activity.onResume().
     */
    fun enable() {
        if (!enabled) return
        try {
            val intent = Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
            val nfcPendingIntent = PendingIntent.getActivity(activity, 0, intent, pendingIntentFlags)
            adapter?.enableForegroundDispatch(activity, nfcPendingIntent, null, null)
        } catch (ex: IllegalStateException) {
            Log.e(TAG, "Error enabling NFC foreground dispatch", ex)
        }
    }

    /**
     * Disable NFC foreground dispatch.
     * Call from Activity.onPause().
     */
    fun disable() {
        try {
            adapter?.disableForegroundDispatch(activity)
        } catch (ex: IllegalStateException) {
            Log.e(TAG, "Error disabling NFC foreground dispatch", ex)
        }
    }

    /**
     * Handle NFC intent. Call from Activity.onNewIntent().
     * Card detection and ID extraction — byte-for-byte identical to original (lines 193-238).
     */
    fun handleIntent(intent: Intent) {
        val tag: Tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG) ?: return

        // Try FeliCa first
        val felica = FeliCa.get(tag)
        if (felica != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    felica.connect()
                    felica.poll()
                    felica.IDm?.copyInto(cardState.cardId)
                        ?: throw IllegalStateException("Failed to fetch IDm from FeliCa")
                    cardState.cardId[8] = 0
                    cardState.cardId[9] = 0
                    cardState.cardType = CardType.CARD_FELICA
                    cardState.hasCard = true
                    Log.d(TAG, "Found FeliCa card: ${cardState.cardId.toHexString().removeRange(16..19)}")
                    while (felica.isConnected) Thread.sleep(50)
                    cardState.hasCard = false
                    felica.close()
                } catch (e: Exception) {
                    Log.e(TAG, "FeliCa card read error", e)
                }
            }
            return
        }

        // Try Mifare Classic (Aime)
        val mifare = MifareClassic.get(tag) ?: return
        scope.launch(Dispatchers.IO) {
            try {
                mifare.connect()
                if (mifare.authenticateBlock(2, keyA = mAimeKey, keyB = mAimeKey) ||
                    mifare.authenticateBlock(2, keyA = mBanaKey, keyB = mAimeKey)
                ) {
                    Thread.sleep(100)
                    val block = mifare.readBlock(2)
                    block.copyInto(cardState.cardId, 0, 6, 16)
                    cardState.cardType = CardType.CARD_AIME
                    cardState.hasCard = true
                    Log.d(TAG, "Found Aime card: ${cardState.cardId.toHexString()}")
                    while (mifare.isConnected) Thread.sleep(50)
                    cardState.hasCard = false
                } else {
                    Log.d(TAG, "NFC auth failed")
                }
                mifare.close()
            } catch (e: Exception) {
                Log.e(TAG, "Aime card read error", e)
            }
        }
    }

    companion object {
        private const val TAG = "Brokenithm"

        // ──────────────────────────────────────────────
        // Extension methods — byte-for-byte identical to original
        // ──────────────────────────────────────────────

        private fun Byte.getBit(bit: Int) = (toInt() ushr bit) and 0x1

        private fun MifareClassic.authenticateBlock(
            blockIndex: Int,
            keyA: ByteArray,
            keyB: ByteArray,
            write: Boolean = false
        ): Boolean {
            val sectorIndex = blockToSector(blockIndex)
            val accessBitsBlock = sectorToBlock(sectorIndex) + 3
            if (!authenticateSectorWithKeyA(sectorIndex, keyA)) return false
            val accessBits = readBlock(accessBitsBlock)
            val targetBit = blockIndex % 4
            val bitC1 = accessBits[7].getBit(targetBit + 4)
            val bitC2 = accessBits[8].getBit(targetBit)
            val bitC3 = accessBits[8].getBit(targetBit + 4)
            val allBits = (bitC1 shl 2) or (bitC2 shl 1) or bitC3
            return if (write) {
                when (allBits) {
                    0 -> true
                    3, 4, 6 -> authenticateSectorWithKeyB(sectorIndex, keyB)
                    else -> false
                }
            } else {
                when (allBits) {
                    7 -> false
                    3, 5 -> authenticateSectorWithKeyB(sectorIndex, keyB)
                    else -> true
                }
            }
        }

        private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
    }
}
