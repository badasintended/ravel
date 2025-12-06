package lol.bai.ravel.remapper

import com.intellij.json.JsonUtil
import com.intellij.json.psi.*
import com.intellij.openapi.diagnostic.thisLogger
import lol.bai.ravel.mapping.Mapping

class FabricModJsonRemapperFactory : RemapperFactory(::FabricModJsonRemapper, { it.name == "fabric.mod.json" })
class FabricModJsonRemapper : JsonRemapper() {
    private val logger = thisLogger()

    override fun stages() = listOf(remapEntrypoints)

    private val remapEntrypoints = Stage s@{
        val root = JsonUtil.getTopLevelObject(pFile) ?: return@s

        val schemaVersionProp = root.findProperty("schemaVersion")
        if (schemaVersionProp == null) {
            logger.warn("Schema version not specified")
            write { comment(root, "TODO(Ravel): No schemaVersion found, only version 1 is supported") }
            return@s
        }

        val schemaVersion = (schemaVersionProp.value as? JsonNumberLiteral)?.value
        if (schemaVersion == null || schemaVersion.toInt() != 1) {
            logger.warn("Schema version != 1")
            write { comment(schemaVersionProp, "TODO(Ravel): only schemaVersion 1 is supported") }
            return@s
        }

        val entrypointsProp = root.findProperty("entrypoints") ?: return@s
        val entrypoints = entrypointsProp.value as? JsonObject ?: return@s

        fun remapEntrypoint(property: JsonProperty, literal: JsonStringLiteral) {
            val entrypoint = literal.value
            var newEntryPoint = entrypoint

            if (entrypoint.contains("::")) {
                val (className, memberName) = entrypoint.split("::", limit = 2)
                val classNameSlashed = className.replace('.', '/')

                val mClass = mTree.getClass(classNameSlashed) ?: return
                val newClassName = mClass.newPkgPeriodName ?: className

                val mMembers = arrayListOf<Mapping>()
                mClass.getField(memberName)?.let { mMembers.add(it) }
                mMembers.addAll(mClass.getMethods(memberName))
                val newMemberNames = mMembers.map { it.newName ?: it.oldName }.toSet()

                if (newMemberNames.size != 1) {
                    logger.warn("members have different new names")
                    val comment = mMembers.joinToString(separator = "\n") { "${it.oldName} -> ${it.newName ?: it.oldName}" }
                    write { comment(property, "TODO(Ravel): members different new names\n$comment") }
                    return
                }

                newEntryPoint = "${newClassName}::${newMemberNames.first()}"
            } else {
                val mClass = mTree.getClass(entrypoint) ?: return
                newEntryPoint = mClass.newPkgPeriodName ?: return
            }

            if (newEntryPoint != entrypoint) write { literal.replace(gen.createStringLiteral(newEntryPoint)) }
        }

        for (entrypointProp in entrypoints.propertyList) {
            val entrypoint = entrypointProp.value as? JsonArray ?: continue
            for (entrypointImpl in entrypoint.valueList) when (entrypointImpl) {
                is JsonStringLiteral -> remapEntrypoint(entrypointProp, entrypointImpl)
                is JsonObject -> {
                    val valueProp = entrypointImpl.findProperty("value") ?: continue
                    val value = valueProp.value as? JsonStringLiteral ?: continue
                    remapEntrypoint(valueProp, value)
                }
            }
        }
    }
}
