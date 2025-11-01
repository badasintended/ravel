package lol.bai.ravel

import com.intellij.diagnostic.PluginException
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.*
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import net.fabricmc.mappingio.tree.MappingTree.*

typealias Mappings = List<Mapping>

data class RemapperModel(
    val mappings: MutableList<Mapping> = arrayListOf(),
    val modules: MutableList<Module> = arrayListOf(),
)

private val rawQualifierSeparators = Regex("[/$]")
private fun Mappings.newName(cls: ClassMapping): String? {
    var className = cls.srcName

    for (m in this) {
        val mClass = m.tree.getClass(className) ?: return null
        className = mClass.getName(m.dest)
    }

    return if (className == cls.srcName) null else className.replace(rawQualifierSeparators, ".")
}

private fun Mappings.newName(field: FieldMapping): String? {
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

private fun Mappings.newName(method: MethodMapping): String? {
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

private fun forEachRefs(runners: MutableList<Runnable>, module: Module, elt: PsiElement, action: (PsiElement) -> Unit) {
    val app = ApplicationManager.getApplication()
    val refs = app.runReadAction(Computable {
        ReferencesSearch.search(elt, module.moduleScope).findAll()
    })

    refs.forEach {
        runners.add {
            try {
                action(it.element)
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * TODO: Currently tested with WTHIT api module
 *  - kotlin
 *  - how to ignore unused references from being searched
 */
fun remap(project: Project, model: RemapperModel) {
    val java = JavaPsiFacade.getInstance(project)
    val javaFactory = java.elementFactory
    val javaStyle = JavaCodeStyleManager.getInstance(project)

    val writers = arrayListOf<Runnable>()
    val mClasses = model.mappings.first().tree.classes

    for (module in model.modules) for (mClass in mClasses) {
        val scope = module.getModuleWithDependenciesAndLibrariesScope(true)
        val pClass = java.findClass(mClass.srcName.replace(rawQualifierSeparators, "."), scope) ?: continue
        val newClassName = model.mappings.newName(mClass)

        for (mField in mClass.fields) {
            val pField = pClass.findFieldByName(mField.srcName, false) ?: continue
            val newFieldName = model.mappings.newName(mField) ?: continue

            forEachRefs(writers, module, pField) ref@{ elt ->
                val javaRef = PsiTreeUtil.getParentOfType(elt, PsiJavaCodeReferenceElement::class.java, false)
                if (javaRef != null) {
                    val refElt = javaRef.referenceNameElement!!
                    refElt.replace(javaFactory.createExpressionFromText(newFieldName, refElt))
                    return@ref
                }
            }
        }

        for (mMethod in mClass.methods) {
            val newMethodName = model.mappings.newName(mMethod) ?: continue
            val (mParams, mReturn) = parseJvmDescriptor(mMethod.srcDesc!!)

            method@ for (pMethod in pClass.findMethodsByName(mMethod.srcName, false)) {
                val pReturn = pMethod.returnType ?: PsiTypes.voidType()
                if (pReturn.toRaw() != mReturn) continue

                val pParams = pMethod.parameterList.parameters
                if (pParams.size != mParams.size) continue
                for (i in pParams.indices) {
                    val pParam = pParams[i]
                    val mParam = mParams[i]
                    if (pParam.type.toRaw() != mParam) continue@method
                }

                forEachRefs(writers, module, pMethod) ref@{ elt ->
                    val javaRef = PsiTreeUtil.getParentOfType(elt, PsiJavaCodeReferenceElement::class.java, false)
                    if (javaRef != null) {
                        val refElt = javaRef.referenceNameElement!!
                        refElt.replace(javaFactory.createExpressionFromText(newMethodName, refElt))
                        return@ref
                    }
                }
            }
        }

        if (newClassName == null) continue
        forEachRefs(writers, module, pClass) ref@{ elt ->
            val javaRef = PsiTreeUtil.getParentOfType(elt, PsiJavaCodeReferenceElement::class.java, false)
            if (javaRef != null) {
                val newRefName = newClassName.substringAfterLast('.')
                val refElt = javaRef.referenceNameElement!!

                // TODO: Malformed type errors, seem to be only a log messages
                refElt.replace(javaFactory.createExpressionFromText(newRefName, refElt))

                val refQual = javaRef.qualifier
                if (refQual != null) {
                    val newQualName = newClassName.substringBeforeLast('.')
                    refQual.replace(javaFactory.createExpressionFromText(newQualName, refQual))
                }

                return@ref
            }
        }
    }

    WriteCommandAction.runWriteCommandAction(project, "Ravel Remapper", null, {
        writers.forEach { it.run() }
    })
}

private fun PsiType.toRaw(): String {
    return when (this) {
        is PsiArrayType -> componentType.toRaw() + "[]"
        is PsiPrimitiveType -> name
        else -> {
            val ret = canonicalText
            if (ret.contains('<')) ret.substringBefore('<') else ret
        }
    }
}

private fun parseJvmDescriptor(descriptor: String): Pair<List<String>, String> {
    var i = 0
    fun parseType(): String {
        return when (val c = descriptor[i++]) {
            'B' -> "byte"
            'C' -> "char"
            'D' -> "double"
            'F' -> "float"
            'I' -> "int"
            'J' -> "long"
            'S' -> "short"
            'Z' -> "boolean"
            'V' -> "void"
            '[' -> {
                val comp = parseType()
                "$comp[]"
            }

            'L' -> {
                val start = i
                val semicolon = descriptor.indexOf(';', start)
                val internal = descriptor.substring(start, semicolon)
                i = semicolon + 1
                internal.replace('/', '.')
            }

            else -> throw IllegalArgumentException("Unknown descriptor char: $c in $descriptor")
        }
    }

    if (descriptor.isEmpty() || descriptor[0] != '(') throw IllegalArgumentException("Bad descriptor: $descriptor")
    i = 1
    val params = mutableListOf<String>()
    while (descriptor[i] != ')') params += parseType()
    i++ // skip ')'
    val ret = parseType()
    return params to ret
}
