package dev.localphone.core

import com.google.gson.JsonParser

object NaverSearchResponse {
    fun decode(json: String): SearchResult {
        if (json.length > 1_048_576) return SearchResult(error = true)
        return runCatching {
            val root = JsonParser.parseString(json).asJsonObject
            val total = root.get("total").asInt
            require(total >= 0)
            val items = root.getAsJsonArray("items")
            require(items.size() <= 5)
            val candidates = items.mapIndexedNotNull { index, item ->
                runCatching {
                    val value = item.asJsonObject
                    // Current regional-search coordinates are WGS84 scaled by 10^7.
                    // Do not silently reinterpret obsolete KATECH or map-center coordinates.
                    val lng = value.get("mapx").asString.toLong() / 10_000_000.0
                    val lat = value.get("mapy").asString.toLong() / 10_000_000.0
                    val coordinates = Coordinates(lat, lng)
                    require(coordinates.supportedByNaver())
                    val name = value.get("title").asString.replace(Regex("<[^>]*>"), "")
                        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").trim()
                    require(name.isNotBlank() && name.length <= 160)
                    val road = value.get("roadAddress")?.asString.orEmpty()
                    val address = road.ifBlank { value.get("address")?.asString.orEmpty() }.take(500)
                    PlaceCandidate("search_$index", name, coordinates, address)
                }.getOrNull()
            }
            // A malformed result cannot make the remaining one appear unique.
            SearchResult(candidates, total, error = candidates.size != items.size() || total < candidates.size)
        }.getOrElse { SearchResult(error = true) }
    }
}
