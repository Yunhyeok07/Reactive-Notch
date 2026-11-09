package com.reactivenotch.bt

import java.io.ByteArrayOutputStream

/**
 * Sony 헤드폰(WH-1000XM*, WF-1000XM*) RFCOMM 프로토콜.
 *
 * 검증 상태
 *  - [SonyFrame] (프레임 구조·이스케이프·체크섬·시퀀스 규칙): MIT 라이선스 참조 구현
 *    (Plutoberth/SonyHeadphonesClient)과 대조해 확인했고 단위 테스트로 고정되어 있다.
 *  - [SonyCommands] 의 배터리/노이즈 캔슬 명령 번호와 응답 레이아웃: 최신 모델(XM5/XM6)
 *    에서 실기기로 확인되지 않은 **추정값**이다. 앱의 프로토콜 로그로 실제 응답을 확인해
 *    이 파일 한 곳만 고치면 되도록 분리해 두었다.
 */
object SonyFrame {
    const val START = 0x3E
    const val END = 0x3C
    const val ESC = 0x3D

    const val TYPE_ACK = 0x01
    const val TYPE_DATA_MDR = 0x0C

    class Frame(val type: Int, val seq: Int, val payload: ByteArray)

    /** `3E | type | seq | len(4, BE) | payload | checksum | 3C`, 본문은 3C/3D/3E 이스케이프. */
    fun encode(type: Int, seq: Int, payload: ByteArray): ByteArray {
        val body = ByteArrayOutputStream(payload.size + 7)
        body.write(type)
        body.write(seq and 0xFF)
        body.write(payload.size ushr 24 and 0xFF)
        body.write(payload.size ushr 16 and 0xFF)
        body.write(payload.size ushr 8 and 0xFF)
        body.write(payload.size and 0xFF)
        body.write(payload)
        val raw = body.toByteArray()
        val checksum = raw.sumOfBytes()

        val out = ByteArrayOutputStream(raw.size + 4)
        out.write(START)
        for (b in raw + checksum.toByte()) escapeInto(out, b.toInt() and 0xFF)
        out.write(END)
        return out.toByteArray()
    }

    private fun escapeInto(out: ByteArrayOutputStream, b: Int) {
        if (b == START || b == END || b == ESC) {
            out.write(ESC)
            out.write(b and 0xEF)
        } else {
            out.write(b)
        }
    }

    private fun ByteArray.sumOfBytes(): Int {
        var s = 0
        for (b in this) s += b.toInt() and 0xFF
        return s and 0xFF
    }

    /** 소켓에서 조각 조각 들어오는 바이트를 프레임으로 조립한다. */
    class Decoder {
        private val buf = ByteArrayOutputStream()
        private var inFrame = false

        /** 체크섬/길이가 맞지 않은 프레임은 버려지고 [droppedFrames] 만 증가한다. */
        var droppedFrames = 0
            private set

        fun feed(data: ByteArray, length: Int = data.size): List<Frame> {
            val frames = ArrayList<Frame>()
            for (i in 0 until length) {
                val b = data[i].toInt() and 0xFF
                when {
                    b == START -> {
                        buf.reset()
                        inFrame = true
                    }
                    b == END && inFrame -> {
                        inFrame = false
                        val frame = parse(unescape(buf.toByteArray()))
                        if (frame != null) frames += frame else droppedFrames++
                        buf.reset()
                    }
                    inFrame -> buf.write(b)
                }
            }
            return frames
        }

        private fun unescape(src: ByteArray): ByteArray? {
            val out = ByteArrayOutputStream(src.size)
            var i = 0
            while (i < src.size) {
                val b = src[i].toInt() and 0xFF
                if (b == ESC) {
                    if (i == src.size - 1) return null
                    out.write((src[i + 1].toInt() and 0xFF) or 0x10)
                    i += 2
                } else {
                    out.write(b)
                    i++
                }
            }
            return out.toByteArray()
        }

        private fun parse(bytes: ByteArray?): Frame? {
            // type(1) seq(1) len(4) payload(n) checksum(1)
            if (bytes == null || bytes.size < 7) return null
            var sum = 0
            for (i in 0 until bytes.size - 1) sum += bytes[i].toInt() and 0xFF
            if ((sum and 0xFF) != (bytes.last().toInt() and 0xFF)) return null
            val len = ((bytes[2].toInt() and 0xFF) shl 24) or ((bytes[3].toInt() and 0xFF) shl 16) or
                ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
            if (len != bytes.size - 7) return null
            return Frame(
                type = bytes[0].toInt() and 0xFF,
                seq = bytes[1].toInt() and 0xFF,
                payload = bytes.copyOfRange(6, 6 + len),
            )
        }
    }
}

enum class NoiseMode { OFF, NOISE_CANCELLING, AMBIENT }

data class BatteryInfo(
    val single: Int? = null,
    val left: Int? = null,
    val right: Int? = null,
    val case: Int? = null,
    val leftCharging: Boolean = false,
    val rightCharging: Boolean = false,
    val caseCharging: Boolean = false,
    val singleCharging: Boolean = false,
)

sealed interface SonyEvent {
    data class Battery(val info: BatteryInfo) : SonyEvent
    data class Noise(val mode: NoiseMode) : SonyEvent
    data class Unknown(val payload: ByteArray) : SonyEvent
}

/** 명령 번호·응답 레이아웃. 위 문서의 "추정값" 구간이므로 실기기 로그로 확인 후 이 객체만 수정한다. */
object SonyCommands {
    // 검증됨: XM3 계열 참조 구현의 NCASM_SET_PARAM = 0x68
    private const val NCASM_GET = 0x66
    private const val NCASM_RET = 0x67
    private const val NCASM_SET = 0x68
    private const val NCASM_NTFY = 0x69

    // 추정: 전원 상태 조회 계열
    private const val POWER_GET = 0x22
    private const val POWER_RET = 0x23
    private const val POWER_NTFY = 0x25
    private const val BATT_SINGLE = 0x00
    private const val BATT_DUAL = 0x09
    private const val BATT_CASE = 0x0A

    // 추정: XM4 이후 NC/ASM 조회 타입
    private const val NCASM_TYPE_V2 = 0x17

    /** 연결 직후 보내는 프로토콜 정보 요청. */
    val protocolInfoRequest = byteArrayOf(0x00, 0x00)

    val batteryRequests: List<ByteArray> = listOf(
        byteArrayOf(POWER_GET.toByte(), BATT_SINGLE.toByte()),
        byteArrayOf(POWER_GET.toByte(), BATT_DUAL.toByte()),
        byteArrayOf(POWER_GET.toByte(), BATT_CASE.toByte()),
    )

    val noiseRequest = byteArrayOf(NCASM_GET.toByte(), NCASM_TYPE_V2.toByte())

    fun setNoiseMode(mode: NoiseMode): ByteArray {
        val effect = if (mode == NoiseMode.OFF) 0 else 1
        val ambient = if (mode == NoiseMode.AMBIENT) 1 else 0
        val level = if (mode == NoiseMode.AMBIENT) 20 else 0
        return byteArrayOf(
            NCASM_SET.toByte(), NCASM_TYPE_V2.toByte(),
            effect.toByte(), ambient.toByte(), 0, level.toByte(),
        )
    }

    fun parse(payload: ByteArray, previous: BatteryInfo): SonyEvent {
        if (payload.size < 2) return SonyEvent.Unknown(payload)
        val cmd = payload[0].toInt() and 0xFF
        fun at(i: Int) = payload.getOrNull(i)?.toInt()?.and(0xFF)
        return when {
            (cmd == POWER_RET || cmd == POWER_NTFY) -> when (at(1)) {
                BATT_SINGLE -> {
                    val level = at(2) ?: return SonyEvent.Unknown(payload)
                    SonyEvent.Battery(previous.copy(single = level, singleCharging = at(3) == 1))
                }
                BATT_DUAL -> {
                    val l = at(2) ?: return SonyEvent.Unknown(payload)
                    val r = at(4) ?: return SonyEvent.Unknown(payload)
                    SonyEvent.Battery(
                        previous.copy(left = l, leftCharging = at(3) == 1, right = r, rightCharging = at(5) == 1),
                    )
                }
                BATT_CASE -> {
                    val level = at(2) ?: return SonyEvent.Unknown(payload)
                    SonyEvent.Battery(previous.copy(case = level, caseCharging = at(3) == 1))
                }
                else -> SonyEvent.Unknown(payload)
            }
            (cmd == NCASM_RET || cmd == NCASM_NTFY) && at(1) == NCASM_TYPE_V2 -> {
                val effect = at(2) ?: return SonyEvent.Unknown(payload)
                val ambient = at(3) ?: 0
                SonyEvent.Noise(
                    when {
                        effect == 0 -> NoiseMode.OFF
                        ambient == 1 -> NoiseMode.AMBIENT
                        else -> NoiseMode.NOISE_CANCELLING
                    },
                )
            }
            else -> SonyEvent.Unknown(payload)
        }
    }

    /** 배터리 값이 0..100 이 아니면 잘못 해석한 것이므로 표시하지 않는다. */
    fun BatteryInfo.sanitized(): BatteryInfo {
        fun ok(v: Int?) = v?.takeIf { it in 0..100 }
        return copy(single = ok(single), left = ok(left), right = ok(right), case = ok(case))
    }

    fun looksLikeSony(name: String?): Boolean {
        val n = name ?: return false
        return n.startsWith("WH-") || n.startsWith("WF-") || n.startsWith("LinkBuds") || n.startsWith("INZONE")
    }
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
