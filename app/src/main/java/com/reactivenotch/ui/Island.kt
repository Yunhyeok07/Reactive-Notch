package com.reactivenotch.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.reactivenotch.Settings
import com.reactivenotch.bt.EarbudsState
import com.reactivenotch.bt.NoiseMode
import com.reactivenotch.media.Track
import kotlinx.coroutines.delay

/** 디스플레이 좌표(px) 기준 노치 정보. centerX 는 화면 왼쪽 끝에서 노치 중심까지의 거리. */
data class NotchGeometry(val centerXPx: Int, val widthPx: Int, val heightPx: Int)

enum class IslandMode { IDLE, COMPACT, EXPANDED }

/** 창이 섬보다 약간 커야 스프링 오버슈트가 잘리지 않는다. */
val WindowMargin = 12.dp

private const val AUTO_COLLAPSE_MS = 12_000L
private const val SHRINK_DELAY_MS = 450L

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IslandRoot(
    geo: NotchGeometry,
    track: Track?,
    mediaAccess: Boolean,
    earbuds: EarbudsState?,
    settings: Settings,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onWindowSize: (widthPx: Int, heightPx: Int) -> Unit,
    onSwipeDown: () -> Unit,
    mediaActions: MediaActions,
    onSetNoise: (NoiseMode) -> Unit,
) {
    val density = LocalDensity.current
    val notchW = with(density) { geo.widthPx.toDp() }
    val notchH = with(density) { geo.heightPx.toDp() }
    val s = settings.scale

    val compactActive = settings.showMedia && track?.playing == true
    val mode = when {
        expanded -> IslandMode.EXPANDED
        compactActive -> IslandMode.COMPACT
        else -> IslandMode.IDLE
    }

    val targetW: Dp
    val targetH: Dp
    when (mode) {
        IslandMode.IDLE -> { targetW = notchW + 28.dp; targetH = notchH }
        IslandMode.COMPACT -> { targetW = notchW + 104.dp * s; targetH = maxOf(notchH, 36.dp) }
        IslandMode.EXPANDED -> { targetW = 440.dp * s; targetH = 224.dp * s }
    }

    // 창 크기: 커질 때는 즉시, 줄어들 때는 수축 애니메이션이 끝난 뒤에 적용한다.
    val winW = with(density) { (targetW + WindowMargin * 2).roundToPx() }
    val winH = with(density) { (targetH + WindowMargin).roundToPx() }
    var applied by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(winW, winH) {
        val growing = winW > applied.width || winH > applied.height
        if (!growing) delay(SHRINK_DELAY_MS)
        onWindowSize(winW, winH)
        applied = IntSize(winW, winH)
    }

    val spec = spring<Dp>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
    val w by animateDpAsState(targetW, spec, label = "w")
    val h by animateDpAsState(targetH, spec, label = "h")
    val radius by animateDpAsState(
        if (mode == IslandMode.EXPANDED) 36.dp * s else (notchH / 2).coerceAtLeast(12.dp),
        spec, label = "radius",
    )
    val fillAlpha by animateFloatAsState(if (mode == IslandMode.IDLE) 0f else 1f, tween(200), label = "fill")

    // 열려 있는 동안 조작이 없으면 자동으로 접는다.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(expanded, tick) {
        if (expanded) {
            delay(AUTO_COLLAPSE_MS)
            onExpandedChange(false)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .size(w, h)
                .clip(RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = radius, bottomEnd = radius))
                .background(Color.Black.copy(alpha = fillAlpha))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            tick++
                        }
                    }
                }
                .pointerInput(expanded) { detectTapGestures(onTap = { onExpandedChange(!expanded) }) }
                .pointerInput(settings.swipeGemini) {
                    if (!settings.swipeGemini) return@pointerInput
                    var total = 0f
                    val threshold = 48.dp.toPx()
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragCancel = { total = 0f },
                        onDragEnd = { if (total > threshold) onSwipeDown() },
                    ) { _, delta -> total += delta }
                },
        ) {
            Crossfade(targetState = mode, animationSpec = tween(180), label = "content") { m ->
                when (m) {
                    IslandMode.IDLE -> Unit
                    IslandMode.COMPACT -> CompactContent(track, notchW)
                    IslandMode.EXPANDED -> ExpandedContent(
                        topInset = notchH, track = track, mediaAccess = mediaAccess, earbuds = earbuds,
                        settings = settings, mediaActions = mediaActions, onSetNoise = onSetNoise, scale = s,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactContent(track: Track?, notchW: Dp) {
    // 카메라가 있는 가운데는 비워 두고 양옆에 앨범 아트와 파형을 놓는다.
    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { AlbumArt(track, 22.dp, 6.dp) }
        Spacer(Modifier.width(notchW))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { Bars(true, Color.White) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExpandedContent(
    topInset: Dp,
    track: Track?,
    mediaAccess: Boolean,
    earbuds: EarbudsState?,
    settings: Settings,
    mediaActions: MediaActions,
    onSetNoise: (NoiseMode) -> Unit,
    scale: Float,
) {
    val showEarbuds = settings.showEarbuds && earbuds != null
    val pageCount = if (showEarbuds) 2 else 1
    val pager = rememberPagerState { pageCount }

    Column(
        Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = topInset + 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxSize()) { page ->
            if (page == 0) {
                MusicPage(track, mediaAccess, mediaActions)
            } else if (earbuds != null) {
                EarbudsPage(earbuds, settings.sonyNoiseControl, onSetNoise)
            }
        }
        if (pageCount > 1) {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(pageCount) { i ->
                    Box(
                        Modifier.padding(horizontal = 3.dp).size(6.dp).clip(RoundedCornerShape(50))
                            .background(if (pager.currentPage == i) Color.White else Color(0xFF55555C)),
                    )
                }
            }
        }
    }
}
