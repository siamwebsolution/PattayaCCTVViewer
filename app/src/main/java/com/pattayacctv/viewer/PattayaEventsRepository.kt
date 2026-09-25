package com.pattayacctv.viewer

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL

object PattayaEventsRepository {

    const val API_URL = "https://khunsri.com/public/api/pattaya_events.php"

    data class PattayaEvent(
        val id: String,
        val title: String,
        val category: String?,
        val startDateText: String?,
        val endDateText: String?,
        val dateText: String?,
        val location: String?,
        val description: String?,
        val imageUrl: String?,
        val detailUrl: String?,
        val mapUrl: String?,
        val latitude: String?,
        val longitude: String?
    )

    fun fetchEvents(): List<PattayaEvent> {
        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "PattayaCCTVViewer/2.6 Android")
        }

        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP " + code)
            }

            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                .trimStart('\uFEFF', ' ', '\n', '\r', '\t')

            if (body.isBlank()) return emptyList()

            val root = JSONTokener(body).nextValue()
            val objects = extractEventObjects(root)

            objects.mapIndexedNotNull { index, obj ->
                parseEvent(obj, index)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractEventObjects(root: Any?): List<JSONObject> {
        return when (root) {
            is JSONArray -> (0 until root.length())
                .mapNotNull { root.optJSONObject(it) }

            is JSONObject -> {
                val wrapperKeys = listOf(
                    "events", "data", "items", "results", "result",
                    "rows", "records", "list"
                )

                wrapperKeys.forEach { key ->
                    if (!root.has(key) || root.isNull(key)) return@forEach
                    val child = root.opt(key)
                    val nested = extractEventObjects(child)
                    if (nested.isNotEmpty()) return nested
                }

                if (firstString(root, listOf("title", "event_title", "event_name", "name")) != null) {
                    listOf(root)
                } else {
                    emptyList()
                }
            }

            else -> emptyList()
        }
    }

    private fun parseEvent(obj: JSONObject, index: Int): PattayaEvent? {
        val title = firstString(
            obj,
            listOf("title", "event_title", "event_name", "name", "subject")
        ) ?: return null

        val id = firstString(
            obj,
            listOf("id", "event_id", "uuid", "slug", "code")
        ) ?: "event-" + index

        val category = firstString(
            obj,
            listOf(
                "category_name", "category", "event_category",
                "category_title", "type_name", "type"
            )
        )

        val startDate = firstString(
            obj,
            listOf(
                "start_date", "event_date", "date", "start",
                "start_at", "event_start", "date_start",
                "start_datetime", "event_start_date"
            )
        )

        val endDate = firstString(
            obj,
            listOf(
                "end_date", "end", "end_at",
                "event_end", "date_end",
                "end_datetime", "event_end_date"
            )
        )

        val dateText = when {
            !startDate.isNullOrBlank() && !endDate.isNullOrBlank() && startDate != endDate ->
                startDate + " - " + endDate
            !startDate.isNullOrBlank() -> startDate
            !endDate.isNullOrBlank() -> endDate
            else -> null
        }

        val location = firstString(
            obj,
            listOf(
                "location", "venue", "place", "event_location",
                "location_name", "address", "venue_name"
            )
        )

        val description = firstString(
            obj,
            listOf(
                "description", "detail", "details", "content",
                "summary", "event_description", "short_description"
            )
        )?.let(::plainText)

        val imageUrl = firstString(
            obj,
            listOf(
                "image_url", "image", "cover_image", "cover",
                "thumbnail", "banner", "photo", "event_image",
                "picture", "featured_image"
            )
        )?.let(::absoluteUrl)

        val detailUrl = firstString(
            obj,
            listOf(
                "url", "link", "detail_url", "event_url",
                "website", "source_url"
            )
        )?.let(::absoluteUrl)

        val mapUrl = firstString(
            obj,
            listOf(
                "map_url", "map_link", "google_maps_url",
                "google_map", "maps_url", "location_url"
            )
        )?.let(::absoluteUrl)

        val latitude = firstString(
            obj,
            listOf("latitude", "lat", "event_lat", "location_lat")
        )

        val longitude = firstString(
            obj,
            listOf("longitude", "lng", "lon", "event_lng", "location_lng")
        )

        return PattayaEvent(
            id = id,
            title = plainText(title),
            category = category?.let(::plainText),
            startDateText = startDate?.let(::plainText),
            endDateText = endDate?.let(::plainText),
            dateText = dateText?.let(::plainText),
            location = location?.let(::plainText),
            description = description,
            imageUrl = imageUrl,
            detailUrl = detailUrl,
            mapUrl = mapUrl,
            latitude = latitude,
            longitude = longitude
        )
    }

    private fun firstString(obj: JSONObject, keys: List<String>): String? {
        keys.forEach { key ->
            if (!obj.has(key) || obj.isNull(key)) return@forEach
            val value = obj.opt(key)
            val text = when (value) {
                is String -> value
                is Number, is Boolean -> value.toString()
                is JSONObject -> firstString(
                    value,
                    listOf("url", "src", "path", "name", "title", "label", "value", "text")
                )
                else -> null
            }?.trim()

            if (!text.isNullOrBlank() && !text.equals("null", ignoreCase = true)) {
                return text
            }
        }
        return null
    }

    private fun plainText(value: String): String {
        return value
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun absoluteUrl(value: String): String {
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
            trimmed.startsWith("//") -> "https:" + trimmed
            trimmed.startsWith("/") -> "https://khunsri.com" + trimmed
            else -> "https://khunsri.com/public/" + trimmed
        }
    }
}
