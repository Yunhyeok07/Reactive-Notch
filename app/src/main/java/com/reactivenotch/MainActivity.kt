package com.reactivenotch

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.reactivenotch.share.DropPermissionHost

class MainActivity : ComponentActivity() {
    override fun onDestroy() {
        if (DropPermissionHost.activity?.get() === this) DropPermissionHost.activity = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.init(this)
        DropPermissionHost.activity = java.lang.ref.WeakReference(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { SettingsScreen() }
            }
        }
    }
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val enabled = AndroidSettings.Secure.getString(context.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    val me = ComponentName(context, NotchService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }

    // 설정 앱에 다녀오면 상태를 다시 읽는다.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    @Suppress("UNUSED_VARIABLE") val trigger = refresh
    val accessibilityOn = isAccessibilityEnabled(context)
    val notificationOn = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    val bluetoothOn = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    val batteryOn = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    val settings by SettingsStore.state.collectAsState()
    val overlay by NotchStatus.state.collectAsState()
    val log by DebugLog.lines.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Reactive Notch", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "갤럭시 탭 노치를 다이내믹 아일랜드처럼 쓰는 앱입니다. 가로 모드에서 카메라 노치가 위쪽에 있을 때만 동작하고, 세로 모드에서는 자동으로 꺼집니다.",
            fontSize = 13.sp,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("현재 상태", fontWeight = FontWeight.SemiBold)
                Text("서비스: " + if (overlay.serviceRunning) "실행 중" else "꺼짐 (접근성 서비스를 켜 주세요)", fontSize = 13.sp)
                Text(
                    "노치: " + (overlay.notch?.let { "감지됨 ($it)" }
                        ?: "감지 안 됨 — 탭을 가로로 돌려 노치가 위로 오게 하세요"),
                    fontSize = 13.sp,
                )
                Text("섬 창: " + if (overlay.overlayAttached) "표시 중" else "없음", fontSize = 13.sp)
                Text("음악: " + (overlay.mediaTrack ?: "감지된 재생 없음"), fontSize = 13.sp)
                Text("평소(대기) 상태의 섬은 노치와 같은 검은색이라 보이지 않습니다. 음악이 재생되면 노치 양옆에 나타납니다.", fontSize = 12.sp)
                if (overlay.overlayAttached) {
                    OutlinedButton({ NotchStatus.expandHandler?.invoke() }) { Text("섬 열어보기") }
                }
            }
        }

        StepCard(
            "1. 접근성 서비스 켜기", accessibilityOn,
            "노치 위에 오버레이를 그리는 데에만 사용합니다. 화면 내용은 읽지 않습니다. 직접 설치한 앱은 스위치가 회색이면 앱 정보 → ⋮ → '제한된 설정 허용'을 먼저 눌러 주세요.",
            "접근성 설정 열기",
        ) { context.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }

        StepCard(
            "2. 알림 접근 허용", notificationOn,
            "재생 중인 음악을 읽고 제어하려면 필요합니다. 알림 내용은 읽지 않습니다.",
            "알림 접근 설정 열기",
        ) { context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

        StepCard(
            "3. 블루투스 권한", bluetoothOn,
            "연결된 이어폰을 찾고 배터리를 읽는 데 필요합니다.",
            "권한 허용",
        ) { btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }

        StepCard(
            "4. 배터리 최적화 제외 (권장)", batteryOn,
            "삼성 One UI가 백그라운드 서비스를 종료하지 않도록 제외해 주세요.",
            "배터리 설정 열기",
        ) { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }

        Text("기능", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        ToggleRow("음악 재생 표시", settings.showMedia) { v -> SettingsStore.update { it.copy(showMedia = v) } }
        ToggleRow("이어폰 슬라이드", settings.showEarbuds) { v -> SettingsStore.update { it.copy(showEarbuds = v) } }
        ToggleRow("드래그 앤 드롭 공유", settings.shareDrop) { v -> SettingsStore.update { it.copy(shareDrop = v) } }
        Text(
            "파일·이미지·텍스트·링크를 끌어서 노치에 놓으면 공유 창(Quick Share 포함)이 열립니다. 파일이 공유되지 않으면 이 앱을 최근 앱 목록에 열어 둔 채로 시도해 보세요. 결과는 아래 로그에 [drag] 로 남습니다.",
            fontSize = 12.sp,
        )
        Text("섬 크기 ${"%.0f".format(settings.scale * 100)}%", fontSize = 14.sp)
        Slider(settings.scale, { v -> SettingsStore.update { it.copy(scale = v) } }, valueRange = 0.8f..1.3f)

        Text("실험적 기능", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        ToggleRow("소니 노이즈 캔슬 읽기/제어", settings.sonyNoiseControl) { v -> SettingsStore.update { it.copy(sonyNoiseControl = v) } }
        Text("WH-1000XM6 / WF-1000XM5 용 명령은 실기기로 아직 검증되지 않았습니다. 켜기 전에 아래 로그로 응답을 확인하세요.", fontSize = 12.sp)
        ToggleRow("전체화면 앱에서 섬 숨기기", settings.hideInFullscreen) { v -> SettingsStore.update { it.copy(hideInFullscreen = v) } }

        Text("프로토콜 로그", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("log", log.joinToString("\n")))
            }) { Text("복사") }
            TextButton({ DebugLog.clear() }) { Text("지우기") }
        }
        Card(Modifier.fillMaxWidth()) {
            Text(
                if (log.isEmpty()) "(비어 있음)" else log.takeLast(60).joinToString("\n"),
                Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepCard(title: String, done: Boolean, description: String, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (done) "✓ 완료" else "필요", color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            Text(description, fontSize = 13.sp)
            if (!done) Button(onClick) { Text(action) }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
