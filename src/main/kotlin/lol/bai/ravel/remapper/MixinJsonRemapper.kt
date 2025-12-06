package lol.bai.ravel.remapper

import com.intellij.json.JsonUtil
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.diagnostic.thisLogger
import lol.bai.ravel.util.commonPrefix

class MixinJsonRemapperFactory : ExtensionRemapperFactory(::MixinJsonRemapper, "json")
class MixinJsonRemapper : JsonRemapper() {
    private val logger = thisLogger()

    override fun stages() = listOf(remap)

    private val remap = Stage s@{
        val root = JsonUtil.getTopLevelObject(pFile) ?: return@s

        val pkgProp = root.findProperty("package") ?: return@s
        val pkgLiteral = pkgProp.value as? JsonStringLiteral ?: return@s
        val pkg = pkgLiteral.value
        if (!pkg.contains("mixin")) return@s

        val keys = listOf("mixins", "client", "server")
        val newValues = hashMapOf<JsonStringLiteral, String>()

        for (key in keys) {
            val array = root.findProperty(key)?.value as? JsonArray ?: continue

            for (value in array.valueList) {
                if (value !is JsonStringLiteral) continue
                val className = "${pkg}.${value.value}".replace('.', '/')
                val newClassName = mTree.getClass(className)?.newName ?: className
                newValues[value] = newClassName
            }
        }

        val newCommonPrefix = newValues.values.commonPrefix()
        if (!newCommonPrefix.endsWith('/')) {
            logger.warn("Does not have a concrete new package name")
            write { comment(root, "TODO(Ravel): Does not have a concrete package name") }
            return@s
        }

        val newPkg = newCommonPrefix.removeSuffix("/").replace('/', '.')
        if (pkg != newPkg) write { pkgLiteral.replace(gen.createStringLiteral(newPkg)) }

        newValues.forEach { (literal, newName) ->
            val newValue = newName.removePrefix(newCommonPrefix).replace('/', '.')
            if (literal.value != newValue) write { literal.replace(gen.createStringLiteral(newValue)) }
        }
    }
}
