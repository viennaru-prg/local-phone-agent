package dev.localphone.agent.runtime

import dev.localphone.agent.data.SecureSettings
import dev.localphone.core.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NaverPlaceSearch(private val settings: SecureSettings) : PlaceSearch {
    override suspend fun search(phrase: String): SearchResult = withContext(Dispatchers.IO) {
        val id = settings.get("naver_client_id")
        val secret = settings.get("naver_client_secret")
        if (id.isBlank() || secret.isBlank() || settings.get("search_enabled") != "yes") return@withContext SearchResult(permissionRequired = true)
        if (PlaceSlots.slotFor(phrase) != null) return@withContext SearchResult(error = true)
        runCatching {
            val encoded = URLEncoder.encode(phrase, "UTF-8")
            val connection = URI("https://openapi.naver.com/v1/search/local.json?query=$encoded&display=5&start=1&sort=random").toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000; connection.readTimeout = 8_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("X-Naver-Client-Id", id)
                connection.setRequestProperty("X-Naver-Client-Secret", secret)
                if (connection.responseCode != 200) SearchResult(error = true)
                else {
                    val bytes = connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= 1_048_576) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    if (bytes.size > 1_048_576) SearchResult(error = true)
                    else NaverSearchResponse.decode(bytes.toString(Charsets.UTF_8))
                }
            } finally { connection.disconnect() }
        }.getOrElse { SearchResult(error = true) }
    }
}
