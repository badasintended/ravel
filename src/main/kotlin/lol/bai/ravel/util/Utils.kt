package lol.bai.ravel.util

fun String.capitalize() = replaceFirstChar { it.uppercase() }
fun String.decapitalize() = replaceFirstChar { it.lowercase() }

fun wtf(): Nothing = throw UnsupportedOperationException()

@Suppress("unused")
fun <T> mock(): T = throw UnsupportedOperationException()
