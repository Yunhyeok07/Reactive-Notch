package com.reactivenotch

import android.content.Context
import android.content.Intent

object Gemini {
    private const val GEMINI_PACKAGE = "com.google.android.apps.bard"

    /** 제미나이를 띄운다. 성공하면 true. */
    fun launch(context: Context, mode: GeminiMode): Boolean {
        val order = if (mode == GeminiMode.APP) listOf(::launchApp, ::launchAssistant)
        else listOf(::launchAssistant, ::launchApp)
        return order.any { it(context) }
    }

    private fun launchApp(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(GEMINI_PACKAGE) ?: return false
        return start(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun launchAssistant(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        return start(context, intent)
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        DebugLog.add("gemini", "실행 실패: ${e.javaClass.simpleName} ${e.message}")
        false
    }
}
