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

class MojMapDownloader : MappingDownloader("Mojang Mappings") {
    private val logger = thisLogger()

    private lateinit var versions: JsonArray

    override suspend fun versions(): List<String> {
        val response = http.get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")

        if (!response.status.isSuccess()) return emptyList()
        val json = response.bodyAsText()

        return try {
            versions = JsonParser.parseString(json).asJsonObject
                .getAsJsonArray("versions")
            versions.map { it.asJsonObject.get("id").asString }
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
