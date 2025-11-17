package lol.bai.ravel.util

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.outputStream

val http = HttpClient(OkHttp)

suspend fun downloadToFile(url: String, dest: Path, progress: ((downloaded: Long, total: Long?) -> Unit)? = null) {
    val response = http.get(url)
    if (!response.status.isSuccess()) throw Exception("HTTP ${response.status}")
    val contentLength = response.contentLength()

    dest.createParentDirectories()
    dest.outputStream().use { fos ->
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(8 * 1024)
        var bytesCopied = 0L
        while (!channel.isClosedForRead) {
            val rc = channel.readAvailable(buffer, 0, buffer.size)
            if (rc <= 0) break
            fos.write(buffer, 0, rc)
            bytesCopied += rc
            progress?.invoke(bytesCopied, contentLength)
        }
        fos.flush()
    }
}
