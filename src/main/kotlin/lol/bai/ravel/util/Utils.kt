package lol.bai.ravel.util

fun String.capitalizeFirstChar() = replaceFirstChar { it.uppercase() }
fun String.decapitalizeFirstChar() = replaceFirstChar { it.lowercase() }

fun wtf(): Nothing = throw UnsupportedOperationException()

@Suppress("unused")
fun <T> mock(): T = throw UnsupportedOperationException()


fun Collection<String>.commonPrefix(): String {
    if (isEmpty()) return ""
    if (size == 1) return first()

    val list = this as? List<String> ?: toList()
    val first = list.first()
    var prefixLen = first.length

    for (string in list) {
        var i = 0
        val limit = minOf(prefixLen, string.length)
        while (i < limit) {
            if (string[i] != first[i]) {
                prefixLen = i
                break
            }
            i++
        }

        if (i == limit) prefixLen = limit
        if (prefixLen == 0) break
    }

    return first.take(prefixLen)
}
