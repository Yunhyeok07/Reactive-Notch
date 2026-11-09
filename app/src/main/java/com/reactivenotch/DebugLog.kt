package com.reactivenotch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 설정 화면에서 볼 수 있는 간단한 메모리 로그. 이어폰 프로토콜 송수신 바이트 확인용. */
object DebugLog {
    private const val MAX = 300
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun add(tag: String, message: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        _lines.update { (it + "$time [$tag] $message").takeLast(MAX) }
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
