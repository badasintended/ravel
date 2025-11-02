package lol.bai.ravel.remapper

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.isFile
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import lol.bai.ravel.Mapping
import net.fabricmc.mappingio.tree.MappingTree.*

typealias Mappings = List<Mapping>
typealias ClassMappings = Map<String, ClassMapping>
typealias Writers = MutableList<Runnable>

data class RemapperModel(
    val mappings: MutableList<Mapping> = arrayListOf(),
    val modules: MutableList<Module> = arrayListOf(),
)

abstract class Remapper<F : PsiFile>(
    val extension: String,
    val caster: (PsiFile?) -> F?,
) {
    abstract fun remap(project: Project, mappings: Mappings, mClasses: ClassMappings, pFile: F, writers: Writers)
}

private val remappers = listOf(
    JavaRemapper,
    MixinRemapper,
)

/**
 * TODO: Currently tested with WTHIT
 *  - mixin
 *  - access widener
 *  - kotlin
 */
fun remap(project: Project, model: RemapperModel) {
    val psi = PsiManager.getInstance(project)

    val mClasses = linkedMapOf<String, ClassMapping>()
    model.mappings.first().tree.classes.forEach {
        mClasses[replaceAllQualifier(it.srcName)] = it
    }

    val writers = arrayListOf<Runnable>()

    for (module in model.modules) for (root in module.rootManager.sourceRoots) VfsUtil.iterateChildrenRecursively(root, null) vf@{ vf ->
        if (!vf.isFile) return@vf true

        for (remapper in remappers) {
            if (vf.extension != remapper.extension) continue
            val file = remapper.caster(psi.findFile(vf)) ?: continue
            remapper.remap(project, model.mappings, mClasses, file, writers)
        }

        true
    }

    writers.forEach { writer ->
        WriteCommandAction.runWriteCommandAction(project, "Ravel Remapper", null, {
            @Suppress("UnusedExpression")
            try {
                writer.run()
            } catch (e: Exception) {
                // TODO: where is the culprit of errors?
                e
            }
        })
    }
}

private val rawQualifierSeparators = Regex("[/$]")
internal fun replaceAllQualifier(raw: String) = raw.replace(rawQualifierSeparators, ".")
internal fun replacePkgQualifier(raw: String) = raw.replace('/', '.')

internal fun Mappings.newName(cls: ClassMapping): String? {
    var className = cls.srcName

    for (m in this) {
        val mClass = m.tree.getClass(className) ?: return null
        className = mClass.getName(m.dest)
    }

    return if (className == cls.srcName) null else className
}

internal fun Mappings.newName(field: FieldMapping): String? {
    var className = field.owner.srcName
    var fieldName = field.srcName

    for (m in this) {
        val mClass = m.tree.getClass(className) ?: return null
        val mField = mClass.getField(fieldName, null) ?: return null
        className = mClass.getName(m.dest)
        fieldName = mField.getName(m.dest)
    }

    return if (fieldName == field.srcName) null else fieldName
}

internal fun Mappings.newName(method: MethodMapping): String? {
    var className = method.owner.srcName
    var methodName = method.srcName
    var methodDesc = method.srcDesc

    for (m in this) {
        val mClass = m.tree.getClass(className) ?: return null
        val mMethod = mClass.getMethod(methodName, methodDesc) ?: return null
        className = mClass.getName(m.dest)
        methodName = mMethod.getName(m.dest)
        methodDesc = mMethod.getDesc(m.dest)
    }

    return if (methodName == method.srcName) null else methodName
}

@Suppress("UnstableApiUsage")
internal fun PsiType.toRaw(): String {
    return when (this) {
        is PsiArrayType -> "[" + componentType.toRaw()
        is PsiPrimitiveType -> kind.binaryName
        is PsiClassType -> {
            fun rawName(cls: PsiClass): String? {
                if (cls is PsiTypeParameter) {
                    val bounds = cls.extendsList.referencedTypes
                    if (bounds.isEmpty()) return "Ljava/lang/Object;"
                    return rawName(bounds.first().resolve()!!)
                }

                val fullName = cls.qualifiedName ?: return null
                val parent = cls.containingClass
                if (parent != null) return rawName(parent) + "$" + cls.name
                return fullName.replace('.', '/')
            }

            val name = rawName(resolve()!!)
            "L${name};"
        }

        else -> {
            val ret = canonicalText
            if (ret.contains('<')) ret.substringBefore('<') else ret
        }
    }
}

internal inline fun <reified E : PsiElement> PsiElement.process(crossinline action: (E) -> Unit) {
    PsiTreeUtil.processElements(this, E::class.java) {
        action(it)
        true
    }
}

internal inline fun <reified E: PsiElement> PsiElement.parent(): E? {
    return PsiTreeUtil.getParentOfType(this, E::class.java)
}
