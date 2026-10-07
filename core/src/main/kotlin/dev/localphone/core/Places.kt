package dev.localphone.core

import java.text.Normalizer
import java.util.Locale

data class Coordinates(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
    fun supportedByNaver() = latitude in 31.43..44.35 && longitude in 122.37..132.0
}

enum class PlaceSource { MANUAL, CURRENT_LOCATION, SHARED_LINK, SEARCH, UI_VERIFIED }

data class UserPlace(
    val id: String,
    val canonicalName: String,
    val aliases: List<String>,
    val coordinates: Coordinates,
    val address: String,
    val provider: String,
    val source: PlaceSource,
    val createdAt: Long,
    val updatedAt: Long,
) {
    init {
        require(id.isNotBlank() && id.length <= 128)
        require(canonicalName.isNotBlank() && canonicalName.length <= 160)
        require(aliases.size <= 50 && aliases.all { it.isNotBlank() && it.length <= 160 })
        require(address.length <= 500 && provider.length <= 100)
        require(updatedAt >= createdAt)
    }
}

interface UserPlacesRepository {
    suspend fun all(): List<UserPlace>
    suspend fun get(id: String): UserPlace?
    suspend fun saveConfirmed(place: UserPlace)
    suspend fun delete(id: String)
}

object PlaceText {
    fun normalize(text: String): String = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[\\s\\p{P}]+"), "")

    fun variants(text: String): Set<String> {
        val value = normalize(text)
        val suffix = listOf("으로", "로", "에").firstOrNull { value.endsWith(it) && value.length > it.length }
        return setOfNotNull(value, suffix?.let { value.dropLast(it.length) })
    }
}

object PlaceSlots {
    val aliases = mapOf(
        "home" to listOf("집", "우리집", "집에", "집으로"),
        "office" to listOf("회사", "직장", "사무실"),
        "parents_home" to listOf("본가", "부모님집", "엄마집"),
        "favorite_gas_station" to listOf("단골 주유소", "자주 가는 주유소"),
    )
    fun slotFor(phrase: String): String? = aliases.entries.firstOrNull { (id, names) ->
        PlaceText.variants(phrase).any { it == PlaceText.normalize(id) || names.any { name -> PlaceText.normalize(name) == it } }
    }?.key
}

enum class ResolutionStatus { RESOLVED_LOCAL, RESOLVED_SEARCH, NOT_FOUND, AMBIGUOUS, PERMISSION_REQUIRED, ERROR }

data class PlaceCandidate(
    val id: String,
    val name: String,
    val coordinates: Coordinates,
    val address: String = "",
    val provider: String = "NAVER",
    val source: PlaceSource = PlaceSource.SEARCH,
)

data class Resolution(
    val status: ResolutionStatus,
    val candidates: List<PlaceCandidate> = emptyList(),
    val setupSlot: String? = null,
    val message: String = "",
) {
    init {
        if (status == ResolutionStatus.RESOLVED_LOCAL || status == ResolutionStatus.RESOLVED_SEARCH) {
            require(candidates.size == 1)
        }
    }
    val resolved: PlaceCandidate?
        get() = candidates.singleOrNull()?.takeIf {
            status == ResolutionStatus.RESOLVED_LOCAL || status == ResolutionStatus.RESOLVED_SEARCH
        }
}

fun UserPlace.toCandidate() = PlaceCandidate(id, canonicalName, coordinates, address, provider, source)

interface PlaceSearch {
    suspend fun search(phrase: String): SearchResult
}

data class SearchResult(
    val candidates: List<PlaceCandidate> = emptyList(),
    val total: Int = candidates.size,
    val permissionRequired: Boolean = false,
    val error: Boolean = false,
)

class PlaceResolver(private val places: UserPlacesRepository, private val search: PlaceSearch) {
    suspend fun resolve(phrase: String): Resolution {
        return try { resolveChecked(phrase) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { Resolution(ResolutionStatus.ERROR, message = "장소 정보를 읽지 못했습니다. 실행하지 않았습니다.") }
    }
    private suspend fun resolveChecked(phrase: String): Resolution {
        if (phrase.isBlank() || phrase.length > 160) return Resolution(ResolutionStatus.ERROR, message = "목적지를 확인해 주세요.")
        val local = places.all()
        fun exact(found: List<UserPlace>): Resolution? = found.takeIf { it.isNotEmpty() }?.let {
            Resolution(if (it.size == 1) ResolutionStatus.RESOLVED_LOCAL else ResolutionStatus.AMBIGUOUS,
                it.map(UserPlace::toCandidate))
        }
        exact(local.filter { it.canonicalName == phrase.trim() })?.let { return it }
        exact(local.filter { phrase.trim() in it.aliases })?.let { return it }
        exact(local.filter { place ->
            val keys = (place.aliases + place.canonicalName + place.id).map(PlaceText::normalize)
            PlaceText.variants(phrase).any { it in keys }
        })?.let { return it }

        // A private slot must never become a public place-search query.
        PlaceSlots.slotFor(phrase)?.let { slot ->
            places.get(slot)?.let { return Resolution(ResolutionStatus.RESOLVED_LOCAL, listOf(it.toCandidate())) }
            return Resolution(ResolutionStatus.NOT_FOUND, setupSlot = slot, message = "로컬 장소에 없습니다. 지도 앱의 저장 장소와 검색 화면에서 계속 찾습니다.")
        }

        // A near match is only a suggestion, even if just one candidate is present.
        val normalized = PlaceText.normalize(phrase)
        val fuzzy = if (normalized.length >= 4) local.filter { place ->
            (place.aliases + place.canonicalName).any {
                val other = PlaceText.normalize(it)
                other.length >= 4 && editDistanceAtMostOne(normalized, other)
            }
        } else emptyList()
        if (fuzzy.isNotEmpty()) return Resolution(ResolutionStatus.AMBIGUOUS, fuzzy.map(UserPlace::toCandidate), message = "이 장소를 말씀하셨나요?")

        val result = search.search(phrase)
        if (result.permissionRequired) return Resolution(ResolutionStatus.PERMISSION_REQUIRED, message = "외부 장소 검색을 설정하거나 네이버지도에서 장소를 공유해 주세요.")
        if (result.error) return Resolution(ResolutionStatus.ERROR, message = "장소 검색에 실패했습니다. 다시 시도해 주세요.")
        val distinct = result.candidates.distinctBy { Triple(it.name, it.coordinates, it.address) }
        return when {
            distinct.isEmpty() -> Resolution(ResolutionStatus.NOT_FOUND, message = "장소를 찾지 못했습니다.")
            // Do not treat a truncated provider response as a unique result.
            distinct.size == 1 && result.total == 1 &&
                PlaceText.normalize(phrase) in listOf(PlaceText.normalize(distinct.single().name), PlaceText.normalize(distinct.single().address)) ->
                Resolution(ResolutionStatus.RESOLVED_SEARCH, distinct)
            else -> Resolution(ResolutionStatus.AMBIGUOUS, distinct, message = "어느 장소로 갈까요?")
        }
    }

    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0; var j = 0; var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            if (a.length >= b.length) i++
            if (b.length >= a.length) j++
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
}
