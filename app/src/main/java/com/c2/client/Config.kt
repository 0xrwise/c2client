cat > app/src/main/java/com/c2/client/Config.kt << 'EOF'
package com.c2.client

import android.content.Context
import java.util.UUID

object Config {
    private const val DEFAULT_URL = "https://your-tunnel.trycloudflare.com/"

    fun getUrl(ctx: Context): String {
        val p = ctx.getSharedPreferences("c2", Context.MODE_PRIVATE)
        return p.getString("url", DEFAULT_URL) ?: DEFAULT_URL
    }

    fun setUrl(ctx: Context, url: String) {
        val clean = url.trim().let { if (it.endsWith("/")) it else "$it/" }
        ctx.getSharedPreferences("c2", Context.MODE_PRIVATE)
            .edit().putString("url", clean).apply()
    }

    fun deviceUuid(ctx: Context): String {
        val p = ctx.getSharedPreferences("c2", Context.MODE_PRIVATE)
        var id = p.getString("uuid", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            p.edit().putString("uuid", id).apply()
        }
        return id
    }
}
EOF
