package com.satory.graphenosai.weather

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.time.ZoneId
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

data class WeatherReport(
    val locationName: String,
    val country: String,
    val timezone: String,
    val currentTime: String,
    val temperatureC: Double?,
    val apparentTemperatureC: Double?,
    val humidityPercent: Int?,
    val precipitationMm: Double?,
    val cloudCoverPercent: Int?,
    val windSpeedKmh: Double?,
    val windGustsKmh: Double?,
    val weatherCode: Int?,
    val dailySummary: List<DailyWeather>
) {
    fun toPromptContext(): String {
        val condition = weatherCode?.let(::weatherCodeDescription) ?: "unknown"
        val current = buildString {
            append("Location: $locationName")
            if (country.isNotBlank()) append(", $country")
            append("\nTimezone: $timezone")
            append("\nObservation time: $currentTime")
            append("\nCondition: $condition")
            temperatureC?.let { append("\nTemperature: ${formatNumber(it)}°C") }
            apparentTemperatureC?.let { append("\nFeels like: ${formatNumber(it)}°C") }
            humidityPercent?.let { append("\nHumidity: $it%") }
            precipitationMm?.let { append("\nPrecipitation: ${formatNumber(it)} mm") }
            cloudCoverPercent?.let { append("\nCloud cover: $it%") }
            windSpeedKmh?.let { append("\nWind: ${formatNumber(it)} km/h") }
            windGustsKmh?.let { append("\nWind gusts: ${formatNumber(it)} km/h") }
        }

        val forecast = dailySummary.joinToString("\n") { day ->
            val dayCondition = day.weatherCode?.let(::weatherCodeDescription) ?: "unknown"
            "- ${day.date}: ${formatNumber(day.minTempC)}-${formatNumber(day.maxTempC)}°C, $dayCondition, precipitation ${formatNumber(day.precipitationMm)} mm"
        }

        return """
            Current weather from Open-Meteo:
            $current

            3-day forecast:
            $forecast
        """.trimIndent()
    }
}

data class DailyWeather(
    val date: String,
    val maxTempC: Double,
    val minTempC: Double,
    val precipitationMm: Double,
    val weatherCode: Int?
)

class OpenMeteoClient {
    companion object {
        private const val TAG = "OpenMeteoClient"
        private const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
        private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        private const val TIMEOUT_MS = 15000
    }

    suspend fun getWeather(locationQuery: String?): WeatherReport? = withContext(Dispatchers.IO) {
        try {
            val effectiveLocation = locationQuery?.takeIf { it.isNotBlank() } ?: fallbackLocationFromTimezone()
            val location = geocode(effectiveLocation) ?: return@withContext null
            fetchForecast(location)
        } catch (e: Exception) {
            Log.e(TAG, "Weather lookup failed", e)
            null
        }
    }

    private fun geocode(query: String): GeoLocation? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("$GEOCODING_URL?name=$encoded&count=1&language=en&format=json")
        val json = JSONObject(readUrl(url))
        val results = json.optJSONArray("results") ?: return null
        if (results.length() == 0) return null

        val item = results.getJSONObject(0)
        return GeoLocation(
            name = item.optString("name", query),
            country = item.optString("country", ""),
            latitude = item.optDouble("latitude"),
            longitude = item.optDouble("longitude"),
            timezone = item.optString("timezone", "auto")
        )
    }

    private fun fetchForecast(location: GeoLocation): WeatherReport {
        val current = listOf(
            "temperature_2m",
            "relative_humidity_2m",
            "apparent_temperature",
            "precipitation",
            "weather_code",
            "cloud_cover",
            "wind_speed_10m",
            "wind_gusts_10m"
        ).joinToString(",")
        val daily = listOf(
            "weather_code",
            "temperature_2m_max",
            "temperature_2m_min",
            "precipitation_sum"
        ).joinToString(",")
        val url = URL(
            "$FORECAST_URL?latitude=${location.latitude}&longitude=${location.longitude}" +
                "&current=$current&daily=$daily&forecast_days=3&timezone=auto"
        )
        val json = JSONObject(readUrl(url))
        val currentJson = json.optJSONObject("current")
        val dailyJson = json.optJSONObject("daily")

        return WeatherReport(
            locationName = location.name,
            country = location.country,
            timezone = json.optString("timezone", location.timezone),
            currentTime = currentJson?.optString("time", "") ?: "",
            temperatureC = currentJson?.nullableDouble("temperature_2m"),
            apparentTemperatureC = currentJson?.nullableDouble("apparent_temperature"),
            humidityPercent = currentJson?.nullableInt("relative_humidity_2m"),
            precipitationMm = currentJson?.nullableDouble("precipitation"),
            cloudCoverPercent = currentJson?.nullableInt("cloud_cover"),
            windSpeedKmh = currentJson?.nullableDouble("wind_speed_10m"),
            windGustsKmh = currentJson?.nullableDouble("wind_gusts_10m"),
            weatherCode = currentJson?.nullableInt("weather_code"),
            dailySummary = parseDailyWeather(dailyJson)
        )
    }

    private fun parseDailyWeather(dailyJson: JSONObject?): List<DailyWeather> {
        if (dailyJson == null) return emptyList()
        val dates = dailyJson.optJSONArray("time") ?: return emptyList()
        val maxTemps = dailyJson.optJSONArray("temperature_2m_max")
        val minTemps = dailyJson.optJSONArray("temperature_2m_min")
        val precipitation = dailyJson.optJSONArray("precipitation_sum")
        val codes = dailyJson.optJSONArray("weather_code")

        return (0 until dates.length()).mapNotNull { index ->
            DailyWeather(
                date = dates.optString(index),
                maxTempC = maxTemps?.optDouble(index) ?: return@mapNotNull null,
                minTempC = minTemps?.optDouble(index) ?: return@mapNotNull null,
                precipitationMm = precipitation?.optDouble(index) ?: 0.0,
                weatherCode = codes?.optInt(index)
            )
        }
    }

    private fun readUrl(url: URL): String {
        val connection = url.openConnection() as HttpsURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode != HttpsURLConnection.HTTP_OK) {
                throw IllegalStateException("Open-Meteo API error: $responseCode")
            }
            return connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    private fun fallbackLocationFromTimezone(): String {
        val zone = ZoneId.systemDefault().id
        val city = zone.substringAfterLast('/').replace('_', ' ')
        return city.takeIf { it.isNotBlank() && it != "UTC" && it != "GMT" && it != "Etc" } ?: "Warsaw"
    }

    private data class GeoLocation(
        val name: String,
        val country: String,
        val latitude: Double,
        val longitude: Double,
        val timezone: String
    )
}

private fun JSONObject.nullableDouble(name: String): Double? =
    if (has(name) && !isNull(name)) optDouble(name) else null

private fun JSONObject.nullableInt(name: String): Int? =
    if (has(name) && !isNull(name)) optInt(name) else null

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

private fun weatherCodeDescription(code: Int): String = when (code) {
    0 -> "clear sky"
    1 -> "mainly clear"
    2 -> "partly cloudy"
    3 -> "overcast"
    45, 48 -> "fog"
    51, 53, 55 -> "drizzle"
    56, 57 -> "freezing drizzle"
    61, 63, 65 -> "rain"
    66, 67 -> "freezing rain"
    71, 73, 75 -> "snow"
    77 -> "snow grains"
    80, 81, 82 -> "rain showers"
    85, 86 -> "snow showers"
    95 -> "thunderstorm"
    96, 99 -> "thunderstorm with hail"
    else -> "weather code $code"
}
