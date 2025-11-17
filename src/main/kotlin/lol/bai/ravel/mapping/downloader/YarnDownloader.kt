package lol.bai.ravel.mapping.downloader

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.thisLogger
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import lol.bai.ravel.util.http
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.outputStream

class YarnDownloader : MappingDownloader("Yarn") {
    private val logger = thisLogger()

    override fun resolveDest(version: String) = "yarn-${version}-merged" to "tiny"

    override suspend fun versions(): List<String> {
        val response = http.get("https://meta.fabricmc.net/v2/versions/yarn")

        if (!response.status.isSuccess()) return emptyList()
        val json = response.bodyAsText()

        return try {
            JsonParser.parseString(json)
                .asJsonArray
                .map { it.asJsonObject.get("version").asString }
        } catch (e: Exception) {
            logger.error(e)
            emptyList()
        }
    }

    override suspend fun download(version: String, dest: Path): Boolean {
        val jarUrl = "https://maven.fabricmc.net/net/fabricmc/yarn/${version}/yarn-${version}-mergedv2.jar"
        val jarResponse = http.get(jarUrl)
        if (!jarResponse.status.isSuccess()) return false

        ZipInputStream(jarResponse.bodyAsChannel().toInputStream()).use { zis ->
            val buffer = ByteArray(8 * 1024)
            var entry = zis.nextEntry

            val zipDir = dest.parent.resolve("temp.zip")
            val mappingsPath = zipDir.resolve("mappings/mappings.tiny")

            while (entry != null) {
                val resolved = zipDir.resolve(entry.name.trimStart('/'))
                val normalized = resolved.normalize().toAbsolutePath()
                val destAbs = zipDir.toAbsolutePath().normalize()
                if (!normalized.startsWith(destAbs)) {
                    throw SecurityException("Zip entry is outside the target dir: ${entry.name}")
                }

                if (normalized == mappingsPath) {
                    dest.outputStream().use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                        fos.flush()
                        return true
                    }
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return false
    }
}
