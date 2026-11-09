package com.reactivenotch.share

/** 드롭된 ClipData 항목 하나. Android 타입에 의존하지 않아서 단위 테스트가 가능하다. */
data class DropItem(val uri: String?, val text: String?, val html: String?, val mime: String?)

enum class ShareAction { SEND, SEND_MULTIPLE }

/** [uriItemIndexes] 는 입력 items 안에서 파일(스트림)로 보낼 항목의 위치. */
data class ShareSpec(
    val action: ShareAction,
    val mime: String,
    val uriItemIndexes: List<Int>,
    val text: String?,
    val html: String?,
)

object ShareSpecs {
    /** 보낼 수 있는 항목이 없으면 null. */
    fun build(rawItems: List<DropItem>): ShareSpec? {
        val items = rawItems.map(::normalize)

        val withUri = items.withIndex().filter { it.value.uri != null }
        if (withUri.isNotEmpty()) {
            return ShareSpec(
                action = if (withUri.size == 1) ShareAction.SEND else ShareAction.SEND_MULTIPLE,
                mime = commonMime(withUri.map { it.value.mime }),
                uriItemIndexes = withUri.map { it.index },
                text = null,
                html = null,
            )
        }
        val texts = items.mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } }
        if (texts.isEmpty()) return null
        return ShareSpec(
            action = ShareAction.SEND,
            mime = "text/plain",
            uriItemIndexes = emptyList(),
            text = texts.joinToString("\n"),
            html = items.firstNotNullOfOrNull { it.html },
        )
    }

    /** 웹 주소(http/https)는 파일이 아니라 텍스트 링크로 보낸다. */
    private fun normalize(item: DropItem): DropItem {
        val uri = item.uri ?: return item
        val lower = uri.lowercase()
        return if (lower.startsWith("http://") || lower.startsWith("https://")) {
            item.copy(uri = null, text = item.text?.takeIf { it.isNotBlank() } ?: uri)
        } else {
            item
        }
    }

    // 전부 같으면 그대로, 대분류(image 등)만 같으면 와일드카드로, 아니면 전체 와일드카드로 만든다.
    fun commonMime(mimes: List<String?>): String {
        val cleaned = mimes.map { m -> m?.lowercase()?.takeIf { it.contains('/') && !it.startsWith("*") } ?: "*/*" }
        if (cleaned.isEmpty()) return "*/*"
        if (cleaned.distinct().size == 1) return cleaned[0]
        val tops = cleaned.map { it.substringBefore('/') }.distinct()
        return if (tops.size == 1 && tops[0] != "*") "${tops[0]}/*" else "*/*"
    }
}
