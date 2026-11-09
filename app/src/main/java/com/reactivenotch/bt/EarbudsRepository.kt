package com.reactivenotch.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.reactivenotch.DebugLog
import com.reactivenotch.bt.SonyCommands.sanitized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

data class EarbudsState(
    val name: String,
    val address: String,
    val isSony: Boolean,
    val battery: BatteryInfo = BatteryInfo(),
    val noise: NoiseMode? = null,
    /** 소니 RFCOMM 세션이 실제로 연결돼 응답을 주고받는 중인지 */
    val protocolLive: Boolean = false,
)

/**
 * 현재 연결된 블루투스 오디오 기기를 추적하고, 소니 제품이면 RFCOMM 으로 배터리/노이즈 캔슬을 읽는다.
 * 소니 공식 앱(Headphones Connect)이 이미 RFCOMM 채널을 잡고 있으면 연결이 거부될 수 있다.
 */
class EarbudsRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val noiseControlEnabled: () -> Boolean,
) {
    private val _state = MutableStateFlow<EarbudsState?>(null)
    val state: StateFlow<EarbudsState?> = _state

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var a2dp: BluetoothProfile? = null
    private var currentAddress: String? = null
    private var sessionJob: Job? = null
    private var started = false

    private val commands = Channel<ByteArray>(Channel.UNLIMITED)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refresh()
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            a2dp = proxy
            refresh()
        }

        override fun onServiceDisconnected(profile: Int) {
            a2dp = null
            refresh()
        }
    }

    fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        if (hasPermission()) adapter?.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(receiver) }
        sessionJob?.cancel()
        a2dp?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        a2dp = null
        currentAddress = null
        _state.value = null
    }

    /** 섬을 펼칠 때마다 호출: 권한이 뒤늦게 허용됐을 수 있고, 배터리 값도 새로 읽는다. */
    @SuppressLint("MissingPermission")
    fun refresh() {
        if (!started) return
        if (!hasPermission()) {
            _state.value = null
            return
        }
        if (a2dp == null) {
            adapter?.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
            return
        }
        val devices = a2dp?.connectedDevices.orEmpty()
        val device = devices.firstOrNull { SonyCommands.looksLikeSony(it.name) } ?: devices.firstOrNull()
        if (device == null) {
            sessionJob?.cancel()
            currentAddress = null
            _state.value = null
            return
        }
        if (device.address == currentAddress) {
            if (_state.value?.protocolLive == true) requestBattery()
            return
        }
        currentAddress = device.address
        sessionJob?.cancel()
        val name = device.name ?: "Bluetooth"
        val sony = SonyCommands.looksLikeSony(name)
        _state.value = EarbudsState(
            name = name,
            address = device.address,
            isSony = sony,
            battery = BatteryInfo(single = systemBattery(device)),
        )
        DebugLog.add("bt", "연결된 오디오 기기: $name (소니=$sony)")
        if (sony) sessionJob = scope.launch(Dispatchers.IO) { runSony(device) }
    }

    fun requestBattery() {
        SonyCommands.batteryRequests.forEach { commands.trySend(it) }
    }

    fun setNoise(mode: NoiseMode) {
        if (!noiseControlEnabled() || _state.value?.protocolLive != true) return
        commands.trySend(SonyCommands.setNoiseMode(mode))
        _state.update { it?.copy(noise = mode) }
    }

    /** 숨김 API 라 기기/OS 에 따라 실패할 수 있다. 소니 프로토콜이 안 될 때의 보조 수단. */
    private fun systemBattery(device: BluetoothDevice): Int? = try {
        (BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(device) as? Int)?.takeIf { it in 0..100 }
    } catch (_: Throwable) {
        null
    }

    private suspend fun runSony(device: BluetoothDevice) {
        var attempt = 0
        while (scope.isActive && attempt < 4) {
            attempt++
            try {
                connectAndRead(device)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                DebugLog.add("sony", "연결 실패/끊김(시도 $attempt): ${e.message}")
            } catch (e: SecurityException) {
                DebugLog.add("sony", "블루투스 권한 오류: ${e.message}")
                return
            }
            updateIfCurrent(device) { it.copy(protocolLive = false) }
            delay(15_000)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectAndRead(device: BluetoothDevice) {
        val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(SONY_UUID)
        adapter?.cancelDiscovery()
        try {
            socket.connect()
            DebugLog.add("sony", "RFCOMM 연결됨: ${device.name}")
            updateIfCurrent(device) { it.copy(protocolLive = true) }

            val output = socket.outputStream
            val writeLock = Any()
            val acks = Channel<Int>(Channel.CONFLATED)
            fun write(bytes: ByteArray) = synchronized(writeLock) {
                output.write(bytes)
                output.flush()
            }

            coroutineScope {
                // 취소되면 소켓을 닫아 블로킹 read 를 깨운다.
                launch { try { awaitCancellation() } finally { runCatching { socket.close() } } }

                // 수신: 데이터 프레임은 ACK 로 응답하고 파싱한다.
                launch(Dispatchers.IO) {
                    val decoder = SonyFrame.Decoder()
                    val buf = ByteArray(1024)
                    val input = socket.inputStream
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) throw IOException("소켓 종료")
                        for (f in decoder.feed(buf, n)) {
                            if (f.type == SonyFrame.TYPE_ACK) {
                                acks.trySend(f.seq)
                                continue
                            }
                            write(SonyFrame.encode(SonyFrame.TYPE_ACK, (f.seq + 1) and 1, ByteArray(0)))
                            DebugLog.add("sony", "RX type=${f.type} ${f.payload.toHex()}")
                            handlePayload(device, f.payload)
                        }
                    }
                }

                // 송신: 한 번에 하나씩 보내고 ACK 의 시퀀스 번호를 다음 송신에 쓴다.
                launch(Dispatchers.IO) {
                    var seq = 0
                    commands.trySend(SonyCommands.protocolInfoRequest)
                    requestBattery()
                    if (noiseControlEnabled()) commands.trySend(SonyCommands.noiseRequest)
                    for (payload in commands) {
                        write(SonyFrame.encode(SonyFrame.TYPE_DATA_MDR, seq, payload))
                        DebugLog.add("sony", "TX ${payload.toHex()}")
                        seq = withTimeoutOrNull(1_500) { acks.receive() } ?: ((seq + 1) and 1)
                        delay(120)
                    }
                }
            }
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handlePayload(device: BluetoothDevice, payload: ByteArray) {
        val previous = _state.value?.battery ?: BatteryInfo()
        when (val ev = SonyCommands.parse(payload, previous)) {
            is SonyEvent.Battery -> updateIfCurrent(device) { it.copy(battery = ev.info.sanitized()) }
            is SonyEvent.Noise -> updateIfCurrent(device) { it.copy(noise = ev.mode) }
            is SonyEvent.Unknown -> Unit // 로그에는 이미 RX 로 남아 있다.
        }
    }

    private fun updateIfCurrent(device: BluetoothDevice, f: (EarbudsState) -> EarbudsState) {
        _state.update { s -> if (s != null && s.address == device.address) f(s) else s }
    }

    companion object {
        val SONY_UUID: UUID = UUID.fromString("96CC203E-5068-46ad-B32D-E316F5E069BA")
    }
}
