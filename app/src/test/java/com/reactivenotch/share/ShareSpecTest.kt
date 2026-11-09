package com.reactivenotch.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareSpecTest {
    private fun file(uri: String, mime: String?) = DropItem(uri, null, null, mime)

    @Test
    fun singleFileIsSendWithItsMime() {
        val s = ShareSpecs.build(listOf(file("content://x/1", "application/pdf")))!!
        assertEquals(ShareAction.SEND, s.action)
        assertEquals("application/pdf", s.mime)
        assertEquals(listOf(0), s.uriItemIndexes)
    }

    @Test
    fun multipleFilesAreSendMultiple() {
        val s = ShareSpecs.build(listOf(file("content://x/1", "image/png"), file("content://x/2", "image/jpeg")))!!
        assertEquals(ShareAction.SEND_MULTIPLE, s.action)
        assertEquals("image/*", s.mime)
        assertEquals(listOf(0, 1), s.uriItemIndexes)
    }

    @Test
    fun mixedTypesFallBackToWildcard() {
        val s = ShareSpecs.build(listOf(file("content://x/1", "image/png"), file("content://x/2", "video/mp4")))!!
        assertEquals("*/*", s.mime)
    }

    @Test
    fun unknownMimeIsWildcard() {
        assertEquals("*/*", ShareSpecs.build(listOf(file("content://x/1", null)))!!.mime)
    }

    @Test
    fun plainTextIsSentAsText() {
        val s = ShareSpecs.build(listOf(DropItem(null, "안녕", null, "text/plain")))!!
        assertEquals(ShareAction.SEND, s.action)
        assertEquals("text/plain", s.mime)
        assertEquals("안녕", s.text)
        assertEquals(emptyList<Int>(), s.uriItemIndexes)
    }

    @Test
    fun webLinkIsTextNotStream() {
        val s = ShareSpecs.build(listOf(DropItem("https://example.com/a", null, null, "text/uri-list")))!!
        assertEquals("text/plain", s.mime)
        assertEquals("https://example.com/a", s.text)
        assertEquals(emptyList<Int>(), s.uriItemIndexes)
    }

    @Test
    fun filesWinOverTextWhenBothPresent() {
        val s = ShareSpecs.build(listOf(DropItem("content://x/1", "name.png", null, "image/png")))!!
        assertEquals(listOf(0), s.uriItemIndexes)
        assertNull(s.text)
    }

    @Test
    fun emptyOrBlankIsNotShareable() {
        assertNull(ShareSpecs.build(emptyList()))
        assertNull(ShareSpecs.build(listOf(DropItem(null, "  ", null, null))))
    }
}
