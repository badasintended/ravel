package lol.bai.ravel.mapping.downloader

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import lol.bai.ravel.util.downloadToFile
import lol.bai.ravel.util.http
import java.nio.file.Path
import java.time.Instant

class MojMapDownloader : MappingDownloader("Mojang Mappings") {
    private val logger = thisLogger()

    private val mojMapTime = Instant.parse("2019-09-04T11:19:34+00:00").minusSeconds(1)
    private val unobfuscatedTime = Instant.parse("2025-12-16T12:42:29+00:00")

    private lateinit var versions: JsonArray

    override suspend fun versions(): List<String> {
        val response = http.get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")

        if (!response.status.isSuccess()) return emptyList()
        val json = response.bodyAsText()

        return try {
            versions = JsonParser.parseString(json).asJsonObject
                .getAsJsonArray("versions")
            versions
                .map { it.asJsonObject }
                .filter {
                    val releaseTime = Instant.parse(it.get("releaseTime").asString)
                    releaseTime.isAfter(mojMapTime) && releaseTime.isBefore(unobfuscatedTime)
                }
                .map { it.get("id").asString }
        } catch (e: Exception) {
            logger.error(e)
            emptyList()
        }
    }

    override fun resolveDest(version: String) = "mojmap-${version}" to "txt"

    override suspend fun download(version: String, dest: Path): Boolean {
        val url = versions
            .find { it.asJsonObject.get("id").asString == version }?.asJsonObject
            ?.get("url")?.asString ?: return false

        val response = http.get(url)
        if (!response.status.isSuccess()) return false
        val json = response.bodyAsText()

        try {
            val clientTxt = JsonParser.parseString(json).asJsonObject
                .getAsJsonObject("downloads")
                .getAsJsonObject("client_mappings")
                .get("url").asString

            downloadToFile(clientTxt, dest)
            return true
        } catch (e: Exception) {
            logger.error(e)
            return false
        }
    }
}
