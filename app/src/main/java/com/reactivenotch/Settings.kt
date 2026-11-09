package com.reactivenotch

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Settings(
    val showMedia: Boolean = true,
    val showEarbuds: Boolean = true,
    val shareDrop: Boolean = true,
    /** 실험적: 소니 노이즈 캔슬 읽기/쓰기. 명령 번호가 실기기로 확인되기 전까지 기본 꺼짐. */
    val sonyNoiseControl: Boolean = false,
    /** 실험적: 전체화면 앱에서 숨김. 오버레이 창의 인셋 보고에 의존한다. */
    val hideInFullscreen: Boolean = false,
    val scale: Float = 1f,
)

object SettingsStore {
    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("notch_settings", Context.MODE_PRIVATE)
        prefs = p
        _state.value = Settings(
            showMedia = p.getBoolean("showMedia", true),
            showEarbuds = p.getBoolean("showEarbuds", true),
            shareDrop = p.getBoolean("shareDrop", true),
            sonyNoiseControl = p.getBoolean("sonyNoiseControl", false),
            hideInFullscreen = p.getBoolean("hideInFullscreen", false),
            scale = p.getFloat("scale", 1f).coerceIn(0.8f, 1.3f),
        )
    }

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_state.value)
        _state.value = s
        prefs?.edit()
            ?.putBoolean("showMedia", s.showMedia)
            ?.putBoolean("showEarbuds", s.showEarbuds)
            ?.putBoolean("shareDrop", s.shareDrop)
            ?.putBoolean("sonyNoiseControl", s.sonyNoiseControl)
            ?.putBoolean("hideInFullscreen", s.hideInFullscreen)
            ?.putFloat("scale", s.scale)
            ?.apply()
    }
}
