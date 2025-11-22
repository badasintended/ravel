package lol.bai.ravel.remapper

import com.intellij.openapi.vfs.writeText
import kotlin.io.path.readLines
import kotlin.io.path.useLines

private const val accessWidener = "accessWidener"
private const val classTweaker = "classTweaker"

private object Entry {
    val header = Regex("^([A-Za-z]+)\\s+v(\\d+)\\s+(\\w+)\\s*$")
    val clazz = Regex("^([\\w-]+\\s+class\\s+)([\\w/$]+)(.*)")
    val field = Regex("^([\\w-]+\\s+field\\s+)([\\w/$]+)(\\s+)([\\w$]+)(\\s+)([\\w/$;\\[]+)(.*)")
    val method = Regex("^([\\w-]+\\s+method\\s+)([\\w/$]+)(\\s+)([\\w$<>]+)(\\s+)([\\w/$;\\[()]+)(.*)")
    val injectInterface = Regex("^([\\w-]*inject-interface\\s+)([\\w/$]+)(\\s+)([\\w/$]+)(.*)")
}

private val skip = setOf(
    "json", "yaml", "yml", "properties", "toml",
    "java", "kt", "scala", "groovy",
    "png", "jpg", "jpeg", "svg", "ogg", "wav"
)

class ClassTweakerRemapperFactory : RemapperFactory(::ClassTweakerRemapper, { !skip.contains(it) })
class ClassTweakerRemapper : Remapper() {

    private lateinit var lines: List<String>
    override fun stages() = listOf(remapClassTweakers)

    override fun init(): Boolean {
        val header = try {
            file.toNioPath().useLines { it.firstOrNull() }
        } catch (_: Exception) {
            null
        }

        if (header == null) return false

        val headerMatch = Entry.header.matchEntire(header) ?: return false
        val (type, versionStr, _) = headerMatch.destructured
        val version = versionStr.toIntOrNull() ?: return false

        if (type == accessWidener) {
            if (version !in 1..2) return false
        } else if (type == classTweaker) {
            if (version != 1) return false
        }

        lines = file.toNioPath().readLines()
        return true
    }

    override fun fileComment(comment: String) {
        val sb = StringBuilder()
        val lineIter = lines.iterator()

        sb.append(lineIter.next()).append('\n')
        sb.append(comment).append('\n')
        lineIter.forEachRemaining { sb.append(it).append('\n') }

        file.writeText(sb.toString())
    }

    private val remapClassTweakers = Stage {
        var modified = false
        val sb = StringBuilder()
        val lineIter = lines.iterator()
        sb.append(lineIter.next()).append('\n')

        lineIter.forEachRemaining l@{ line ->
            val classMatch = Entry.clazz.matchEntire(line)
            if (classMatch != null) {
                val (s1, className, rest) = classMatch.destructured
                val newClassName = mTree.getClass(className)?.newName
                if (newClassName != null) {
                    sb.append("${s1}${newClassName}${rest}\n")
                    modified = true
                    return@l
                }
            }

            val fieldMatch = Entry.field.matchEntire(line)
            if (fieldMatch != null) {
                var (s1, className, s2, fieldName, s3, fieldDesc, rest) = fieldMatch.destructured

                val mClass = mTree.getClass(className)
                if (mClass != null) {
                    val newClassName = mClass.newName
                    if (newClassName != null) {
                        className = newClassName
                        modified = true
                    }

                    val newFieldName = mClass.getField(fieldName)?.newName
                    if (newFieldName != null) {
                        fieldName = newFieldName
                        modified = true
                    }
                }

                val newFieldDesc = mTree.remapDesc(fieldDesc)
                if (fieldDesc != newFieldDesc) {
                    fieldDesc = newFieldDesc
                    modified = true
                }

                sb.append("${s1}${className}${s2}${fieldName}${s3}${fieldDesc}${rest}\n")
                return@l
            }

            val methodMatch = Entry.method.matchEntire(line)
            if (methodMatch != null) {
                var (s1, className, s2, methodName, s3, methodDesc, rest) = methodMatch.destructured

                val mClass = mTree.getClass(className)
                if (mClass != null) {
                    val newClassName = mClass.newName
                    if (newClassName != null) {
                        className = newClassName
                        modified = true
                    }

                    val newMethodName = mClass.getMethod(methodName, methodDesc)?.newName
                    if (newMethodName != null) {
                        methodName = newMethodName
                        modified = true
                    }
                }

                val newMethodDesc = mTree.remapDesc(methodDesc)
                if (methodDesc != newMethodDesc) {
                    methodDesc = newMethodDesc
                    modified = true
                }

                sb.append("${s1}${className}${s2}${methodName}${s3}${methodDesc}${rest}\n")
                return@l
            }

            val injectInterfaceMatch = Entry.injectInterface.matchEntire(line)
            if (injectInterfaceMatch != null) {
                var (s1, className1, s2, className2, rest) = injectInterfaceMatch.destructured

                val newClassName1 = mTree.getClass(className1)?.newName
                if (newClassName1 != null) {
                    className1 = newClassName1
                    modified = true
                }

                val newClassName2 = mTree.getClass(className2)?.newName
                if (newClassName2 != null) {
                    className2 = newClassName2
                    modified = true
                }

                sb.append("${s1}${className1}${s2}${className2}${rest}\n")
                return@l
            }

            sb.append(line).append('\n')
        }

        if (modified) write { file.writeText(sb.toString()) }
    }
}
