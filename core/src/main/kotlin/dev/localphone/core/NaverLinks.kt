package dev.localphone.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object NaverLinks {
    const val PACKAGE = "com.nhn.android.nmap"
    fun navigation(place: PlaceCandidate, appId: String): String {
        require(place.coordinates.supportedByNaver())
        require(appId.matches(Regex("[a-zA-Z][a-zA-Z0-9_.]+")))
        return "nmap://navigation?dlat=${place.coordinates.latitude}&dlng=${place.coordinates.longitude}&dname=${encode(place.name)}&appname=${encode(appId)}"
    }
    fun search(query: String, appId: String) = "nmap://search?query=${encode(query)}&appname=${encode(appId)}"
    private fun encode(text: String) = URLEncoder.encode(text, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

sealed interface ShareResult {
    data class CoordinatesFound(val candidate: PlaceCandidate) : ShareResult
    data class NeedsSelection(val name: String, val url: String?, val message: String) : ShareResult
    data class Rejected(val message: String) : ShareResult
}

/** Pure, offline parser. It never follows redirects or fetches private web endpoints. */
object NaverShareParser {
    private val supportedHosts = setOf("map.naver.com", "m.map.naver.com", "naver.me", "place.map.naver.com", "pcmap.place.naver.com")
    fun parse(sharedText: String): ShareResult {
        if (sharedText.length > 16_384) return ShareResult.Rejected("공유 내용이 너무 깁니다.")
        val links = Regex("(?:https?://|nmap://)[^\\s<>]+", RegexOption.IGNORE_CASE).findAll(sharedText).map { it.value.trimEnd(')', ']', ',', '.') }.toList()
        if (links.size != 1) return ShareResult.Rejected("장소 링크를 하나씩 공유해 주세요.")
        val uri = runCatching { URI(links.single()) }.getOrNull() ?: return ShareResult.Rejected("링크 형식을 확인해 주세요.")
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        if (uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return ShareResult.Rejected("지원하지 않는 링크입니다.")
        if (scheme == "https" && host in supportedHosts) {
            val name = sharedText.lineSequence().map(String::trim).firstOrNull { it.isNotBlank() && !it.contains(Regex("https?://")) && it != "[네이버 지도]" && it != "[네이버지도]" }?.take(160).orEmpty()
            return ShareResult.NeedsSelection(name, links.single(), "이 공유 링크에는 확정된 좌표가 없습니다. 주소 검색 또는 현재 위치로 확인해 주세요.")
        }
        if (scheme != "nmap" || host != "place" || (uri.path != "" && uri.path != "/")) return ShareResult.Rejected("네이버 장소 공유 링크를 사용해 주세요.")
        val params = runCatching {
            (uri.rawQuery ?: "").split('&').filter { it.isNotBlank() }.map {
                val parts = it.split('=', limit = 2)
                require(parts.size == 2)
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
            }
        }.getOrNull() ?: return ShareResult.Rejected("링크 인자를 확인해 주세요.")
        if (params.map { it.first }.distinct().size != params.size) return ShareResult.Rejected("중복된 좌표 인자입니다.")
        val map = params.toMap()
        val coords = runCatching { Coordinates(map.getValue("lat").toDouble(), map.getValue("lng").toDouble()) }.getOrNull()
        val name = map["name"]?.takeIf { it.isNotBlank() && it.length <= 160 }
        if (coords == null || !coords.supportedByNaver() || name == null) return ShareResult.Rejected("장소명과 좌표를 확인해 주세요.")
        return ShareResult.CoordinatesFound(PlaceCandidate("shared", name, coords, source = PlaceSource.SHARED_LINK))
    }
}
