package lol.bai.ravel.remapper

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.psi.*

abstract class JavaRemapper : Remapper<PsiJavaFile>("java", { it as? PsiJavaFile }) {
    companion object : JavaRemapper()

    private val logger = thisLogger()

    protected lateinit var java: JavaPsiFacade
    protected lateinit var factory: PsiElementFactory

    override fun init() {
        java = JavaPsiFacade.getInstance(project)
        factory = java.elementFactory
    }

    override fun comment(pElt: PsiElement, comment: String) {
        val formatted = comment.split('\n').joinToString(prefix = "// ", separator = "\n// ")
        val pComment = factory.createCommentFromText(formatted, pElt)

        pElt.addBefore(pComment, pElt.firstChild)
    }

    protected fun signature(pMethod: PsiMethod): String {
        val mSignatureBuilder = StringBuilder()
        mSignatureBuilder.append("(")
        for (pParam in pMethod.parameterList.parameters) {
            mSignatureBuilder.append(pParam.type.toRaw())
        }
        mSignatureBuilder.append(")")
        val pReturn = pMethod.returnType ?: PsiTypes.voidType()
        mSignatureBuilder.append(pReturn.toRaw())
        val mSignature = mSignatureBuilder.toString()
        return mSignature
    }

    protected fun findMethod(pClass: PsiClass, name: String, signature: String): PsiMethod? {
        return pClass.findMethodsByName(name, false).find { signature(it) == signature }
    }

    protected fun newMethodName(pSafeElt: PsiElement, pMethod: PsiMethod): String? {
        var pSuperMethods = pMethod.findDeepestSuperMethods()
        if (pSuperMethods.isEmpty()) pSuperMethods = arrayOf(pMethod)

        val newMethodNames = hashMapOf<String, String>()
        for (pMethod in pSuperMethods) {
            val pClass = pMethod.containingClass ?: continue
            val pClassName = pClass.qualifiedName ?: continue
            val pMethodName = pMethod.name

            val key = "$pClassName#$pMethod"
            newMethodNames[key] = pMethodName

            val mClass = mClasses[pClassName] ?: continue
            val mSignature = signature(pMethod)
            val mMethod = mClass.getMethod(pMethodName, mSignature) ?: continue
            val newMethodName = mappings.remap(mMethod) ?: continue
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

    override fun remap() {
        val psi = JavaPsiFacade.getInstance(project).elementFactory

        pFile.process r@{ pRef: PsiJavaCodeReferenceElement ->
            val pRefElt = pRef.referenceNameElement as? PsiIdentifier ?: return@r
            val pTarget = pRef.resolve() ?: return@r
            val pSafeParent = pRef.parent<PsiNamedElement>() ?: pFile

            if (pTarget is PsiField) {
                val pClass = pTarget.containingClass ?: return@r
                val pClassName = pClass.qualifiedName ?: return@r
                val mClass = mClasses[pClassName] ?: return@r
                val mField = mClass.getField(pTarget.name, null) ?: return@r
                val newFieldName = mappings.remap(mField) ?: return@r

                write { pRefElt.replace(psi.createIdentifier(newFieldName)) }
                return@r
            }

            if (pTarget is PsiMethod) {
                val newMethodName = newMethodName(pSafeParent, pTarget) ?: return@r
                write { pRefElt.replace(psi.createIdentifier(newMethodName)) }
                return@r
            }

            fun replaceClass(pClass: PsiClass, pClassRef: PsiJavaCodeReferenceElement) {
                val pClassName = pClass.qualifiedName ?: return
                val mClass = mClasses[pClassName] ?: return
                var newClassName = mappings.remap(mClass) ?: return
                newClassName = replaceAllQualifier(newClassName)

                val newRefName = newClassName.substringAfterLast('.')
                write { pRefElt.replace(psi.createIdentifier(newRefName)) }

                val pRefQual = pClassRef.qualifier as? PsiJavaCodeReferenceElement
                if (pRefQual != null) {
                    val pRefQualTarget = pRefQual.resolve()
                    if (pRefQualTarget is PsiClass) {
                        replaceClass(pRefQualTarget, pRefQual)
                    } else {
                        pRefQualTarget as PsiPackage
                        val newQualName = newClassName.substringBeforeLast('.')
                        write { pRefQual.replace(psi.createPackageReferenceElement(newQualName)) }
                    }
                }
            }

            if (pTarget is PsiClass) replaceClass(pTarget, pRef)
        }

        pFile.process m@{ pMethod: PsiMethod ->
            val newMethodName = newMethodName(pMethod, pMethod) ?: return@m
            write { pMethod.name = newMethodName }
        }
    }

}
