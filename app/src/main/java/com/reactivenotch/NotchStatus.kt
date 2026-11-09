package com.reactivenotch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class OverlayStatus(
    val serviceRunning: Boolean = false,
    val overlayAttached: Boolean = false,
    /** 감지된 노치 크기 설명. null 이면 위쪽에 노치가 없는 상태(세로 모드 등). */
    val notch: String? = null,
    val mediaTrack: String? = null,
)

/** 설정 화면에서 서비스 상태를 확인하고 섬을 열어보기 위한 가벼운 공유 객체. */
object NotchStatus {
    private val _state = MutableStateFlow(OverlayStatus())
    val state: StateFlow<OverlayStatus> = _state

    @Volatile
    var expandHandler: (() -> Unit)? = null

    fun update(f: (OverlayStatus) -> OverlayStatus) = _state.update(f)
}
