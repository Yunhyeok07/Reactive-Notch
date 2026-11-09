package com.reactivenotch.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.reactivenotch.Settings
import com.reactivenotch.bt.EarbudsState
import com.reactivenotch.bt.NoiseMode
import com.reactivenotch.media.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 디스플레이 좌표(px) 기준 노치 정보. centerX 는 화면 왼쪽 끝에서 노치 중심까지의 거리. */
data class NotchGeometry(val centerXPx: Int, val widthPx: Int, val heightPx: Int)

/** 드래그 앤 드롭 공유 상태. [message] 가 있으면 드롭 직후 결과 안내 중. */
data class ShareDragState(val active: Boolean = false, val hover: Boolean = false, val message: String? = null)

/** 창이 섬보다 약간 커야 스프링 오버슈트가 잘리지 않는다. */
val WindowMargin = 12.dp

/** 실제 탭 노치처럼 위쪽 모서리가 바깥으로 부드럽게 벌어지는 정도. */
private val NotchFlare = 12.dp

private const val AUTO_COLLAPSE_MS = 12_000L
private const val SHRINK_DELAY_MS = 450L
private const val FLING_VELOCITY = 700f // px/s

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
    share: ShareDragState,
    onWindowSize: (widthPx: Int, heightPx: Int) -> Unit,
    mediaActions: MediaActions,
    onSetNoise: (NoiseMode) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val s = settings.scale
    val notchW = with(density) { geo.widthPx.toDp() }
    val notchH = with(density) { geo.heightPx.toDp() }
    val flare = NotchFlare

    // ── 크기 정의 (몸통 폭 기준; 실제 폭은 좌우 귀 flare 만큼 더한다) ──
    val compactActive = settings.showMedia && track?.playing == true
    val dpSpring = spring<Dp>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
    // 음악 중: 노치와 같은 높이로 양옆만 길어진다. 대기: 노치 그대로.
    val restBodyW by animateDpAsState(if (compactActive) notchW + 112.dp * s else notchW, dpSpring, label = "restW")
    val restH = notchH
    val expBodyW = 440.dp * s
    val expH = 224.dp * s
    val shareBodyW = 300.dp * s
    val shareH = notchH + 58.dp

    // ── 손가락을 따라가는 진행도 p (0=접힘, 1=펼침) ──
    val progress = remember { Animatable(0f) }
    var dragP by remember { mutableFloatStateOf(-1f) } // 드래그 중에만 0..1, 아니면 -1
    var gestureActive by remember { mutableStateOf(false) }
    val p = if (dragP >= 0f) dragP else progress.value

    LaunchedEffect(expanded) {
        if (!gestureActive) {
            progress.animateTo(if (expanded) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
        }
    }

    val shareBlend by animateFloatAsState(if (share.active) 1f else 0f, tween(220), label = "share")

    val bodyW = lerp(lerp(restBodyW, expBodyW, p), shareBodyW, shareBlend)
    val h = lerp(lerp(restH, expH, p), shareH, shareBlend)
    val radius = lerp(lerp((notchH / 2).coerceAtLeast(10.dp), 36.dp * s, p), 24.dp, shareBlend)

    val restAlpha by animateFloatAsState(if (compactActive) 1f else 0f, tween(200), label = "restAlpha")
    val fillAlpha = maxOf(restAlpha, (p * 6f).coerceAtMost(1f), shareBlend)

    // ── 창 크기: 커질 때는 즉시, 줄어들 때는 수축이 끝난 뒤 ──
    fun winPx(body: Dp, height: Dp, minBody: Dp = 0.dp, minHeight: Dp = 0.dp) = with(density) {
        IntSize(
            (maxOf(body, minBody) + flare * 2 + WindowMargin * 2).roundToPx(),
            (maxOf(height, minHeight) + WindowMargin).roundToPx(),
        )
    }
    // 대기 중에도 노치 주변을 넉넉히 잡을 수 있게 터치 영역을 조금 키운다.
    val restWin = winPx(if (compactActive) notchW + 112.dp * s else notchW, restH, notchW + 80.dp, notchH + 22.dp)
    val fullWin = winPx(expBodyW, expH)
    val shareWin = winPx(shareBodyW + 60.dp, shareH + 20.dp)
    val needFull = expanded || p > 0.001f || gestureActive
    val target = when {
        share.active -> shareWin
        needFull -> fullWin
        else -> restWin
    }
    var applied by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(target) {
        val growing = target.width > applied.width || target.height > applied.height
        if (!growing) delay(SHRINK_DELAY_MS)
        onWindowSize(target.width, target.height)
        applied = target
    }

    // 열려 있는 동안 조작이 없으면 자동으로 접는다.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(expanded, tick, gestureActive) {
        if (expanded && !gestureActive) {
            delay(AUTO_COLLAPSE_MS)
            onExpandedChange(false)
        }
    }

    val rangePx = with(density) { (expH - restH).toPx() }.coerceAtLeast(1f)
    // pointerInput(Unit) 블록은 한 번만 시작되므로, 바뀔 수 있는 값은 최신 상태로 읽는다.
    val currentRangePx by rememberUpdatedState(rangePx)
    val currentOnExpandedChange by rememberUpdatedState(onExpandedChange)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .size(bodyW + flare * 2, h)
                .clip(NotchShape(flare = flare, bottomRadius = radius))
                .background(Color.Black.copy(alpha = fillAlpha))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        tick++
                        var over = 0f
                        val slop = awaitVerticalTouchSlopOrCancellation(down.id) { change, o ->
                            change.consume()
                            over = o
                        }
                        if (slop == null) return@awaitEachGesture // 탭이거나 다른 곳(페이저 등)이 가져감

                        gestureActive = true
                        val startP = progress.value
                        val range = currentRangePx
                        var total = over
                        val tracker = VelocityTracker()
                        tracker.addPosition(slop.uptimeMillis, slop.position)
                        dragP = (startP + total / range).coerceIn(0f, 1f)

                        verticalDrag(slop.id) { change ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            total += change.positionChange().y
                            dragP = (startP + total / range).coerceIn(0f, 1f)
                            change.consume()
                            tick++
                        }

                        val vy = tracker.calculateVelocity().y
                        val pNow = dragP.coerceAtLeast(0f)
                        val open = when {
                            vy > FLING_VELOCITY -> true
                            vy < -FLING_VELOCITY -> false
                            else -> pNow > 0.5f
                        }
                        currentOnExpandedChange(open)
                        scope.launch {
                            progress.snapTo(pNow)
                            dragP = -1f
                            gestureActive = false
                            progress.animateTo(
                                if (open) 1f else 0f,
                                spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                                initialVelocity = vy / range,
                            )
                        }
                    }
                }
                .pointerInput(expanded) { detectTapGestures(onTap = { onExpandedChange(!expanded) }) }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            tick++
                        }
                    }
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            val shareFade = 1f - shareBlend
            val compactAlpha = (1f - p / 0.3f).coerceIn(0f, 1f) * shareFade
            val expandedAlpha = ((p - 0.45f) / 0.5f).coerceIn(0f, 1f) * shareFade

            if (compactActive && compactAlpha > 0.01f) {
                CompactContent(track, notchW, notchH, flare, Modifier.fillMaxSize().alpha(compactAlpha))
            }
            if (p > 0.05f && expandedAlpha > 0.01f) {
                // 레이아웃은 항상 완전히 펼친 크기로 두고, 섬 모양이 그것을 드러내도록 잘라낸다.
                Box(Modifier.requiredSize(expBodyW + flare * 2, expH).alpha(expandedAlpha)) {
                    ExpandedContent(
                        topInset = notchH, track = track, mediaAccess = mediaAccess, earbuds = earbuds,
                        settings = settings, mediaActions = mediaActions, onSetNoise = onSetNoise,
                    )
                }
            }
            if (shareBlend > 0.01f) {
                ShareContent(share, notchH, Modifier.fillMaxSize().alpha(shareBlend))
            }
        }
    }
}

@Composable
private fun CompactContent(track: Track?, notchW: Dp, notchH: Dp, flare: Dp, modifier: Modifier) {
    // 카메라가 있는 가운데는 비워 두고 양옆에 앨범 아트와 파형을 놓는다.
    val art = (notchH - 8.dp).coerceIn(12.dp, 24.dp)
    Row(modifier.padding(horizontal = flare + 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { AlbumArt(track, art, 6.dp) }
        Spacer(Modifier.width(notchW))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            Bars(true, Color.White, barHeight = (art - 4.dp).coerceAtLeast(10.dp))
        }
    }
}

@Composable
private fun ShareContent(share: ShareDragState, notchH: Dp, modifier: Modifier) {
    Column(
        modifier.padding(top = notchH + 4.dp, start = 16.dp, end = 16.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(IconKind.SHARE, if (share.hover) Color.White else Color(0xFF9A9AA2), if (share.hover) 26.dp else 22.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                share.message ?: if (share.hover) "놓으면 공유 창이 열려요" else "여기에 놓아 공유",
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            )
        }
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
) {
    val showEarbuds = settings.showEarbuds && earbuds != null
    val pageCount = if (showEarbuds) 2 else 1
    val pager = rememberPagerState { pageCount }

    Column(
        Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = topInset + 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            if (page == 0) {
                MusicPage(track, mediaAccess, mediaActions)
            } else if (earbuds != null) {
                EarbudsPage(earbuds, settings.sonyNoiseControl, onSetNoise)
            }
        }
        if (pageCount > 1) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
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

/**
 * 화면 위쪽 가장자리에 붙은 노치 모양: 위쪽 모서리는 오목하게 벌어지고(귀),
 * 아래쪽 모서리만 둥글다. 전체 폭 안에서 몸통은 좌우로 [flare] 만큼 안쪽에 있다.
 */
class NotchShape(private val flare: Dp, private val bottomRadius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val f = with(density) { flare.toPx() }.coerceAtMost(w / 4f).coerceAtMost(h / 2f)
        val r = with(density) { bottomRadius.toPx() }.coerceAtMost((w - 2 * f) / 2f).coerceAtMost(h - f).coerceAtLeast(0f)
        val path = Path().apply {
            moveTo(0f, 0f)
            quadraticBezierTo(f, 0f, f, f)
            lineTo(f, h - r)
            quadraticBezierTo(f, h, f + r, h)
            lineTo(w - f - r, h)
            quadraticBezierTo(w - f, h, w - f, h - r)
            lineTo(w - f, f)
            quadraticBezierTo(w - f, 0f, w, 0f)
            close()
        }
        return Outline.Generic(path)
    }
}
