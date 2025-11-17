package lol.bai.ravel.mapping.downloader

import lol.bai.ravel.util.Extension
import java.nio.file.Path

val MappingDownloaderExtension = Extension<MappingDownloader>("lol.bai.ravel.mappingDownloader")

abstract class MappingDownloader(
    val name: String
) {

    override fun toString() = name

    abstract fun resolveDest(version: String): Pair<String, String>
    abstract suspend fun versions(): List<String>
    abstract suspend fun download(version: String, dest: Path): Boolean

}
