package com.reactivenotch.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reactivenotch.bt.BatteryInfo
import com.reactivenotch.bt.EarbudsState
import com.reactivenotch.bt.NoiseMode
import com.reactivenotch.media.Track
import kotlinx.coroutines.delay

class MediaActions(
    val playPause: () -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val seekTo: (Long) -> Unit,
)

private val Dim = Color(0xFF9A9AA2)

@Composable
fun AlbumArt(track: Track?, size: Dp, corner: Dp) {
    val shape = RoundedCornerShape(corner)
    val bmp = track?.art
    if (bmp != null) {
        Image(bmp.asImageBitmap(), null, Modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(Modifier.size(size).clip(shape).background(Color(0xFF2A2A30)), Alignment.Center) {
            Text("♪", color = Dim, fontSize = (size.value * 0.5f).sp)
        }
    }
}

@Composable
fun Bars(playing: Boolean, color: Color, modifier: Modifier = Modifier, barHeight: Dp = 18.dp) {
    val t = rememberInfiniteTransition(label = "bars")
    val scales = List(4) { i ->
        t.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(380 + i * 140, easing = LinearEasing), RepeatMode.Reverse),
            label = "bar$i",
        )
    }
    androidx.compose.foundation.layout.Row(
        modifier.height(barHeight),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        scales.forEach { s ->
            Box(
                Modifier.width(3.dp).height(barHeight)
                    .graphicsLayer { scaleY = if (playing) s.value else 0.25f }
                    .background(color, RoundedCornerShape(2.dp)),
            )
        }
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun SeekBar(fraction: Float, enabled: Boolean, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(18.dp)
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0xFF3A3A42), RoundedCornerShape(2.dp)))
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp)
                .background(Color.White, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
fun MusicPage(track: Track?, hasAccess: Boolean, actions: MediaActions) {
    if (track == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text(
                if (hasAccess) "재생 중인 음악이 없어요" else "알림 접근을 허용하면 음악이 여기에 나와요",
                color = Dim, fontSize = 14.sp,
            )
        }
        return
    }

    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(track.playing, track.title) {
        while (true) {
            now = System.nanoTime()
            if (!track.playing) break
            delay(500)
        }
    }
    val pos = remember(now, track) { track.positionNow() } // now 가 500ms 마다 바뀌어 진행 바를 갱신한다.
    val fraction = if (track.durationMs > 0) pos.toFloat() / track.durationMs else 0f

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AlbumArt(track, 64.dp, 12.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Bars(track.playing, Color.White)
        }
        Column {
            SeekBar(fraction, track.durationMs > 0, { actions.seekTo((it * track.durationMs).toLong()) }, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(pos), color = Dim, fontSize = 11.sp)
                Text(if (track.durationMs > 0) fmt(track.durationMs) else "", color = Dim, fontSize = 11.sp)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            ControlButton(IconKind.PREV, 26.dp, actions.previous)
            Spacer(Modifier.width(28.dp))
            ControlButton(if (track.playing) IconKind.PAUSE else IconKind.PLAY, 34.dp, actions.playPause)
            Spacer(Modifier.width(28.dp))
            ControlButton(IconKind.NEXT, 26.dp, actions.next)
        }
    }
}

@Composable
private fun ControlButton(kind: IconKind, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size + 14.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(kind, Color.White, size) }
}

@Composable
private fun BatteryChip(label: String, percent: Int?, charging: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Dim, fontSize = 11.sp)
        Text(
            if (percent != null) "$percent%" else "–",
            color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Box(Modifier.width(44.dp).height(5.dp).background(Color(0xFF3A3A42), RoundedCornerShape(3.dp))) {
            if (percent != null) {
                Box(
                    Modifier.fillMaxWidth(percent / 100f).fillMaxHeight()
                        .background(
                            when {
                                charging -> Color(0xFF34C759)
                                percent <= 20 -> Color(0xFFFF453A)
                                else -> Color.White
                            },
                            RoundedCornerShape(3.dp),
                        ),
                )
            }
        }
        if (charging) Text("충전 중", color = Color(0xFF34C759), fontSize = 10.sp)
    }
}

@Composable
fun EarbudsPage(
    state: EarbudsState,
    noiseControlEnabled: Boolean,
    onSetNoise: (NoiseMode) -> Unit,
) {
    val b: BatteryInfo = state.battery
    val hasSplit = b.left != null || b.right != null || b.case != null
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(IconKind.HEADPHONES, Color.White, 26.dp)
            Spacer(Modifier.width(10.dp))
            Text(state.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                when {
                    state.protocolLive -> "소니 연결됨"
                    state.isSony -> "연결 중…"
                    else -> "Bluetooth"
                },
                color = if (state.protocolLive) Color(0xFF34C759) else Dim, fontSize = 11.sp,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (hasSplit) {
                if (b.left != null) BatteryChip("왼쪽", b.left, b.leftCharging)
                if (b.right != null) BatteryChip("오른쪽", b.right, b.rightCharging)
                if (b.case != null) BatteryChip("케이스", b.case, b.caseCharging)
            } else {
                BatteryChip("배터리", b.single, b.singleCharging)
            }
        }

        if (state.isSony && noiseControlEnabled) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeButton("노이즈 캔슬", state.noise == NoiseMode.NOISE_CANCELLING, state.protocolLive, Modifier.weight(1f)) { onSetNoise(NoiseMode.NOISE_CANCELLING) }
                ModeButton("주변 소리", state.noise == NoiseMode.AMBIENT, state.protocolLive, Modifier.weight(1f)) { onSetNoise(NoiseMode.AMBIENT) }
                ModeButton("끄기", state.noise == NoiseMode.OFF, state.protocolLive, Modifier.weight(1f)) { onSetNoise(NoiseMode.OFF) }
            }
        }
    }
}

@Composable
private fun ModeButton(text: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(32.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color.White else Color(0xFF2A2A30))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Color.Black else if (enabled) Color.White else Dim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
