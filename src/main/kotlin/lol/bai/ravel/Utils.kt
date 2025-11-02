package lol.bai.ravel

import kotlin.text.lowercase

fun String.decapitalize() = replaceFirstChar { it.lowercase() }
