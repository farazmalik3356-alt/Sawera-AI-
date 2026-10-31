package com.sawera.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Lets Sawera read the screen and tap/type/scroll — only after the user enables it in Settings > Accessibility. */
class SaweraAccessibilityService : AccessibilityService() {

    companion object { var instance: SaweraAccessibilityService? = null }

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            val t = n.text?.toString() ?: n.contentDescription?.toString()
            if (!t.isNullOrBlank()) sb.append(if (n.isClickable) "[btn] " else "").append(t).append('\n')
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return sb.toString().take(4000)
    }

    fun tapText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val hits = root.findAccessibilityNodeInfosByText(text)
        for (n in hits) {
            var cur: AccessibilityNodeInfo? = n
            while (cur != null) {
                if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                cur = cur.parent
            }
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(dir: String) {
        val m = resources.displayMetrics
        val x = m.widthPixels / 2f
        val (y1, y2) = if (dir == "down") m.heightPixels * 0.7f to m.heightPixels * 0.3f
                       else m.heightPixels * 0.3f to m.heightPixels * 0.7f
        val p = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 350)).build(), null, null)
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
}
