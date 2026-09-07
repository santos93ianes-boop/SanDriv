package com.sandriv.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), LocationListener {
    private lateinit var map: MapView
    private lateinit var destinationInput: TextInputEditText
    private lateinit var statusText: TextView
    private lateinit var weatherText: TextView
    private lateinit var navigationBanner: View
    private lateinit var instructionText: TextView
    private lateinit var navDetailText: TextView
    private lateinit var speedText: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var originText: TextView
    private lateinit var tripStatsText: TextView
    private lateinit var recenterFloating: MaterialButton

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val locationManager by lazy { getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    private var currentPoint: GeoPoint? = null
    private var destinationPoint: GeoPoint? = null
    private var destinationName: String = ""
    private var routePolyline: Polyline? = null
    private var destinationMarker: Marker? = null
    private var currentMarker: Marker? = null
    private var routeDistanceMeters: Double = 0.0
    private var routeDurationSeconds: Double = 0.0
    private var routeSteps = JSONArray()
    private var isNavigating = false
    private var lastWeatherFetch = 0L
    private var followMode = true
    private var tripStartTime = 0L
    private var tripDistanceMeters = 0.0
    private var lastTripLocation: Location? = null
    private var lastRerouteAt = 0L

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val locationOk = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (locationOk) startLocationUpdates() else toast("A localização é necessária para navegar.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = "SanDriv/1.0 (Android; contato pelo aplicativo)"
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        destinationInput = findViewById(R.id.destinationInput)
        statusText = findViewById(R.id.statusText)
        weatherText = findViewById(R.id.weatherText)
        navigationBanner = findViewById(R.id.navigationBanner)
        instructionText = findViewById(R.id.instructionText)
        navDetailText = findViewById(R.id.navDetailText)
        speedText = findViewById(R.id.speedText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        originText = findViewById(R.id.originText)
        tripStatsText = findViewById(R.id.tripStatsText)
        recenterFloating = findViewById(R.id.recenterFloating)

        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(5.0)
        map.controller.setCenter(GeoPoint(-14.2350, -51.9253))

        findViewById<MaterialButton>(R.id.searchButton).setOnClickListener { searchAndRoute() }
        findViewById<MaterialButton>(R.id.myLocationButton).setOnClickListener { centerOnMe() }
        recenterFloating.setOnClickListener { centerOnMe() }
        map.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_MOVE && isNavigating) {
                followMode = false
                recenterFloating.visibility = View.VISIBLE
            }
            false
        }
        startButton.setOnClickListener { startNavigation() }
        stopButton.setOnClickListener { stopNavigation() }
        findViewById<MaterialButton>(R.id.reportButton).setOnClickListener { reportOccurrence() }
        findViewById<MaterialButton>(R.id.fuelButton).setOnClickListener { loadFuelStations() }
        findViewById<MaterialButton>(R.id.voiceButton).setOnClickListener { openVoiceSettings() }
        destinationInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { searchAndRoute(); true } else false
        }

        requestPermissionsIfNeeded()
        renderSavedAlerts()
    }

    private fun requestPermissionsIfNeeded() {
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startLocationUpdates() else permissionsLauncher.launch(missing.toTypedArray())
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        try {
            val provider = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            locationManager.requestLocationUpdates(provider, 2500L, 4f, this)
            locationManager.getLastKnownLocation(provider)?.let { onLocationChanged(it) }
            statusText.text = "Localização ativa. Digite um destino."
        } catch (_: Exception) {
            statusText.text = "Ative o GPS do aparelho para usar o SanDriv."
        }
    }

    override fun onLocationChanged(location: Location) {
        currentPoint = GeoPoint(location.latitude, location.longitude)
        originText.text = "Minha localização • GPS ativo"
        updateCurrentMarker(currentPoint!!)
        if (lastWeatherFetch == 0L || System.currentTimeMillis() - lastWeatherFetch > 15 * 60 * 1000) {
            lastWeatherFetch = System.currentTimeMillis()
            loadWeather(currentPoint!!)
        }
        if (isNavigating) {
            recordTripPoint(location)
            updateNavigationUi(location)
            maybeReroute(location)
        }
    }

    private fun updateCurrentMarker(point: GeoPoint) {
        if (currentMarker == null) {
            currentMarker = Marker(map).apply {
                position = point
                title = "Você está aqui"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            map.overlays.add(currentMarker)
        } else currentMarker?.position = point
        map.invalidate()
    }

    private fun centerOnMe() {
        val p = currentPoint
        if (p == null) toast("Aguardando sinal de GPS...") else {
            followMode = true
            recenterFloating.visibility = View.GONE
            map.controller.animateTo(p)
            map.controller.setZoom(if (isNavigating) 17.5 else 16.0)
        }
    }

    private fun searchAndRoute() {
        val query = destinationInput.text?.toString()?.trim().orEmpty()
        val start = currentPoint
        if (query.isBlank()) { toast("Digite um endereço ou cidade."); return }
        if (start == null) { toast("Aguardando sua localização para calcular a rota."); return }
        statusText.text = "Procurando destino e calculando rota..."
        startButton.isEnabled = false

        lifecycleScope.launch {
            try {
                val dest = withContext(Dispatchers.IO) { geocode(query) }
                    ?: throw IllegalStateException("Destino não encontrado")
                destinationPoint = dest
                destinationName = query
                showDestination(dest, query)
                val route = withContext(Dispatchers.IO) { fetchRoute(start, dest) }
                drawRoute(route.geometry)
                routeDistanceMeters = route.distance
                routeDurationSeconds = route.duration
                routeSteps = route.steps
                startButton.isEnabled = true
                val km = route.distance / 1000.0
                val mins = (route.duration / 60).roundToInt()
                statusText.text = "${formatKm(km)} • ${formatDuration(mins)} • rota pronta"
                fitRoute(route.geometry)
                loadWeather(dest)
            } catch (e: Exception) {
                statusText.text = "Não consegui montar a rota. Verifique a internet e tente novamente."
                toast(e.message ?: "Falha ao calcular rota")
            }
        }
    }

    private fun geocode(query: String): GeoPoint? {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&countrycodes=br&q=$q"
        val request = Request.Builder().url(url).header("User-Agent", "SanDriv/1.0 Android").build()
        http.newCall(request).execute().use { res ->
            if (!res.isSuccessful) return null
            val arr = JSONArray(res.body?.string().orEmpty())
            if (arr.length() == 0) return null
            val obj = arr.getJSONObject(0)
            return GeoPoint(obj.getString("lat").toDouble(), obj.getString("lon").toDouble())
        }
    }

    private data class RouteResult(val geometry: List<GeoPoint>, val distance: Double, val duration: Double, val steps: JSONArray)

    private fun fetchRoute(start: GeoPoint, dest: GeoPoint): RouteResult {
        val url = "https://router.project-osrm.org/route/v1/driving/${start.longitude},${start.latitude};${dest.longitude},${dest.latitude}?overview=full&geometries=geojson&steps=true"
        val request = Request.Builder().url(url).header("User-Agent", "SanDriv/1.0 Android").build()
        http.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("Serviço de rota indisponível")
            val root = JSONObject(res.body?.string().orEmpty())
            if (root.optString("code") != "Ok") throw IllegalStateException("Não existe rota disponível")
            val route = root.getJSONArray("routes").getJSONObject(0)
            val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
            val points = ArrayList<GeoPoint>(coords.length())
            for (i in 0 until coords.length()) {
                val c = coords.getJSONArray(i)
                points += GeoPoint(c.getDouble(1), c.getDouble(0))
            }
            val flatSteps = JSONArray()
            val legs = route.getJSONArray("legs")
            for (l in 0 until legs.length()) {
                val steps = legs.getJSONObject(l).getJSONArray("steps")
                for (s in 0 until steps.length()) {
                    val st = steps.getJSONObject(s)
                    val man = st.optJSONObject("maneuver") ?: JSONObject()
                    val loc = man.optJSONArray("location") ?: continue
                    flatSteps.put(JSONObject().apply {
                        put("lat", loc.getDouble(1)); put("lon", loc.getDouble(0))
                        put("distance", st.optDouble("distance", 0.0))
                        put("name", st.optString("name", ""))
                        put("type", man.optString("type", "continue"))
                        put("modifier", man.optString("modifier", ""))
                    })
                }
            }
            return RouteResult(points, route.getDouble("distance"), route.getDouble("duration"), flatSteps)
        }
    }

    private fun showDestination(point: GeoPoint, name: String) {
        destinationMarker?.let { map.overlays.remove(it) }
        destinationMarker = Marker(map).apply {
            position = point; title = name; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        map.overlays.add(destinationMarker)
        map.invalidate()
    }

    private fun drawRoute(points: List<GeoPoint>) {
        routePolyline?.let { map.overlays.remove(it) }
        routePolyline = Polyline().apply {
            outlinePaint.strokeWidth = 13f
            outlinePaint.color = android.graphics.Color.rgb(0, 117, 255)
            setPoints(points)
        }
        map.overlays.add(0, routePolyline)
        map.invalidate()
    }

    private fun fitRoute(points: List<GeoPoint>) {
        if (points.isEmpty()) return
        val box = org.osmdroid.util.BoundingBox.fromGeoPoints(points)
        map.post { map.zoomToBoundingBox(box, true, 120) }
    }

    private fun startNavigation() {
        val dest = destinationPoint ?: return
        if (!hasLocationPermission()) { requestPermissionsIfNeeded(); return }
        val intent = Intent(this, NavigationService::class.java).apply {
            action = NavigationService.ACTION_START
            putExtra(NavigationService.EXTRA_DEST_LAT, dest.latitude)
            putExtra(NavigationService.EXTRA_DEST_LON, dest.longitude)
            putExtra(NavigationService.EXTRA_DEST_NAME, destinationName)
            putExtra(NavigationService.EXTRA_STEPS, routeSteps.toString())
        }
        ContextCompat.startForegroundService(this, intent)
        isNavigating = true
        followMode = true
        tripStartTime = System.currentTimeMillis()
        tripDistanceMeters = 0.0
        lastTripLocation = null
        recenterFloating.visibility = View.GONE
        navigationBanner.visibility = View.VISIBLE
        findViewById<View>(R.id.topPanel).visibility = View.GONE
        stopButton.visibility = View.VISIBLE
        startButton.visibility = View.GONE
        statusText.text = "À frente • clima, postos e alertas durante o percurso"
        instructionText.text = "Rota iniciada"
        toast("SanDriv continuará orientando mesmo com outro app aberto.")
    }

    private fun stopNavigation() {
        startService(Intent(this, NavigationService::class.java).apply { action = NavigationService.ACTION_STOP })
        saveTripSummary()
        isNavigating = false
        followMode = true
        recenterFloating.visibility = View.GONE
        navigationBanner.visibility = View.GONE
        findViewById<View>(R.id.topPanel).visibility = View.VISIBLE
        stopButton.visibility = View.GONE
        startButton.visibility = View.VISIBLE
        statusText.text = "Navegação encerrada."
    }

    private fun updateNavigationUi(location: Location) {
        val dest = destinationPoint ?: return
        val results = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, dest.latitude, dest.longitude, results)
        val remainingKm = results[0] / 1000.0
        val speedMps = location.speed.takeIf { it > 2f } ?: 13.9f
        val sec = results[0] / speedMps
        val eta = Date(System.currentTimeMillis() + (sec * 1000).toLong())
        val etaText = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(eta)
        navDetailText.text = "${formatKm(remainingKm)} • chegada aprox. $etaText"
        val kmh = (location.speed * 3.6f).coerceAtLeast(0f).roundToInt()
        speedText.text = "$kmh km/h"
        instructionText.text = nearestInstruction(location) ?: "Siga pela rota"
        val elapsed = ((System.currentTimeMillis() - tripStartTime) / 1000).coerceAtLeast(0)
        tripStatsText.text = "Gravando • ${formatKm(tripDistanceMeters / 1000.0)} • ${elapsed / 60} min"
        if (followMode) {
            map.controller.animateTo(GeoPoint(location.latitude, location.longitude))
            map.controller.setZoom(17.5)
        }
    }

    private fun nearestInstruction(location: Location): String? {
        var best: JSONObject? = null
        var bestD = Float.MAX_VALUE
        for (i in 0 until routeSteps.length()) {
            val s = routeSteps.optJSONObject(i) ?: continue
            val out = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, s.optDouble("lat"), s.optDouble("lon"), out)
            if (out[0] < bestD && out[0] > 15) { bestD = out[0]; best = s }
        }
        return best?.let { instructionFor(it, bestD) }
    }

    private fun instructionFor(step: JSONObject, distance: Float): String {
        val modifier = step.optString("modifier")
        val road = step.optString("name")
        val direction = when (modifier) {
            "left", "slight left", "sharp left" -> "vire à esquerda"
            "right", "slight right", "sharp right" -> "vire à direita"
            "uturn" -> "faça o retorno"
            else -> "siga em frente"
        }
        val prefix = if (distance < 1000) "Em ${distance.roundToInt()} m" else "Em ${"%.1f".format(Locale.US, distance / 1000f)} km"
        return if (road.isBlank()) "$prefix, $direction" else "$prefix, $direction na $road"
    }


    private fun recordTripPoint(location: Location) {
        val last = lastTripLocation
        if (last != null) {
            val delta = last.distanceTo(location)
            if (delta in 1f..250f) tripDistanceMeters += delta
        }
        lastTripLocation = Location(location)
    }

    private fun maybeReroute(location: Location) {
        val poly = routePolyline ?: return
        if (System.currentTimeMillis() - lastRerouteAt < 20_000L) return
        val here = GeoPoint(location.latitude, location.longitude)
        val points = poly.actualPoints
        if (points.isNullOrEmpty()) return
        var nearest = Double.MAX_VALUE
        for (p in points.asSequence().filterIndexed { i, _ -> i % 6 == 0 }) {
            val r = FloatArray(1)
            Location.distanceBetween(here.latitude, here.longitude, p.latitude, p.longitude, r)
            if (r[0] < nearest) nearest = r[0].toDouble()
        }
        if (nearest > 90.0) {
            lastRerouteAt = System.currentTimeMillis()
            statusText.text = "Você saiu da rota • recalculando..."
            val dest = destinationPoint ?: return
            lifecycleScope.launch {
                try {
                    val route = withContext(Dispatchers.IO) { fetchRoute(here, dest) }
                    drawRoute(route.geometry)
                    routeDistanceMeters = route.distance
                    routeDurationSeconds = route.duration
                    routeSteps = route.steps
                    statusText.text = "Nova rota pronta • continue dirigindo"
                } catch (_: Exception) {
                    statusText.text = "Sem conexão para recalcular • mantendo rota atual"
                }
            }
        }
    }

    private fun saveTripSummary() {
        if (tripStartTime == 0L || destinationName.isBlank()) return
        val prefs = getSharedPreferences("trips", MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString("items", "[]")) } catch (_: Exception) { JSONArray() }
        arr.put(JSONObject().apply {
            put("destination", destinationName)
            put("startedAt", tripStartTime)
            put("endedAt", System.currentTimeMillis())
            put("distanceMeters", tripDistanceMeters)
        })
        while (arr.length() > 30) arr.remove(0)
        prefs.edit().putString("items", arr.toString()).apply()
        tripStatsText.text = "Viagem salva • ${formatKm(tripDistanceMeters / 1000.0)}"
        tripStartTime = 0L
        lastTripLocation = null
    }

    private fun loadWeather(point: GeoPoint) {
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { fetchWeather(point) }
                weatherText.text = info
            } catch (_: Exception) { weatherText.text = "☁ clima --" }
        }
    }

    private fun fetchWeather(point: GeoPoint): String {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${point.latitude}&longitude=${point.longitude}&current=temperature_2m,weather_code,wind_speed_10m&hourly=precipitation_probability&forecast_days=1&timezone=auto"
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException()
            val root = JSONObject(res.body?.string().orEmpty())
            val cur = root.getJSONObject("current")
            val temp = cur.optDouble("temperature_2m").roundToInt()
            val code = cur.optInt("weather_code")
            val icon = weatherIcon(code)
            val hourly = root.optJSONObject("hourly")
            val probs = hourly?.optJSONArray("precipitation_probability")
            val times = hourly?.optJSONArray("time")
            val currentHour = cur.optString("time").take(13)
            var start = 0
            if (times != null && currentHour.isNotBlank()) {
                for (i in 0 until times.length()) {
                    if (times.optString(i).startsWith(currentHour)) { start = i; break }
                }
            }
            val maxRain = if (probs != null && probs.length() > 0) {
                var max = 0
                for (i in start until (start + 4).coerceAtMost(probs.length())) max = maxOf(max, probs.optInt(i))
                max
            } else 0
            return "$icon $temp°C • chuva $maxRain%"
        }
    }

    private fun weatherIcon(code: Int) = when (code) {
        0 -> "☀"
        1, 2, 3 -> "⛅"
        45, 48 -> "🌫"
        in 51..67, in 80..82 -> "🌧"
        in 71..77, 85, 86 -> "🌨"
        in 95..99 -> "⛈"
        else -> "☁"
    }

    private fun loadFuelStations() {
        val p = currentPoint ?: run { toast("Aguardando GPS."); return }
        statusText.text = "Buscando postos próximos..."
        lifecycleScope.launch {
            try {
                val stations = withContext(Dispatchers.IO) { fetchFuelStations(p) }
                stations.forEach { st ->
                    val marker = Marker(map).apply {
                        position = GeoPoint(st.lat, st.lon)
                        title = "⛽ ${st.name}"
                        snippet = "Posto de combustível"
                    }
                    map.overlays.add(marker)
                }
                map.invalidate()
                statusText.text = "${stations.size} postos encontrados em até 7 km."
            } catch (_: Exception) {
                statusText.text = "Não foi possível consultar postos agora."
            }
        }
    }

    private data class Fuel(val lat: Double, val lon: Double, val name: String)

    private fun fetchFuelStations(p: GeoPoint): List<Fuel> {
        val query = "[out:json][timeout:15];(node[\"amenity\"=\"fuel\"](around:7000,${p.latitude},${p.longitude});way[\"amenity\"=\"fuel\"](around:7000,${p.latitude},${p.longitude}););out center 25;"
        val body = query.toRequestBody("text/plain; charset=utf-8".toMediaType())
        val req = Request.Builder().url("https://overpass-api.de/api/interpreter").post(body).header("User-Agent", "SanDriv/1.0 Android").build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException()
            val arr = JSONObject(res.body?.string().orEmpty()).getJSONArray("elements")
            val out = mutableListOf<Fuel>()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val center = e.optJSONObject("center")
                val lat = if (e.has("lat")) e.getDouble("lat") else center?.optDouble("lat") ?: continue
                val lon = if (e.has("lon")) e.getDouble("lon") else center?.optDouble("lon") ?: continue
                val tags = e.optJSONObject("tags")
                val name = tags?.optString("name")?.takeIf { it.isNotBlank() } ?: tags?.optString("brand")?.takeIf { it.isNotBlank() } ?: "Posto"
                out += Fuel(lat, lon, name)
            }
            return out
        }
    }

    private fun reportOccurrence() {
        val p = currentPoint ?: run { toast("Aguardando sua localização."); return }
        val items = arrayOf("🕳 Buraco", "🚗 Acidente", "🚧 Obra", "⚠ Perigo na pista")
        AlertDialog.Builder(this)
            .setTitle("Informar ocorrência")
            .setItems(items) { _, which ->
                saveAlert(items[which], p)
                renderAlert(items[which], p)
                toast("Ocorrência registrada neste aparelho.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun saveAlert(type: String, p: GeoPoint) {
        val prefs = getSharedPreferences("alerts", MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString("items", "[]")) } catch (_: Exception) { JSONArray() }
        arr.put(JSONObject().apply {
            put("type", type); put("lat", p.latitude); put("lon", p.longitude); put("time", System.currentTimeMillis())
        })
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private fun renderSavedAlerts() {
        val prefs = getSharedPreferences("alerts", MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString("items", "[]")) } catch (_: Exception) { JSONArray() }
        val cutoff = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optLong("time") < cutoff) continue
            renderAlert(o.optString("type", "⚠ Alerta"), GeoPoint(o.optDouble("lat"), o.optDouble("lon")))
        }
    }

    private fun renderAlert(type: String, p: GeoPoint) {
        map.overlays.add(Marker(map).apply { position = p; title = type; snippet = "Alerta registrado no SanDriv" })
        map.invalidate()
    }

    private fun openVoiceSettings() {
        val prefs = getSharedPreferences("voice", MODE_PRIVATE)
        lateinit var localTts: TextToSpeech
        localTts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) { toast("Voz do aparelho indisponível."); return@TextToSpeech }
            val ptVoices = localTts.voices.orEmpty().filter { it.locale.language == "pt" }.sortedBy { it.name }
            val labels = mutableListOf("Velocidade lenta", "Velocidade normal", "Velocidade rápida")
            labels += ptVoices.mapIndexed { i, v -> "Voz ${i + 1} • ${v.locale.displayCountry.ifBlank { "Português" }}" }
            AlertDialog.Builder(this)
                .setTitle("Voz e navegação")
                .setItems(labels.toTypedArray()) { _, which ->
                    when (which) {
                        0 -> prefs.edit().putFloat("rate", 0.82f).apply()
                        1 -> prefs.edit().putFloat("rate", 1.0f).apply()
                        2 -> prefs.edit().putFloat("rate", 1.18f).apply()
                        else -> prefs.edit().putString("voiceName", ptVoices[which - 3].name).apply()
                    }
                    toast("Configuração de voz salva.")
                    localTts.shutdown()
                }
                .setOnCancelListener { localTts.shutdown() }
                .show()
        }
    }

    private fun formatKm(km: Double): String = if (km < 1) "${(km * 1000).roundToInt()} m" else "${"%.1f".format(Locale("pt", "BR"), km)} km"
    private fun formatDuration(mins: Int): String = if (mins < 60) "$mins min" else "${mins / 60}h ${mins % 60}min"
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { map.onPause(); super.onPause() }
    override fun onDestroy() {
        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
        super.onDestroy()
    }
}
