package com.jarvis2.app.integrations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Releve meteo courant pour un point GPS -- voir [WeatherController]. */
data class WeatherReport(
    val locationLabel: String,
    val temperatureC: Double,
    val feelsLikeC: Double?,
    val windKmh: Double,
    val description: String,
    val isDay: Boolean,
)

/**
 * Meteo par position GPS. Utilise Open-Meteo (https://open-meteo.com) : API
 * publique gratuite, sans cle ni compte -- meme philosophie "zero cle a
 * coller" que le catalogue de modeles GGUF (voir ai/gguf/LocalGgufModel.kt)
 * ou l'ancien WebsiteGenController. Le nom de lieu (ville/pays) est resolu en
 * best-effort via BigDataCloud (reverse geocoding gratuit, sans cle) ; si ce
 * second appel echoue, on retombe simplement sur les coordonnees brutes
 * plutot que de faire echouer toute la requete meteo pour une simple
 * etiquette manquante.
 */
class WeatherController(private val httpClient: OkHttpClient = OkHttpClient()) {

    suspend fun currentWeather(latitude: Double, longitude: Double): Result<WeatherReport> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$latitude&longitude=$longitude" +
                    "&current=temperature_2m,apparent_temperature,wind_speed_10m,weather_code,is_day" +
                    "&timezone=auto"
                val request = Request.Builder().url(url).get().build()
                val body = httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Météo: HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
                val current = JSONObject(body).getJSONObject("current")
                val code = current.optInt("weather_code", -1)
                WeatherReport(
                    locationLabel = reverseGeocode(latitude, longitude)
                        ?: "%.3f, %.3f".format(latitude, longitude),
                    temperatureC = current.optDouble("temperature_2m"),
                    feelsLikeC = current.optDouble("apparent_temperature").takeIf { !it.isNaN() },
                    windKmh = current.optDouble("wind_speed_10m"),
                    description = describeWeatherCode(code),
                    isDay = current.optInt("is_day", 1) == 1,
                )
            }
        }

    private fun reverseGeocode(latitude: Double, longitude: Double): String? = runCatching {
        val url = "https://api.bigdatacloud.net/data/reverse-geocode-client" +
            "?latitude=$latitude&longitude=$longitude&localityLanguage=fr"
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val json = JSONObject(response.body?.string().orEmpty())
            val city = json.optString("city").takeIf { it.isNotBlank() }
                ?: json.optString("locality").takeIf { it.isNotBlank() }
            val country = json.optString("countryName").takeIf { it.isNotBlank() }
            listOfNotNull(city, country).joinToString(", ").takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    /** Traduit les codes meteo WMO (standard utilise par Open-Meteo) en francais simple. */
    private fun describeWeatherCode(code: Int): String = when (code) {
        0 -> "ciel dégagé"
        1 -> "plutôt dégagé"
        2 -> "partiellement nuageux"
        3 -> "couvert"
        45, 48 -> "brouillard"
        51, 53, 55 -> "bruine"
        56, 57 -> "bruine verglaçante"
        61, 63, 65 -> "pluie"
        66, 67 -> "pluie verglaçante"
        71, 73, 75 -> "neige"
        77 -> "grains de neige"
        80, 81, 82 -> "averses"
        85, 86 -> "averses de neige"
        95 -> "orage"
        96, 99 -> "orage avec grêle"
        else -> "conditions inconnues"
    }
}
