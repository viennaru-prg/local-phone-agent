package dev.localphone.agent.data

import com.google.gson.Gson
import dev.localphone.core.*

/** A provider identity can be cached even when its UI exposes no coordinates. Never invent map-center coordinates. */
data class ProviderPlaceReference(val alias: String, val name: String, val address: String,
                                  val packageName: String, val verifiedAt: Long)
class ProviderPlaceCache(private val settings: SecureSettings) {
    private val gson = Gson()
    private fun key(alias: String) = "ui_place:${PlaceSlots.slotFor(alias) ?: PlaceText.normalize(alias)}"
    fun get(alias: String): ProviderPlaceReference? = runCatching {
        gson.fromJson(settings.get(key(alias)), ProviderPlaceReference::class.java)?.takeIf {
            it.packageName == NaverLinks.PACKAGE && it.name.isNotBlank() &&
                System.currentTimeMillis() - it.verifiedAt in 0..30L * 86400000
        }
    }.getOrNull()
    fun save(reference: ProviderPlaceReference) {
        if (settings.get("cache_ui_places") != "no") settings.put(key(reference.alias), gson.toJson(reference))
    }
}
