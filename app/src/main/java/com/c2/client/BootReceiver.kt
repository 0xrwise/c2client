cat > app/src/main/java/com/c2/client/BootReceiver.kt << 'EOF'
package com.c2.client

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        try {
            val svc = Intent(ctx, C2Service::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(svc)
            else ctx.startService(svc)
        } catch (_: Exception) {}
    }
}
EOF
