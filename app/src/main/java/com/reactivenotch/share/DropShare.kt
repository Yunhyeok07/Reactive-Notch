package com.reactivenotch.share

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.DragAndDropPermissions
import android.view.DragEvent
import com.reactivenotch.DebugLog
import java.lang.ref.WeakReference

/**
 * 다른 앱에서 끌어온 URI 를 읽으려면 DragAndDropPermissions 가 필요한데, 공개 API 는
 * Activity.requestDragAndDropPermissions() 뿐이다. 오버레이(서비스)에는 그 경로가 없어서,
 * 살아 있는 Activity(설정 화면)를 빌려 호출해 본다. 동작 여부는 기기에서 확인해야 한다.
 */
object DropPermissionHost {
    @Volatile
    var activity: WeakReference<Activity>? = null

    private var held: DragAndDropPermissions? = null

    fun acquire(event: DragEvent): Boolean {
        val a = activity?.get()
        if (a == null) {
            DebugLog.add("drag", "권한 확보용 Activity 없음 (앱이 최근 앱에서 닫힌 상태)")
            return false
        }
        return try {
            held?.release()
            held = a.requestDragAndDropPermissions(event)
            DebugLog.add("drag", "URI 읽기 권한 요청 결과: ${if (held != null) "확보" else "null (URI 권한 없음)"}")
            held != null
        } catch (e: Exception) {
            DebugLog.add("drag", "URI 권한 요청 실패: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }
}

object DropShare {
    /** 드롭된 내용으로 시스템 공유 창을 연다. 성공하면 true. */
    fun share(context: Context, clip: ClipData): Boolean {
        val items = (0 until clip.itemCount).map { i ->
            val item = clip.getItemAt(i)
            val uri: Uri? = item.uri
            val mime = uri?.let { runCatching { context.contentResolver.getType(it) }.getOrNull() }
                ?: clip.description.takeIf { it.mimeTypeCount > i }?.getMimeType(i)
            DropItem(uri?.toString(), item.text?.toString(), item.htmlText, mime)
        }
        DebugLog.add("drag", "드롭 항목 ${items.size}개: " + items.joinToString { "${it.mime ?: "?"}:${it.uri ?: "텍스트"}" })

        val spec = ShareSpecs.build(items)
        if (spec == null) {
            DebugLog.add("drag", "공유할 수 있는 항목이 없음")
            return false
        }
        val intent = Intent(if (spec.action == ShareAction.SEND) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
            .setType(spec.mime)
        if (spec.uriItemIndexes.isNotEmpty()) {
            val uris = ArrayList(spec.uriItemIndexes.map { clip.getItemAt(it).uri })
            if (spec.action == ShareAction.SEND) intent.putExtra(Intent.EXTRA_STREAM, uris[0])
            else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            // 받는 앱(Quick Share 등)이 읽을 수 있도록 권한을 함께 넘긴다.
            intent.clipData = clip
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        spec.text?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
        spec.html?.let { intent.putExtra(Intent.EXTRA_HTML_TEXT, it) }

        val chooser = Intent.createChooser(intent, "공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            DebugLog.add("drag", "공유 창 열림 (${spec.action}, ${spec.mime})")
            true
        } catch (e: Exception) {
            DebugLog.add("drag", "공유 창 실패: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }
}
