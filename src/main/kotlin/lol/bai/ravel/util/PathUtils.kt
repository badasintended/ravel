package lol.bai.ravel.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

fun getUserDownloadsDir(): Path {
    val home = System.getProperty("user.home")
    val userProfile = System.getProperty("USERPROFILE")

    val candidates = listOf(
        if (home != null) Paths.get(home, "Downloads") else null,
        if (userProfile != null) Paths.get(userProfile, "Downloads") else null,
    )

    val found = candidates.firstOrNull { it != null && it.exists() && it.isDirectory() }
    return found ?: Path(".")
}

fun Path.resolveUnique(name: String, extension: String = ""): Path {
    val extWithDot = if (extension.isEmpty()) "" else ".$extension"
    var candidate = resolve(name + extWithDot)
    if (!Files.exists(candidate)) return candidate

    var i = 1
    while (true) {
        val numbered = "${name}-${i}${extWithDot}"
        candidate = resolve(numbered)
        if (!Files.exists(candidate)) return candidate
        i++
    }
}
