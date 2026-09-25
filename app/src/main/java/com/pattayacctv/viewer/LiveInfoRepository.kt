package com.pattayacctv.viewer

import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class WeatherInfo(
    val summary: String,
    val source: String = "Open-Meteo • แบบจำลอง ECMWF/NOAA และผู้ให้บริการอุตุนิยมวิทยา"
)

data class OilInfo(
    val summary: String,
    val source: String = "กระทรวงพลังงาน • ราคาขายปลีกน้ำมัน"
)

data class GoldInfo(
    val summary: String,
    val source: String = "สมาคมค้าทองคำ • ราคาทองตามประกาศ"
)

object LiveInfoRepository {
    const val WEATHER_DETAIL_URL = "https://www.tmd.go.th/weather/province/pattaya"
    const val OIL_DETAIL_URL = "https://new2.energy.go.th/th/home"
    const val GOLD_DETAIL_URL = "https://www.goldtraders.or.th/"

    private const val WEATHER_API =
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=12.9236&longitude=100.8825" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=Asia%2FBangkok&forecast_days=3"

    fun fetchWeather(): WeatherInfo {
        val json = JSONObject(httpGet(WEATHER_API))
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")

        val temp = current.optDouble("temperature_2m", Double.NaN)
        val feels = current.optDouble("apparent_temperature", Double.NaN)
        val humidity = current.optInt("relative_humidity_2m", -1)
        val wind = current.optDouble("wind_speed_10m", Double.NaN)
        val precipitation = current.optDouble("precipitation", Double.NaN)
        val weatherCode = current.optInt("weather_code", -1)

        val maxTemp = daily.optJSONArray("temperature_2m_max")?.optDouble(0, Double.NaN) ?: Double.NaN
        val minTemp = daily.optJSONArray("temperature_2m_min")?.optDouble(0, Double.NaN) ?: Double.NaN
        val rainChance = daily.optJSONArray("precipitation_probability_max")?.optInt(0, -1) ?: -1

        val line1 = buildString {
            append(weatherDescription(weatherCode))
            if (!temp.isNaN()) append("  ${fmt(temp)}°C")
            if (!feels.isNaN()) append(" • รู้สึก ${fmt(feels)}°C")
        }
        val line2 = buildString {
            if (!minTemp.isNaN() && !maxTemp.isNaN()) append("วันนี้ ${fmt(minTemp)}–${fmt(maxTemp)}°C")
            if (rainChance >= 0) {
                if (isNotEmpty()) append(" • ")
                append("โอกาสฝนสูงสุด $rainChance%")
            }
        }
        val line3 = buildString {
            if (humidity >= 0) append("ความชื้น $humidity%")
            if (!wind.isNaN()) {
                if (isNotEmpty()) append(" • ")
                append("ลม ${fmt(wind)} กม./ชม.")
            }
            if (!precipitation.isNaN() && precipitation > 0.0) {
                if (isNotEmpty()) append(" • ")
                append("ฝน ${fmt(precipitation)} มม.")
            }
        }

        return WeatherInfo(listOf(line1, line2, line3).filter { it.isNotBlank() }.joinToString("\n"))
    }

    fun fetchOil(): OilInfo {
        val doc = Jsoup.connect(OIL_DETAIL_URL)
            .userAgent("Mozilla/5.0 (Android) PattayaCCTVViewer/2.1")
            .timeout(18000)
            .get()

        fun rowPrice(vararg aliases: String): String? {
            for (row in doc.select("tr")) {
                val rowText = row.text().replace("\u00A0", " ").trim()
                if (aliases.none { rowText.contains(it, ignoreCase = true) }) continue

                val cells = row.select("th,td").map { it.text().trim() }
                for (cell in cells.drop(1)) {
                    val match = Regex("""\b\d{1,3}(?:\.\d{1,2})\b""").find(cell)
                    if (match != null) return match.value
                }
            }
            return null
        }

        val gasohol95 = rowPrice("Gasohol 95", "แก๊สโซฮอลล์ 95", "แก็สโซฮอลล์ 95")
        val e20 = rowPrice("Gasohol E20", "E20", "อี 20")
        val diesel = rowPrice("Diesel B7", "ดีเซล B7", "ดีเซล บี7")

        if (gasohol95 == null && e20 == null && diesel == null) {
            throw IllegalStateException("Oil price table not found")
        }

        val summary = buildString {
            if (gasohol95 != null) append("PTT Gasohol 95  ฿$gasohol95/ลิตร")
            if (e20 != null) {
                if (isNotEmpty()) append("\n")
                append("PTT E20  ฿$e20/ลิตร")
            }
            if (diesel != null) {
                if (isNotEmpty()) append("\n")
                append("PTT Diesel B7  ฿$diesel/ลิตร")
            }
        }
        return OilInfo(summary)
    }

    fun fetchGold(): GoldInfo {
        val urls = listOf(
            "https://classic.goldtraders.or.th/default2.aspx",
            "https://www.goldtraders.or.th/"
        )

        var lastError: Throwable? = null
        for (url in urls) {
            try {
                val text = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Android) PattayaCCTVViewer/2.1")
                    .timeout(18000)
                    .get()
                    .text()
                    .replace("\u00A0", " ")

                val bar = Regex(
                    """ทองคำแท่ง\s*96\.5%\s*ขายออก\s*([0-9,.]+).*?รับซื้อ\s*([0-9,.]+)""",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                ).find(text)

                val ornament = Regex(
                    """ทองรูปพรรณ\s*96\.5%\s*ขายออก\s*([0-9,.]+)""",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                ).find(text)

                if (bar != null) {
                    val sell = bar.groupValues[1]
                    val buy = bar.groupValues[2]
                    val ornamentSell = ornament?.groupValues?.getOrNull(1)

                    val summary = buildString {
                        append("ทองคำแท่ง 96.5%\nรับซื้อ ฿$buy • ขายออก ฿$sell")
                        if (!ornamentSell.isNullOrBlank()) {
                            append("\nทองรูปพรรณขายออก ฿$ornamentSell")
                        }
                    }
                    return GoldInfo(summary)
                }
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("Gold price not found")
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "PattayaCCTVViewer/2.1 Android")
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return body
        } finally {
            connection.disconnect()
        }
    }

    private fun fmt(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString()
        else String.format(Locale.US, "%.1f", value)

    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "ท้องฟ้าแจ่มใส"
        1 -> "ท้องฟ้าโปร่งเป็นส่วนมาก"
        2 -> "มีเมฆบางส่วน"
        3 -> "เมฆมาก"
        45, 48 -> "มีหมอก"
        51, 53, 55 -> "มีฝนปรอย"
        56, 57 -> "ฝนเยือกแข็งปรอย"
        61 -> "ฝนเล็กน้อย"
        63 -> "ฝนปานกลาง"
        65 -> "ฝนตกหนัก"
        66, 67 -> "ฝนเยือกแข็ง"
        71, 73, 75, 77 -> "หิมะ"
        80 -> "มีฝนซู่เล็กน้อย"
        81 -> "มีฝนซู่ปานกลาง"
        82 -> "มีฝนซู่หนัก"
        85, 86 -> "มีหิมะซู่"
        95 -> "มีพายุฝนฟ้าคะนอง"
        96, 99 -> "พายุฝนฟ้าคะนองและลูกเห็บ"
        else -> "สภาพอากาศล่าสุด"
    }
}
