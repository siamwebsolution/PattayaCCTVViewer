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
    val source: String = "PTT OR • ราคาขายปลีกน้ำมัน"
)

data class GoldInfo(
    val summary: String,
    val source: String = "สมาคมค้าทองคำ • ราคาทองตามประกาศ"
)

object LiveInfoRepository {
    const val WEATHER_DETAIL_URL = "https://www.tmd.go.th/weather/province/pattaya"
    const val OIL_DETAIL_URL = "https://orapiweb.pttor.com/oilservice/OilPrice.asmx?op=CurrentOilPrice"
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
        return runCatching { fetchOilFromPttOrService() }
            .getOrElse { fetchOilFromEnergyPage() }
    }

    private fun fetchOilFromPttOrService(): OilInfo {
        val soapUrl = "https://orapiweb.pttor.com/oilservice/OilPrice.asmx"
        val payload = """<?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body>
                <CurrentOilPrice xmlns="http://www.pttor.com">
                  <Language>EN</Language>
                </CurrentOilPrice>
              </soap:Body>
            </soap:Envelope>""".trimIndent()
        val connection = (URL(soapUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 18000
            setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            setRequestProperty("SOAPAction", "\"https://orapiweb.pttor.com/CurrentOilPrice\"")
            setRequestProperty("User-Agent", "PattayaCCTVViewer/2.8 Android")
        }
        val response = try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("PTT OR HTTP " + code)
            body
        } finally { connection.disconnect() }

        val decoded = response.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        val rows = Regex("""(?is)<DataAccess\b[^>]*>(.*?)</DataAccess>""")
            .findAll(decoded).mapNotNull { match ->
                val block = match.groupValues[1]
                val product = Regex("""(?is)<PRODUCT\b[^>]*>\s*(.*?)\s*</PRODUCT>""")
                    .find(block)?.groupValues?.getOrNull(1)?.replace(Regex("<[^>]+>"), "")?.trim()
                val price = Regex("""(?is)<PRICE\b[^>]*>\s*([0-9.]+)\s*</PRICE>""")
                    .find(block)?.groupValues?.getOrNull(1)
                if (product.isNullOrBlank() || price.isNullOrBlank()) null else product to price
            }.toList()
        fun priceOf(vararg aliases: String): String? =
            rows.firstOrNull { (product, _) -> aliases.any { product.contains(it, ignoreCase = true) } }?.second

        val gasohol95 = priceOf("Blue Gasohol 95", "Gasohol 95")
        val e20 = priceOf("Blue Gasohol E20", "Gasohol E20", "E20")
        val diesel = priceOf("Blue Diesel B7", "Diesel B7", "Blue Diesel", "Diesel")
        if (gasohol95 == null && e20 == null && diesel == null) throw IllegalStateException("PTT OR oil data not found")

        val summary = buildString {
            if (gasohol95 != null) append("PTT Gasohol 95  ฿" + gasohol95 + "/ลิตร")
            if (e20 != null) { if (isNotEmpty()) append("\n"); append("PTT E20  ฿" + e20 + "/ลิตร") }
            if (diesel != null) { if (isNotEmpty()) append("\n"); append("PTT Diesel  ฿" + diesel + "/ลิตร") }
        }
        return OilInfo(summary, "PTT OR • CurrentOilPrice Web Service")
    }

    private fun fetchOilFromEnergyPage(): OilInfo {
        val text = Jsoup.connect("https://new2.energy.go.th/th/home")
            .userAgent("Mozilla/5.0 (Android) PattayaCCTVViewer/2.8")
            .timeout(18000).get().text().replace("\u00A0", " ")
        fun findPrice(vararg labels: String): String? {
            labels.forEach { label ->
                Regex(Regex.escape(label) + """[^0-9]{0,40}([0-9]{2,3}(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.getOrNull(1)?.let { return it }
            }
            return null
        }
        val gasohol95 = findPrice("Gasohol 95", "แก๊สโซฮอล์ 95", "แก๊สโซฮอลล์ 95")
        val e20 = findPrice("E20", "Gasohol E20")
        val diesel = findPrice("Diesel B7", "ดีเซล B7", "ดีเซล")
        if (gasohol95 == null && e20 == null && diesel == null) throw IllegalStateException("Oil price data not found")
        val summary = buildString {
            if (gasohol95 != null) append("Gasohol 95  ฿" + gasohol95 + "/ลิตร")
            if (e20 != null) { if (isNotEmpty()) append("\n"); append("E20  ฿" + e20 + "/ลิตร") }
            if (diesel != null) { if (isNotEmpty()) append("\n"); append("Diesel  ฿" + diesel + "/ลิตร") }
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
