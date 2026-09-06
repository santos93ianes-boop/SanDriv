package com.sandriv.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

class NavigationService : Service(), LocationListener, TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_START = "com.sandriv.app.START_NAVIGATION"
        const val ACTION_STOP = "com.sandriv.app.STOP_NAVIGATION"
        const val EXTRA_DEST_LAT = "dest_lat"
        const val EXTRA_DEST_LON = "dest_lon"
        const val EXTRA_DEST_NAME = "dest_name"
        const val EXTRA_STEPS = "steps"
        private const val CHANNEL_ID = "sandriv_navigation"
        private const val NOTIFICATION_ID = 116
    }

    private lateinit var locationManager: LocationManager
    private var tts: TextToSpeech? = null
    private var destLat = 0.0
    private var destLon = 0.0
    private var destName = "Destino"
    private var steps = JSONArray()
    private val spokenStepStage = mutableMapOf<Int, Int>()
    private val spokenAlerts = mutableSetOf<String>()
    private var lastNotificationUpdate = 0L

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        createChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopNavigation()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START) {
            destLat = intent.getDoubleExtra(EXTRA_DEST_LAT, 0.0)
            destLon = intent.getDoubleExtra(EXTRA_DEST_LON, 0.0)
            destName = intent.getStringExtra(EXTRA_DEST_NAME).orEmpty().ifBlank { "Destino" }
            steps = try { JSONArray(intent.getStringExtra(EXTRA_STEPS) ?: "[]") } catch (_: Exception) { JSONArray() }
            startAsForeground("Navegação iniciada para $destName")
            requestLocation()
            speak("Navegação iniciada. Siga pela rota indicada.")
        }
        return START_STICKY
    }

    private fun startAsForeground(text: String) {
        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopIntent = Intent(this, NavigationService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("SanDriv • navegação ativa")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Encerrar", stopPending)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun requestLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) { stopNavigation(); return }
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2200L, 4f, this)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 15f, this)
            }
        } catch (_: Exception) { }
    }

    override fun onLocationChanged(location: Location) {
        val remaining = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, destLat, destLon, remaining)
        if (remaining[0] < 45f) {
            speak("Você chegou ao destino.")
            stopNavigation()
            return
        }

        processSteps(location)
        processLocalAlerts(location)
        if (System.currentTimeMillis() - lastNotificationUpdate > 12_000L) {
            lastNotificationUpdate = System.currentTimeMillis()
            val km = remaining[0] / 1000f
            val text = if (km < 1) "${remaining[0].roundToInt()} m até $destName" else "${String.format(Locale("pt", "BR"), "%.1f", km)} km até $destName"
            startAsForeground(text)
        }
    }

    private fun processSteps(location: Location) {
        var closestIndex = -1
        var closestDistance = Float.MAX_VALUE
        var closest: JSONObject? = null
        for (i in 0 until steps.length()) {
            val s = steps.optJSONObject(i) ?: continue
            val out = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, s.optDouble("lat"), s.optDouble("lon"), out)
            if (out[0] < closestDistance && out[0] > 8f) {
                closestDistance = out[0]
                closestIndex = i
                closest = s
            }
        }
        val step = closest ?: return
        val oldStage = spokenStepStage[closestIndex] ?: 0
        val stage = when {
            closestDistance <= 85f -> 2
            closestDistance <= 330f -> 1
            else -> 0
        }
        if (stage > oldStage && stage > 0) {
            spokenStepStage[closestIndex] = stage
            speak(buildInstruction(step, closestDistance, stage))
        }
    }

    private fun buildInstruction(step: JSONObject, distance: Float, stage: Int): String {
        val modifier = step.optString("modifier")
        val road = step.optString("name")
        val direction = when (modifier) {
            "left", "slight left", "sharp left" -> "vire à esquerda"
            "right", "slight right", "sharp right" -> "vire à direita"
            "uturn" -> "faça o retorno"
            else -> "siga em frente"
        }
        val distanceText = if (stage == 2) "Agora" else "Em ${distance.roundToInt()} metros"
        return if (road.isBlank()) "$distanceText, $direction." else "$distanceText, $direction na $road."
    }

    private fun processLocalAlerts(location: Location) {
        val prefs = getSharedPreferences("alerts", MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString("items", "[]")) } catch (_: Exception) { JSONArray() }
        val cutoff = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            if (a.optLong("time") < cutoff) continue
            val lat = a.optDouble("lat")
            val lon = a.optDouble("lon")
            val key = "${lat}_${lon}_${a.optString("type")}" 
            if (key in spokenAlerts) continue
            val out = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, lat, lon, out)
            if (out[0] <= 280f) {
                spokenAlerts += key
                val clean = a.optString("type", "Alerta").replace(Regex("[^\\p{L}\\p{N} ]"), "").trim()
                speak("Atenção. $clean a aproximadamente ${out[0].roundToInt()} metros.")
            }
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val prefs = getSharedPreferences("voice", MODE_PRIVATE)
        tts?.language = Locale("pt", "BR")
        tts?.setSpeechRate(prefs.getFloat("rate", 1.0f))
        val preferred = prefs.getString("voiceName", null)
        if (preferred != null) {
            tts?.voices?.firstOrNull { it.name == preferred }?.let { tts?.voice = it }
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "sandriv_${System.currentTimeMillis()}")
    }

    private fun stopNavigation() {
        try { locationManager.removeUpdates(this) } catch (_: Exception) { }
        tts?.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Navegação SanDriv", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mantém a orientação de rota ativa em segundo plano"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        try { locationManager.removeUpdates(this) } catch (_: Exception) { }
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
}
