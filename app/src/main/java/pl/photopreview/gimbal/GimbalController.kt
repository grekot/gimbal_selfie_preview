package pl.photopreview.gimbal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Drives the Aochuan Smart X3 gimbal motors over BLE, using the protocol reverse-engineered from
 * the official app's HCI snoop log.
 *
 * Frame: a5 5a 03 01 <cmd16> <len16 BE> <payload> <flag> <checksum=sum(payload)&0xff>.
 * Control = cmd 40 26, payload `02 0b 00 <panLE16> <tiltLE16>` (signed int16 speeds).
 * On connect we enable notifications (CCCD), send the init triplet, then a ~1 Hz heartbeat;
 * movement frames are resent ~every 80 ms while a direction is held.
 *
 * NOTE: only one BLE central can hold the gimbal — the official Aochuan app must be closed.
 */
@SuppressLint("MissingPermission")
class GimbalController(private val context: Context) {

    /** Human-readable connection status (Polish), pushed to the UI. */
    var onState: ((String) -> Unit)? = null

    /** Diagnostic dump of discovered services/characteristics. */
    var onInfo: ((String) -> Unit)? = null

    /** Latest telemetry notification as hex (for protocol probing). */
    var onTelemetry: ((String) -> Unit)? = null

    /** Gimbal yaw (heading) in degrees, parsed from telemetry — used for the closed-loop 180° flip. */
    @Volatile var yawDeg: Float = 0f

    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null

    private val thread = HandlerThread("gimbal-ble").apply { start() }
    private val h = Handler(thread.looper)

    // Phone gyroscope: measures the physical rotation during a flip (fast, unlike the ~1 Hz BLE telemetry).
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    @Volatile private var gravX = 0f
    @Volatile private var gravY = 0f
    @Volatile private var gravZ = 9.81f
    @Volatile private var flipping = false
    @Volatile private var flipAccumDeg = 0f
    private var lastGyroNs = 0L
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                    gravX = e.values[0]; gravY = e.values[1]; gravZ = e.values[2]
                }
                Sensor.TYPE_GYROSCOPE -> {
                    if (!flipping) { lastGyroNs = e.timestamp; return }
                    if (lastGyroNs != 0L) {
                        val dt = (e.timestamp - lastGyroNs) / 1e9f
                        val gn = sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ)
                        if (gn > 0.1f && dt > 0f && dt < 0.5f) {
                            // Angular-velocity component around the gravity (world-vertical) axis = yaw rate.
                            val yawRate = (e.values[0] * gravX + e.values[1] * gravY + e.values[2] * gravZ) / gn
                            flipAccumDeg += Math.toDegrees((yawRate * dt).toDouble()).toFloat()
                        }
                    }
                    lastGyroNs = e.timestamp
                }
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    @Volatile private var ready = false
    @Volatile private var moving = false
    @Volatile private var curPan = 0
    @Volatile private var curTilt = 0
    @Volatile private var curRoll = 0
    @Volatile private var lastCmdAt = 0L

    private fun state(s: String) { onState?.invoke(s) }

    fun connect(mac: String) {
        disconnect()
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = mgr?.adapter
        if (adapter == null || !adapter.isEnabled) { state("Włącz Bluetooth i spróbuj ponownie"); return }
        val dev: BluetoothDevice = try {
            adapter.getRemoteDevice(mac.trim().uppercase())
        } catch (e: Exception) { state("Nieprawidłowy adres MAC: $mac"); return }
        state("Łączę z $mac …")
        gatt = dev.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        val g = gatt
        val c = writeChar
        val wasReady = ready
        ready = false; moving = false
        flipping = false
        runCatching { sensorManager?.unregisterListener(sensorListener) }
        h.removeCallbacksAndMessages(null)
        gatt = null; writeChar = null; notifyChar = null
        // Hand control back to the gimbal's own remote (release "L" mode), then drop the link.
        h.post {
            if (wasReady && g != null && c != null) {
                try {
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeCharacteristic(c, RELEASE, WRITE_TYPE_NO_RESPONSE)
                    } else {
                        @Suppress("DEPRECATION")
                        run {
                            c.writeType = WRITE_TYPE_NO_RESPONSE
                            c.value = RELEASE
                            g.writeCharacteristic(c)
                        }
                    }
                } catch (_: Exception) {}
            }
            try { g?.disconnect(); g?.close() } catch (_: Exception) {}
        }
    }

    /** True if a GATT connection exists (connected or connecting). */
    fun active(): Boolean = gatt != null

    /** Release the background thread. Call when the screen goes away. */
    fun close() {
        disconnect()
        thread.quitSafely()
    }

    /** Begin moving at the given signed pan/tilt speed (resent until [stopMove] or watchdog). */
    fun startMove(pan: Int, tilt: Int) {
        curPan = pan; curTilt = tilt; curRoll = 0; moving = true
        lastCmdAt = SystemClock.elapsedRealtime()
    }

    /** Begin rolling the phone (orientation toward portrait/landscape) at the given signed speed. */
    fun startRoll(roll: Int) {
        curRoll = roll; curPan = 0; curTilt = 0; moving = true
        lastCmdAt = SystemClock.elapsedRealtime()
    }

    fun stopMove() {
        moving = false; curPan = 0; curTilt = 0; curRoll = 0
        sendRaw(STOP); sendRaw(ROLL_STOP)
    }

    /**
     * ~180° pan flip — toggles the camera between facing you and away. Uses the phone's gyroscope
     * (high-rate) to measure the actual rotation and stop at ~180°; falls back to timing without one.
     */
    fun flipPan() {
        if (!ready) return
        val sm = sensorManager
        if (sm == null || gyro == null) {
            timedFlip()
            return
        }
        flipAccumDeg = 0f
        lastGyroNs = 0L
        flipping = true
        sm.registerListener(sensorListener, gyro, SensorManager.SENSOR_DELAY_GAME)
        gravitySensor?.let { sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME) }
        val deadline = SystemClock.elapsedRealtime() + 8000L // safety if the gyro stalls
        // Single control thread (h) reads the gyro-integrated angle and decides speed / stop — no race.
        val loop = object : Runnable {
            override fun run() {
                val moved = abs(flipAccumDeg)
                if (!flipping || !ready || moved >= FLIP_STOP_DEG || SystemClock.elapsedRealtime() > deadline) {
                    flipping = false
                    stopMove()
                    sm.unregisterListener(sensorListener)
                    return
                }
                // Slow down before the target so motor coast / detection latency don't overshoot 180°.
                startMove(if (moved < FLIP_SLOW_DEG) FLIP_FAST else FLIP_SLOW, 0)
                h.postDelayed(this, 40)
            }
        }
        // Brief delay so the gravity vector settles (phone still) before we integrate the rotation.
        h.postDelayed(loop, 120)
    }

    private fun timedFlip() {
        val end = SystemClock.elapsedRealtime() + FLIP_MS
        val r = object : Runnable {
            override fun run() {
                if (ready && SystemClock.elapsedRealtime() < end) {
                    startMove(FLIP_FAST, 0)
                    h.postDelayed(this, 80)
                } else {
                    stopMove()
                }
            }
        }
        h.post(r)
    }

    private fun parseTelemetry(b: ByteArray) {
        // Telemetry frame: a5 5a 01 03 80 0f <len16 BE> <30-byte status>. Yaw = 4th int16, units 0.1°.
        if (b.size >= 16 && b[0] == 0xa5.toByte() && b[1] == 0x5a.toByte() &&
            b[4] == 0x80.toByte() && b[5] == 0x0f.toByte()
        ) {
            val o = 8 + 6
            if (b.size >= o + 2) {
                val lo = b[o].toInt() and 0xff
                val hi = b[o + 1].toInt() and 0xff
                yawDeg = ((hi shl 8) or lo).toShort().toInt() / 10f
            }
        }
    }

    /**
     * Send an arbitrary command frame (protocol probing):
     * a5 5a 03 01 <cmd16> <len16 BE> <payload> <flag> <checksum=sum(payload)&0xff>.
     */
    fun sendCommand(cmd: Int, payload: ByteArray, flag: Int = 0) {
        var ck = 0
        for (b in payload) ck += b.toInt() and 0xff
        val frame = byteArrayOf(
            0xa5.toByte(), 0x5a, 0x03, 0x01,
            ((cmd shr 8) and 0xff).toByte(), (cmd and 0xff).toByte(),
            ((payload.size shr 8) and 0xff).toByte(), (payload.size and 0xff).toByte(),
        ) + payload + byteArrayOf(flag.toByte(), (ck and 0xff).toByte())
        sendRaw(frame)
    }

    private val cb = object : BluetoothGattCallback() {
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            parseTelemetry(value)
            onTelemetry?.invoke(value.joinToString("") { "%02x".format(it) })
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            parseTelemetry(v)
            onTelemetry?.invoke(v.joinToString("") { "%02x".format(it) })
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    state("Połączono — szukam usług…")
                    h.post { try { g.discoverServices() } catch (_: Exception) {} }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    ready = false
                    state("Rozłączono (status $status)")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            var wNoResp: BluetoothGattCharacteristic? = null
            var wAny: BluetoothGattCharacteristic? = null
            var notify: BluetoothGattCharacteristic? = null
            val sb = StringBuilder()
            for (svc in g.services) {
                for (c in svc.characteristics) {
                    val p = c.properties
                    if (p and PROPERTY_WRITE_NO_RESPONSE != 0 && wNoResp == null) wNoResp = c
                    if (p and PROPERTY_WRITE != 0 && wAny == null) wAny = c
                    if (p and PROPERTY_NOTIFY != 0 && notify == null) notify = c
                    if (p and (PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE or PROPERTY_NOTIFY) != 0) {
                        sb.append("• svc ${short(svc.uuid)}  char ${short(c.uuid)}  p=0x%02x\n".format(p))
                    }
                }
            }
            writeChar = wNoResp ?: wAny
            notifyChar = notify
            onInfo?.invoke(sb.toString().ifEmpty { "(brak charakterystyk)" })

            val wc = writeChar
            if (wc == null) { state("Nie znaleziono zapisywalnej charakterystyki"); return }
            notify?.let { nc ->
                try {
                    g.setCharacteristicNotification(nc, true)
                    nc.getDescriptor(cccd)?.let { writeDesc(g, it, byteArrayOf(0x01, 0x00)) }
                } catch (_: Exception) {}
            }
            // Let the CCCD write settle, then init + heartbeat.
            h.postDelayed({
                sendRaw(INIT1); sendRaw(INIT2); sendRaw(INIT3)
                ready = true
                state("Gotowe ✓ — steruj przyciskami")
                h.post(heartbeat)
                h.post(moveLoop)
            }, 350)
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!ready) return
            sendRaw(HEARTBEAT)
            h.postDelayed(this, 1000)
        }
    }

    private val moveLoop = object : Runnable {
        override fun run() {
            if (!ready) return
            if (moving) {
                // Watchdog: if commands stop arriving (button released or Wi-Fi dropped), halt.
                if (SystemClock.elapsedRealtime() - lastCmdAt > 600) {
                    moving = false; curPan = 0; curTilt = 0; curRoll = 0
                    sendRaw(STOP); sendRaw(ROLL_STOP)
                } else if (curRoll != 0) {
                    sendRaw(rollFrame(curRoll))
                } else {
                    sendRaw(motorFrame(curPan, curTilt))
                }
            }
            h.postDelayed(this, 80)
        }
    }

    private fun sendRaw(bytes: ByteArray) {
        val g = gatt ?: return
        val c = writeChar ?: return
        h.post {
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeCharacteristic(c, bytes, WRITE_TYPE_NO_RESPONSE)
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        c.writeType = WRITE_TYPE_NO_RESPONSE
                        c.value = bytes
                        g.writeCharacteristic(c)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun writeDesc(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, value)
            } else {
                @Suppress("DEPRECATION")
                run { d.value = value; g.writeDescriptor(d) }
            }
        } catch (_: Exception) {}
    }

    private fun motorFrame(pan: Int, tilt: Int): ByteArray {
        val payload = byteArrayOf(
            0x02, 0x0b, 0x00,
            (pan and 0xff).toByte(), ((pan shr 8) and 0xff).toByte(),
            (tilt and 0xff).toByte(), ((tilt shr 8) and 0xff).toByte(),
        )
        var ck = 0
        for (b in payload) ck += b.toInt() and 0xff
        val flag = (if (pan < 0) 1 else 0) + (if (tilt < 0) 1 else 0)
        return byteArrayOf(0xa5.toByte(), 0x5a, 0x03, 0x01, 0x40, 0x26, 0x00, 0x07) +
            payload + byteArrayOf(flag.toByte(), (ck and 0xff).toByte())
    }

    /** Roll-axis (orientation) command: cmd 40 16, single signed int16 speed. */
    private fun rollFrame(roll: Int): ByteArray {
        val payload = byteArrayOf((roll and 0xff).toByte(), ((roll shr 8) and 0xff).toByte())
        var ck = 0
        for (b in payload) ck += b.toInt() and 0xff
        val flag = if (roll < 0) 1 else 0
        return byteArrayOf(0xa5.toByte(), 0x5a, 0x03, 0x01, 0x40, 0x16, 0x00, 0x02) +
            payload + byteArrayOf(flag.toByte(), (ck and 0xff).toByte())
    }

    private fun short(u: UUID): String {
        val s = u.toString()
        return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) s.substring(4, 8) else s
    }

    companion object {
        const val DEFAULT_MAC = "CA:03:49:48:47:70"

        private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        private val HEARTBEAT = hex("a55a0301800f0001000000")
        private val INIT1 = hex("a55a0301801c00000000")
        private val INIT2 = hex("a55a0304801d00000000")
        private val INIT3 = hex("a55a0304801800000000")
        private val STOP = hex("a55a030140260007000000000000000000")
        private val ROLL_STOP = hex("a55a03014016000200000000")
        // payload[0]=01 = "release / idle" — what the official app sends when it stops controlling.
        private val RELEASE = hex("a55a030140260007010000000000000001")
        private const val FLIP_FAST = 350 // pan speed for the bulk of the flip
        private const val FLIP_SLOW = 70 // slow final approach so motor coast doesn't overshoot 180°
        private const val FLIP_SLOW_DEG = 130f // start slowing once this much has been turned
        private const val FLIP_STOP_DEG = 176f // stop here; small coast lands it ≈ 180°
        private const val FLIP_MS = 1100L // fallback timing only if the phone has no gyroscope
    }
}
