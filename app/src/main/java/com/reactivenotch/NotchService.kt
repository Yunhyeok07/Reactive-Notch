package com.reactivenotch

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
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
import com.reactivenotch.share.DropPermissionHost
import com.reactivenotch.share.DropShare
import com.reactivenotch.ui.NotchGeometry
import com.reactivenotch.ui.ShareDragState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private var view: NotchRoot? = null
    private var params: WindowManager.LayoutParams? = null
    private var attached = false

    private var geometry by mutableStateOf<NotchGeometry?>(null)
    private var expanded by mutableStateOf(false)
    private var mediaAccess by mutableStateOf(false)
    private var statusBarVisible by mutableStateOf(true)
    private var dragActive by mutableStateOf(false)
    private var dragHover by mutableStateOf(false)
    private var dropMessage by mutableStateOf<String?>(null)
    private var dragResetJob: Job? = null

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

    /** 드래그 이벤트를 자식(Compose)보다 먼저 받기 위한 루트. */
    private class NotchRoot(context: android.content.Context, val onDrag: (DragEvent) -> Boolean) : FrameLayout(context) {
        override fun dispatchDragEvent(event: DragEvent): Boolean = onDrag(event)
    }

    private fun buildView(): NotchRoot {
        val root = NotchRoot(this, ::handleDrag)
        root.setViewTreeLifecycleOwner(this)
        root.setViewTreeSavedStateRegistryOwner(this)
        root.setViewTreeViewModelStoreOwner(this)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            statusBarVisible = insets.isVisible(WindowInsetsCompat.Type.statusBars())
            insets
        }
        // 창 밖을 누르면 접는다 (FLAG_WATCH_OUTSIDE_TOUCH).
        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) changeExpanded(false)
            false
        }

        val compose = ComposeView(this)
        root.addView(compose, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        compose.setContent {
            val settings by SettingsStore.state.collectAsState()
            val track by media.track.collectAsState()
            val buds by earbuds.state.collectAsState()
            val geo = geometry
            val hiddenByFullscreen = settings.hideInFullscreen && !statusBarVisible
            SideEffect { root.visibility = if (hiddenByFullscreen) View.INVISIBLE else View.VISIBLE }

            if (geo != null) {
                IslandRoot(
                    geo = geo,
                    track = track,
                    mediaAccess = mediaAccess,
                    earbuds = buds,
                    settings = settings,
                    expanded = expanded,
                    onExpandedChange = ::changeExpanded,
                    share = ShareDragState(dragActive, dragHover, dropMessage),
                    onWindowSize = ::resizeWindow,
                    mediaActions = MediaActions(media::playPause, media::next, media::previous, media::seekTo),
                    onSetNoise = earbuds::setNoise,
                )
            }
        }
        return root
    }

    /** 다른 앱에서 끌어온 항목을 받는다 (소스 앱이 DRAG_FLAG_GLOBAL 로 시작한 드래그만 도착한다). */
    private fun handleDrag(e: DragEvent): Boolean {
        if (!SettingsStore.state.value.shareDrop || geometry == null) return false
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                DebugLog.add("drag", "시작 mime=${e.clipDescription?.filterMimeTypes("*/*")?.toList()}")
                dragActive = true
                dragHover = false
                dropMessage = null
                expanded = false
                // 끝 이벤트를 못 받는 경우를 대비한 안전장치
                dragResetJob?.cancel()
                dragResetJob = scope.launch {
                    delay(120_000)
                    resetDrag()
                }
                return true
            }
            DragEvent.ACTION_DRAG_ENTERED -> dragHover = true
            DragEvent.ACTION_DRAG_EXITED -> dragHover = false
            DragEvent.ACTION_DROP -> {
                handleDrop(e)
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                DebugLog.add("drag", "끝 (처리됨=${e.result})")
                if (dropMessage == null) resetDrag()
            }
        }
        return true
    }

    private fun handleDrop(e: DragEvent) {
        val clip = e.clipData
        DebugLog.add("drag", "드롭 clip=${if (clip == null) "null" else "${clip.itemCount}개"}")
        dragHover = false
        if (clip == null) {
            showDropMessage("내용을 읽을 수 없어요")
            return
        }
        DropPermissionHost.acquire(e)
        showDropMessage(if (DropShare.share(this, clip)) "공유 창을 여는 중…" else "공유할 수 없는 항목이에요")
    }

    private fun showDropMessage(text: String) {
        dropMessage = text
        dragResetJob?.cancel()
        dragResetJob = scope.launch {
            delay(1_100)
            resetDrag()
        }
    }

    private fun resetDrag() {
        dragActive = false
        dragHover = false
        dropMessage = null
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
