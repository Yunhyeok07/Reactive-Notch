package com.reactivenotch

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.reactivenotch.bt.EarbudsRepository
import com.reactivenotch.media.MediaRepository
import com.reactivenotch.ui.IslandRoot
import com.reactivenotch.ui.MediaActions
import com.reactivenotch.ui.NotchGeometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 접근성 서비스는 "상태바/컷아웃 위에 창을 그릴 수 있는" TYPE_ACCESSIBILITY_OVERLAY 때문에 쓴다.
 * 화면 내용은 읽지 않는다 (canRetrieveWindowContent=false, 이벤트 처리 없음).
 */
class NotchService : AccessibilityService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private lateinit var media: MediaRepository
    private lateinit var earbuds: EarbudsRepository

    private var view: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false

    private var geometry by mutableStateOf<NotchGeometry?>(null)
    private var expanded by mutableStateOf(false)
    private var mediaAccess by mutableStateOf(false)
    private var statusBarVisible by mutableStateOf(true)

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) refreshGeometry()
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        SettingsStore.init(this)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        windowManager = getSystemService(WindowManager::class.java)
        displayManager = getSystemService(DisplayManager::class.java)
        media = MediaRepository(this)
        earbuds = EarbudsRepository(this, scope) { SettingsStore.state.value.sonyNoiseControl }

        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        view = buildView()
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))

        media.ensureStarted()
        earbuds.start()
        NotchStatus.expandHandler = { changeExpanded(true) }
        NotchStatus.update { it.copy(serviceRunning = true) }
        refreshGeometry()

        scope.launch {
            media.track.collect { t ->
                val text = t?.let { "${it.title} (${if (it.playing) "재생 중" else "일시정지"})" }
                NotchStatus.update { it.copy(mediaTrack = text) }
            }
        }
        // 알림 접근/블루투스 권한은 서비스가 켜진 뒤에 허용될 수 있으므로, 허용되는 순간 시작한다.
        scope.launch {
            var btGranted = earbuds.hasPermission()
            while (true) {
                delay(3_000)
                if (!media.isRegistered) media.ensureStarted()
                if (!btGranted && earbuds.hasPermission()) {
                    btGranted = true
                    earbuds.refresh()
                }
            }
        }
    }

    private fun buildView(): ComposeView = ComposeView(this).apply {
        setViewTreeLifecycleOwner(this@NotchService)
        setViewTreeSavedStateRegistryOwner(this@NotchService)
        setViewTreeViewModelStoreOwner(this@NotchService)

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            statusBarVisible = insets.isVisible(WindowInsetsCompat.Type.statusBars())
            insets
        }
        // 창 밖을 누르면 접는다 (FLAG_WATCH_OUTSIDE_TOUCH).
        setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) changeExpanded(false)
            false
        }

        setContent {
            val settings by SettingsStore.state.collectAsState()
            val track by media.track.collectAsState()
            val buds by earbuds.state.collectAsState()
            val geo = geometry
            val hiddenByFullscreen = settings.hideInFullscreen && !statusBarVisible
            SideEffect { visibility = if (hiddenByFullscreen) View.INVISIBLE else View.VISIBLE }

            if (geo != null) {
                IslandRoot(
                    geo = geo,
                    track = track,
                    mediaAccess = mediaAccess,
                    earbuds = buds,
                    settings = settings,
                    expanded = expanded,
                    onExpandedChange = ::changeExpanded,
                    onWindowSize = ::resizeWindow,
                    onSwipeDown = {
                        changeExpanded(false)
                        Gemini.launch(this@NotchService, settings.geminiMode)
                    },
                    mediaActions = MediaActions(media::playPause, media::next, media::previous, media::seekTo),
                    onSetNoise = earbuds::setNoise,
                )
            }
        }
    }

    private fun changeExpanded(value: Boolean) {
        if (value) {
            // 권한이 뒤늦게 허용됐을 수 있으니 열 때마다 다시 확인한다.
            media.ensureStarted()
            mediaAccess = media.hasAccess()
            earbuds.refresh()
        }
        expanded = value
    }

    /** 가로 모드에서 위쪽에 노치가 있을 때만 섬을 띄운다. 세로 모드 등에서는 비활성화(창 제거). */
    private fun refreshGeometry() {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val rect = display?.cutout?.boundingRectTop?.takeIf { !it.isEmpty }
        if (rect == null) {
            geometry = null
            expanded = false
            detach()
            NotchStatus.update { it.copy(notch = null, overlayAttached = false) }
            return
        }
        val geo = NotchGeometry(rect.centerX(), rect.width(), rect.height())
        if (geo != geometry) geometry = geo
        attach(geo)
        NotchStatus.update { it.copy(notch = "폭 ${geo.widthPx}px × 높이 ${geo.heightPx}px", overlayAttached = attached) }
    }

    private fun attach(geo: NotchGeometry) {
        val v = view ?: return
        if (attached) {
            // 회전 등으로 노치 위치가 바뀐 경우 위치만 갱신한다.
            params?.let { p ->
                p.x = geo.centerXPx - p.width / 2
                runCatching { windowManager.updateViewLayout(v, p) }
            }
            return
        }
        val w = geo.widthPx + 56
        val h = geo.heightPx
        params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = geo.centerXPx - w / 2
            y = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "ReactiveNotch"
        }
        try {
            windowManager.addView(v, params)
            attached = true
        } catch (e: Exception) {
            DebugLog.add("service", "오버레이 추가 실패: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun detach() {
        val v = view ?: return
        if (!attached) return
        runCatching { windowManager.removeView(v) }
        attached = false
    }

    private fun resizeWindow(width: Int, height: Int) {
        val v = view ?: return
        val p = params ?: return
        val geo = geometry ?: return
        if (!attached) return
        p.width = width
        p.height = height
        p.x = geo.centerXPx - width / 2
        runCatching { windowManager.updateViewLayout(v, p) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    private fun teardown() {
        NotchStatus.expandHandler = null
        NotchStatus.update { OverlayStatus() }
        if (::displayManager.isInitialized) displayManager.unregisterDisplayListener(displayListener)
        detach()
        if (::media.isInitialized) media.stop()
        if (::earbuds.isInitialized) earbuds.stop()
        scope.cancel()
        view = null
    }
}
