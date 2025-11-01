package lol.bai.ravel

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.rootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.isFile
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import net.fabricmc.mappingio.tree.MappingTree.*

typealias Mappings = List<Mapping>

data class RemapperModel(
    val mappings: MutableList<Mapping> = arrayListOf(),
    val modules: MutableList<Module> = arrayListOf(),
)

private val rawQualifierSeparators = Regex("[/$]")
private fun replaceQualifier(raw: String): String {
    return raw.replace(rawQualifierSeparators, ".")
}

private fun Mappings.newName(cls: ClassMapping): String? {
    var className = cls.srcName

    for (m in this) {
        val mClass = m.tree.getClass(className) ?: return null
        className = mClass.getName(m.dest)
    }

    return if (className == cls.srcName) null else replaceQualifier(className)
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

/**
 * TODO: Currently tested with WTHIT
 *  - mixin
 *  - access widener
 *  - kotlin
 */
fun remap(project: Project, model: RemapperModel) {
    val psi = PsiManager.getInstance(project)
    val java = JavaPsiFacade.getInstance(project)
    val javaFactory = java.elementFactory

    val mClasses = linkedMapOf<String, ClassMapping>()
    model.mappings.first().tree.classes.forEach {
        mClasses[replaceQualifier(it.srcName)] = it
    }

    fun newMethodName(pMethod: PsiMethod): String? {
        val pMethod = pMethod.findDeepestSuperMethods().firstOrNull() ?: pMethod
        val pClass = pMethod.containingClass ?: return null
        val pClassName = pClass.qualifiedName ?: return null
        val mClass = mClasses[pClassName] ?: return null

        val mSignatureBuilder = StringBuilder()
        mSignatureBuilder.append("(")
        for (pParam in pMethod.parameterList.parameters) {
            mSignatureBuilder.append(pParam.type.toRaw())
        }
        mSignatureBuilder.append(")")
        val pReturn = pMethod.returnType ?: PsiTypes.voidType()
        mSignatureBuilder.append(pReturn.toRaw())
        val mSignature = mSignatureBuilder.toString()

        val mMethod = mClass.getMethod(pMethod.name, mSignature) ?: return null
        val newMethodName = model.mappings.newName(mMethod) ?: return null

        return newMethodName
    }

    val writers = arrayListOf<Runnable>()

    for (module in model.modules) for (root in module.rootManager.sourceRoots) VfsUtil.iterateChildrenRecursively(root, null) vf@{ vf ->
        if (!vf.isFile) return@vf true
        if (vf.extension != "java") return@vf true

        val javaFile = psi.findFile(vf) ?: return@vf true
        if (javaFile !is PsiJavaFile) return@vf true

        PsiTreeUtil.processElements(javaFile, PsiJavaCodeReferenceElement::class.java) r@{ pRef ->
            val pTarget = pRef.resolve() ?: return@r true

            if (pTarget is PsiField) {
                val pClass = pTarget.containingClass ?: return@r true
                val pClassName = pClass.qualifiedName ?: return@r true
                val mClass = mClasses[pClassName] ?: return@r true
                val mField = mClass.getField(pTarget.name, null) ?: return@r true
                val newFieldName = model.mappings.newName(mField) ?: return@r true

                val pRefElt = pRef.referenceNameElement!!
                assert(pRefElt is PsiIdentifier)

                writers.add {
                    pRefElt.replace(javaFactory.createIdentifier(newFieldName))
                }
                return@r true
            }

            if (pTarget is PsiMethod) {
                val newMethodName = newMethodName(pTarget) ?: return@r true

                val pRefElt = pRef.referenceNameElement!!
                assert(pRefElt is PsiIdentifier)

                writers.add {
                    pRefElt.replace(javaFactory.createIdentifier(newMethodName))
                }
                return@r true
            }

            fun replaceClass(pClass: PsiClass, pClassRef: PsiJavaCodeReferenceElement) {
                val pClassName = pClass.qualifiedName ?: return
                val mClass = mClasses[pClassName] ?: return
                val newClassName = model.mappings.newName(mClass) ?: return

                val pRefElt = pClassRef.referenceNameElement!!
                assert(pRefElt is PsiIdentifier)

                val newRefName = newClassName.substringAfterLast('.')
                writers.add {
                    pRefElt.replace(javaFactory.createIdentifier(newRefName))
                }

                val pRefQual = pClassRef.qualifier
                if (pRefQual != null) {
                    assert(pRefQual is PsiJavaCodeReferenceElement)
                    pRefQual as PsiJavaCodeReferenceElement

                    val pRefQualTarget = pRefQual.resolve()
                    if (pRefQualTarget is PsiClass) {
                        replaceClass(pRefQualTarget, pRefQual)
                    } else {
                        assert(pRefQualTarget is PsiPackage)

                        val newQualName = newClassName.substringBeforeLast('.')
                        writers.add {
                            pRefQual.replace(javaFactory.createPackageReferenceElement(newQualName))
                        }
                    }
                }
                return
            }

            if (pTarget is PsiClass) replaceClass(pTarget, pRef)

            return@r true
        }

        PsiTreeUtil.processElements(javaFile, PsiMethod::class.java) m@{ pMethod ->
            val newMethodName = newMethodName(pMethod) ?: return@m true
            writers.add {
                pMethod.name = newMethodName
            }
            return@m true
        }

        return@vf true
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

@Suppress("UnstableApiUsage")
private fun PsiType.toRaw(): String {
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

