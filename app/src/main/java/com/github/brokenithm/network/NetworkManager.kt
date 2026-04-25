package com.github.brokenithm.network

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import com.github.brokenithm.util.AsyncTaskUtil
import java.net.*
import java.nio.ByteBuffer
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Shared mutable state for input data consumed by the sender loop.
 * Set by TouchController / SensorController / UI button handlers.
 */
class InputState {
    @Volatile var buttons: Long = 0L
    @Volatile var airHeight: Int = 6
    @Volatile var testButton: Boolean = false
    @Volatile var serviceButton: Boolean = false
}

/**
 * Shared mutable state for NFC card data consumed by the sender loop.
 * Set by NFCManager.
 */
class CardState {
    @Volatile var hasCard: Boolean = false
    @Volatile var cardType: CardType = CardType.CARD_AIME
    val cardId = ByteArray(10)
}

enum class CardType {
    CARD_AIME, CARD_FELICA
}

enum class FunctionButton {
    UNDEFINED, FUNCTION_COIN, FUNCTION_CARD
}

data class InputEvent(
    val keys: Long = 0L,
    val airHeight: Int = 6,
    val testButton: Boolean = false,
    val serviceButton: Boolean = false
)

class IoBuffer {
    var length: Int = 0
    var header = ByteArray(3)
    var air = ByteArray(6)
    var slider = ByteArray(32)
    var testBtn = false
    var serviceBtn = false
}

/**
 * Manages all network communication: UDP/TCP socket lifecycle, packet construction,
 * packet sending/receiving, ping management, and connection state.
 *
 * Wire-format compatibility guarantee: every byte sent on the wire is IDENTICAL
 * to the original MainActivity code.
 */
class NetworkManager(
    private val lifecycleScope: LifecycleCoroutineScope,
    val inputState: InputState,
    val cardState: CardState,
    private val onLEDData: (ByteArray) -> Unit,
    private val onDelayUpdate: (Float) -> Unit,
    private val onDelayDisplay: (Float) -> Unit
) {
    private lateinit var senderTask: AsyncTaskUtil.AsyncTask<InetSocketAddress?, Unit, Unit>
    private lateinit var receiverTask: AsyncTaskUtil.AsyncTask<InetSocketAddress?, Unit, Unit>
    private lateinit var pingPongTask: AsyncTaskUtil.AsyncTask<Unit, Unit, Unit>

    // TCP
    @Volatile var tcpMode = false
    private lateinit var mTCPSocket: Socket
    private val mTCPSocketLock = Any()

    // Packet buffers — reused to avoid per-frame allocation (identical to original)
    private val mSendBuffer = ByteArray(48)
    private val mCardBuffer = ByteArray(24)
    private val mPingBuffer = ByteArray(12)
    private val mIoBuffer = IoBuffer()

    // Packet tracking
    private var currentPacketId = 1
    private var lastPingTime = 0L
    private val pingInterval = 100L
    var showDelay = false
    var enableAir = true
    var enableNFC = true
    @Volatile var exitFlag = true
    var currentDelay = 0f

    // Air encoding constants / state
    var airIdx: List<Int> = listOf(4, 5, 2, 3, 0, 1)
    private var mLastAirHeight = 6
    private var mLastAirUpdateTime = 0L
    private val airUpdateInterval = 10L

    /** Provide getString from Activity for delay display. */
    var getStringProvider: ((Int) -> String)? = null

    // ──────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────

    fun init() {
        initTasks()
    }

    fun start(address: InetSocketAddress) {
        if (!tcpMode) sendConnect(address)
        currentPacketId = 1
        senderTask.execute(lifecycleScope, address)
        receiverTask.execute(lifecycleScope, address)
        pingPongTask.execute(lifecycleScope)
    }

    fun stop(address: InetSocketAddress?) {
        sendDisconnect(address)
        exitFlag = true
        senderTask.cancel()
        receiverTask.cancel()
        pingPongTask.cancel()
    }

    fun sendFunctionKey(address: InetSocketAddress?, function: FunctionButton) {
        address ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val buffer = byteArrayOf(4, 'F'.byte(), 'N'.byte(), 'C'.byte(), function.ordinal.toByte())
            if (tcpMode) {
                try {
                    tcpWrite(buffer)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send function key", e)
                }
            } else {
                try {
                    val socket = DatagramSocket()
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.apply {
                        connect(address)
                        send(packet)
                        close()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send function key", e)
                }
            }
        }
    }

    fun sendConnect(address: InetSocketAddress?) {
        address ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val selfAddress = getLocalIPAddress()
            if (selfAddress.isEmpty()) return@launch
            val buffer = ByteArray(21)
            byteArrayOf('C'.byte(), 'O'.byte(), 'N'.byte()).copyInto(buffer, 1)
            buffer[4] = if (selfAddress.size == 4) 1.toByte() else 2.toByte()
            buffer.putShort(5, 52468.toShort())
            selfAddress.copyInto(buffer, 7)
            buffer[0] = (3 + 1 + 2 + selfAddress.size).toByte()
            try {
                val socket = DatagramSocket()
                val packet = DatagramPacket(buffer, buffer.size)
                socket.apply {
                    connect(address)
                    send(packet)
                    close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send connect packet", e)
            }
        }
    }

    // ──────────────────────────────────────────────
    // Internal: task loop creation
    // ──────────────────────────────────────────────

    private fun initTasks() {
        receiverTask = AsyncTaskUtil.AsyncTask.make(
            doInBackground = {
                val address = it[0] ?: return@make
                if (tcpMode) {
                    val buffer = ByteArray(256)
                    while (!exitFlag) {
                        if (!this::mTCPSocket.isInitialized || !mTCPSocket.isConnected || mTCPSocket.isClosed) {
                            Thread.sleep(50)
                            continue
                        }
                        try {
                            val dataSize = mTCPSocket.getInputStream().read(buffer, 0, 256)
                            if (dataSize >= 3) {
                                if (dataSize >= 100 && buffer[1] == 'L'.byte() && buffer[2] == 'E'.byte() && buffer[3] == 'D'.byte()) {
                                    onLEDData(buffer)
                                }
                                if (dataSize >= 4 && buffer[1] == 'P'.byte() && buffer[2] == 'O'.byte() && buffer[3] == 'N'.byte()) {
                                    val delay = calculateDelay(buffer)
                                    if (delay > 0f)
                                        currentDelay = delay
                                    onDelayUpdate(delay)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "TCP receive error", e)
                        }
                    }
                } else {
                    val socket = try {
                        DatagramSocket().apply {
                            reuseAddress = true
                            soTimeout = 1000
                        }
                    } catch (e: BindException) {
                        Log.e(TAG, "UDP bind error", e)
                        return@make
                    }
                    val buffer = ByteArray(256)
                    val packet = DatagramPacket(buffer, buffer.size)
                    while (!exitFlag) {
                        try {
                            socket.receive(packet)
                            if (packet.address.hostAddress == address.toHostString() && packet.port == address.port) {
                                val data = packet.data
                                if (data.size >= 3) {
                                    if (data.size >= 100 && data[1] == 'L'.byte() && data[2] == 'E'.byte() && data[3] == 'D'.byte()) {
                                        onLEDData(data)
                                    }
                                    if (data.size >= 4 && data[1] == 'P'.byte() && data[2] == 'O'.byte() && data[3] == 'N'.byte()) {
                                        val delay = calculateDelay(data)
                                        if (delay > 0f)
                                            currentDelay = delay
                                        onDelayUpdate(delay)
                                    }
                                }
                            }
                        } catch (e: SocketTimeoutException) {
                            // ignore, try again
                        }
                    }
                    socket.close()
                }
            }
        )

        senderTask = AsyncTaskUtil.AsyncTask.make(
            doInBackground = {
                val address = it[0] ?: return@make
                val intervalNs = 1_000_000L // 1ms in nanos
                if (tcpMode) {
                    try {
                        mTCPSocket = Socket().apply {
                            tcpNoDelay = true
                        }
                        mTCPSocket.connect(address)
                    } catch (e: Exception) {
                        Log.e(TAG, "TCP connect error", e)
                        return@make
                    }
                    var nextTime = System.nanoTime()
                    while (!exitFlag) {
                        if (showDelay)
                            sendTCPPing()
                        val buttons = InputEvent(inputState.buttons, inputState.airHeight, inputState.testButton, inputState.serviceButton)
                        val buffer = applyKeys(buttons, mIoBuffer)
                        try {
                            val dataBuf = constructBuffer(buffer)
                            tcpWrite(dataBuf, buffer.length + 1)
                            if (enableNFC) {
                                val cardBuf = constructCardData()
                                tcpWrite(cardBuf, cardBuf[0].toInt() + 1)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "TCP send error", e)
                            continue
                        }
                        nextTime += intervalNs
                        val now = System.nanoTime()
                        if (nextTime > now) {
                            java.util.concurrent.locks.LockSupport.parkNanos(nextTime - now)
                        }
                    }
                } else {
                    val socket = try {
                        DatagramSocket().apply {
                            reuseAddress = true
                            soTimeout = 1000
                        }
                    } catch (e: BindException) {
                        Log.e(TAG, "UDP bind error", e)
                        return@make
                    }
                    try {
                        socket.connect(address)
                    } catch (e: Exception) {
                        Log.e(TAG, "UDP connect error", e)
                        return@make
                    }
                    val packet = DatagramPacket(mSendBuffer, mSendBuffer.size)
                    val cardPacket = DatagramPacket(mCardBuffer, mCardBuffer.size)
                    var nextTime = System.nanoTime()
                    while (!exitFlag) {
                        if (showDelay)
                            sendPing(socket, address)
                        val buttons = InputEvent(inputState.buttons, inputState.airHeight, inputState.testButton, inputState.serviceButton)
                        val buffer = applyKeys(buttons, mIoBuffer)
                        val dataBuf = constructBuffer(buffer)
                        packet.data = dataBuf
                        packet.length = buffer.length + 1
                        try {
                            socket.send(packet)
                            if (enableNFC) {
                                val cardBuf = constructCardData()
                                cardPacket.data = cardBuf
                                cardPacket.length = cardBuf[0].toInt() + 1
                                socket.send(cardPacket)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "UDP send error", e)
                            Thread.sleep(100)
                            continue
                        }
                        nextTime += intervalNs
                        val now = System.nanoTime()
                        if (nextTime > now) {
                            java.util.concurrent.locks.LockSupport.parkNanos(nextTime - now)
                        }
                    }
                    socket.close()
                }
            }
        )

        pingPongTask = AsyncTaskUtil.AsyncTask.make(
            doInBackground = {
                while (!exitFlag) {
                    if (!showDelay) {
                        Thread.sleep(250)
                        continue
                    }
                    if (currentDelay >= 0f) {
                        onDelayDisplay(currentDelay)
                    }
                    Thread.sleep(200)
                }
            }
        )
    }

    // ──────────────────────────────────────────────
    // Internal: send helpers
    // ──────────────────────────────────────────────

    private fun sendDisconnect(address: InetSocketAddress?) {
        address ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val buffer = byteArrayOf(3, 'D'.byte(), 'I'.byte(), 'S'.byte())
            if (tcpMode) {
                try {
                    tcpWrite(buffer)
                    synchronized(mTCPSocketLock) {
                        if (this@NetworkManager::mTCPSocket.isInitialized && !mTCPSocket.isClosed) {
                            mTCPSocket.close()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send disconnect packet", e)
                }
            } else {
                try {
                    val socket = DatagramSocket()
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.apply {
                        connect(address)
                        send(packet)
                        close()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send disconnect packet", e)
                }
            }
        }
    }

    private fun sendPing(socket: DatagramSocket, address: InetSocketAddress?) {
        address ?: return
        if (System.currentTimeMillis() - lastPingTime < pingInterval) return
        lastPingTime = System.currentTimeMillis()
        mPingBuffer[0] = 11
        mPingBuffer[1] = 'P'.byte()
        mPingBuffer[2] = 'I'.byte()
        mPingBuffer[3] = 'N'.byte()
        mPingBuffer.putLong(4, SystemClock.elapsedRealtimeNanos())
        try {
            val packet = DatagramPacket(mPingBuffer, mPingBuffer.size)
            socket.send(packet)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ping", e)
        }
    }

    private fun sendTCPPing() {
        if (System.currentTimeMillis() - lastPingTime < pingInterval) return
        lastPingTime = System.currentTimeMillis()
        mPingBuffer[0] = 11
        mPingBuffer[1] = 'P'.byte()
        mPingBuffer[2] = 'I'.byte()
        mPingBuffer[3] = 'N'.byte()
        mPingBuffer.putLong(4, SystemClock.elapsedRealtimeNanos())
        try {
            tcpWrite(mPingBuffer)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send TCP ping", e)
        }
    }

    // ──────────────────────────────────────────────
    // Internal: TCP write with lock
    // ──────────────────────────────────────────────

    private fun tcpWrite(data: ByteArray, length: Int = data.size) {
        synchronized(mTCPSocketLock) {
            if (this::mTCPSocket.isInitialized && mTCPSocket.isConnected && !mTCPSocket.isClosed) {
                mTCPSocket.getOutputStream().write(data, 0, length)
            }
        }
    }

    // ──────────────────────────────────────────────
    // Packet construction — byte-for-byte identical to original
    // ──────────────────────────────────────────────

    private fun constructBuffer(buffer: IoBuffer): ByteArray {
        mSendBuffer[0] = buffer.length.toByte()
        buffer.header.copyInto(mSendBuffer, 1)
        ByteBuffer.wrap(mSendBuffer).putInt(4, currentPacketId++)
        if (enableAir) {
            buffer.air.copyInto(mSendBuffer, 8)
            buffer.slider.copyInto(mSendBuffer, 14)
            mSendBuffer[46] = if (buffer.testBtn) 0x01 else 0x00
            mSendBuffer[47] = if (buffer.serviceBtn) 0x01 else 0x00
        } else {
            buffer.slider.copyInto(mSendBuffer, 8)
            mSendBuffer[40] = if (buffer.testBtn) 0x01 else 0x00
            mSendBuffer[41] = if (buffer.serviceBtn) 0x01 else 0x00
        }
        return mSendBuffer
    }

    private fun constructPacket(buffer: IoBuffer): DatagramPacket {
        val realBuf = constructBuffer(buffer)
        return DatagramPacket(realBuf, buffer.length + 1)
    }

    private fun constructCardData(): ByteArray {
        mCardBuffer.fill(0)
        mCardBuffer[0] = 15
        mCardBuffer[1] = 'C'.byte()
        mCardBuffer[2] = 'R'.byte()
        mCardBuffer[3] = 'D'.byte()
        mCardBuffer[4] = if (cardState.hasCard) 1 else 0
        mCardBuffer[5] = cardState.cardType.ordinal.toByte()
        if (cardState.hasCard)
            cardState.cardId.copyInto(mCardBuffer, 6)
        return mCardBuffer
    }

    @Suppress("unused")
    private fun constructCardPacket(): DatagramPacket {
        val buf = constructCardData()
        return DatagramPacket(buf, buf[0].toInt() + 1)
    }

    // ──────────────────────────────────────────────
    // Key/air encoding — byte-for-byte identical to original
    // ──────────────────────────────────────────────

    private fun applyKeys(event: InputEvent, buffer: IoBuffer): IoBuffer {
        return buffer.apply {
            slider.fill(0)
            air.fill(0)

            if (enableAir) {
                buffer.length = 47
                buffer.header[0] = 'I'.byte()
                buffer.header[1] = 'N'.byte()
                buffer.header[2] = 'P'.byte()
            } else {
                buffer.length = 41
                buffer.header[0] = 'I'.byte()
                buffer.header[1] = 'P'.byte()
                buffer.header[2] = 'T'.byte()
            }

            if (event.keys != 0L) {
                for (i in 0 until 32) {
                    buffer.slider[31 - i] = if (event.keys and (1L shl i) != 0L) 0x80.toByte() else 0x0
                }
            }

            if (enableAir) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - mLastAirUpdateTime > airUpdateInterval) {
                    mLastAirHeight += if (mLastAirHeight < event.airHeight) 1 else if (mLastAirHeight > event.airHeight) -1 else 0
                    mLastAirUpdateTime = currentTime
                }
                if (mLastAirHeight != 6) {
                    for (i in mLastAirHeight..5) {
                        buffer.air[airIdx[i]] = 1
                    }
                }
            }

            buffer.serviceBtn = event.serviceButton
            buffer.testBtn = event.testButton
        }
    }

    // ──────────────────────────────────────────────
    // Utility methods — byte-for-byte identical to original
    // ──────────────────────────────────────────────

    private fun getLocalIPAddress(useIPv4: Boolean = true): ByteArray {
        try {
            val interfaces: List<NetworkInterface> = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs: List<InetAddress> = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.address
                        if (useIPv4) {
                            if (addr is Inet4Address) return sAddr
                        } else {
                            if (addr is Inet6Address) return sAddr
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }
        return byteArrayOf()
    }

    private fun calculateDelay(data: ByteArray): Float {
        val currentTime = SystemClock.elapsedRealtimeNanos()
        val lastPingTime = data.getLong(4)
        return (currentTime - lastPingTime) / 2000000.0f
    }

    private fun ByteArray.putLong(offset: Int, value: Long) {
        this[offset] = (value ushr 56).toByte()
        this[offset + 1] = (value ushr 48).toByte()
        this[offset + 2] = (value ushr 40).toByte()
        this[offset + 3] = (value ushr 32).toByte()
        this[offset + 4] = (value ushr 24).toByte()
        this[offset + 5] = (value ushr 16).toByte()
        this[offset + 6] = (value ushr 8).toByte()
        this[offset + 7] = value.toByte()
    }

    private fun ByteArray.getLong(offset: Int): Long {
        return (this[offset].toLong() and 0xFF shl 56) or
                (this[offset + 1].toLong() and 0xFF shl 48) or
                (this[offset + 2].toLong() and 0xFF shl 40) or
                (this[offset + 3].toLong() and 0xFF shl 32) or
                (this[offset + 4].toLong() and 0xFF shl 24) or
                (this[offset + 5].toLong() and 0xFF shl 16) or
                (this[offset + 6].toLong() and 0xFF shl 8) or
                (this[offset + 7].toLong() and 0xFF)
    }

    private fun ByteArray.putShort(offset: Int, value: Short) {
        this[offset] = (value.toInt() ushr 8).toByte()
        this[offset + 1] = value.toByte()
    }

    private fun InetSocketAddress.toHostString(): String? {
        if (hostName != null)
            return hostName
        if (this.address != null)
            return this.address.hostName ?: this.address.hostAddress
        return null
    }

    // ──────────────────────────────────────────────
    // Char-to-byte extension — used extensively in packet construction
    // ──────────────────────────────────────────────

    private fun Char.byte() = code.toByte()

    companion object {
        private const val TAG = "Brokenithm"
    }
}
