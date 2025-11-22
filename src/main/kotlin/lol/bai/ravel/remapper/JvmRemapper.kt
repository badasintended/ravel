package lol.bai.ravel.remapper

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.psi.*
import lol.bai.ravel.psi.jvmDesc

abstract class JvmRemapper<F : PsiClassOwner>(
    caster: (PsiFile?) -> F?
) : PsiRemapper<F>(caster) {
    private val logger = thisLogger()

    protected fun remap(pField: PsiField): String? {
        val pClass = pField.containingClass ?: return null
        val mClass = mTree.get(pClass) ?: return null

        val fieldName = pField.name
        val mField = mClass.getField(fieldName) ?: return null
        val newFieldName = mField.newName ?: return null
        return if (newFieldName == fieldName) null else newFieldName
    }

    protected fun remap(pSafeElt: PsiElement, pMethod: PsiMethod): String? {
        var pSuperMethods = pMethod.findDeepestSuperMethods()
        if (pSuperMethods.isEmpty()) pSuperMethods = arrayOf(pMethod)

        val newMethodNames = linkedMapOf<String, String>()
        for (pMethod in pSuperMethods) {
            val pClass = pMethod.containingClass ?: continue
            val pClassName = pClass.qualifiedName ?: continue
            val pMethodName = pMethod.name

            val key = "$pClassName#$pMethod"
            newMethodNames[key] = pMethodName

            val mClass = mTree.get(pClass) ?: continue
            val mSignature = pMethod.jvmDesc
            val mMethod = mClass.getMethod(pMethodName, mSignature) ?: continue
            val newMethodName = mMethod.newName ?: continue
            newMethodNames[key] = newMethodName
        }

        if (newMethodNames.isEmpty()) return null
        if (newMethodNames.size != pSuperMethods.size) {
            logger.warn("could not resolve all method origins")
            write { comment(pSafeElt, "TODO(Ravel): could not resolve all method origins") }
            return null
        }

        val uniqueNewMethodNames = newMethodNames.values.toSet()
        if (uniqueNewMethodNames.size != 1) {
            logger.warn("method origins have different new names")
            val comment = newMethodNames.map { (k, v) -> "$k -> $v" }.joinToString(separator = "\n")
            write { comment(pSafeElt, "TODO(Ravel): method origins have different new names\n$comment") }
            return null
        }

        val newMethodName = uniqueNewMethodNames.first()
        return if (newMethodName == pMethod.name) null else newMethodName
    }
}
