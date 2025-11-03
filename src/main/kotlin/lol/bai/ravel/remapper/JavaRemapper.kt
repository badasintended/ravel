package lol.bai.ravel.remapper

import com.intellij.openapi.project.Project
import com.intellij.psi.*

object JavaRemapper : Remapper<PsiJavaFile>("java", { it as? PsiJavaFile }) {

    fun comment(psi: PsiElementFactory, elt: PsiElement, comment: String) {
        val pComment = psi.createCommentFromText("// $comment", elt)
        elt.addBefore(pComment, elt.firstChild)
    }

    fun signature(pMethod: PsiMethod): String {
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

    fun newMethodName(mappings: Mappings, mClasses: ClassMappings, pMethod: PsiMethod): String? {
        val pMethod = pMethod.findDeepestSuperMethods().firstOrNull() ?: pMethod
        val pClass = pMethod.containingClass ?: return null
        val pClassName = pClass.qualifiedName ?: return null
        val mClass = mClasses[pClassName] ?: return null
        val mSignature = signature(pMethod)
        val mMethod = mClass.getMethod(pMethod.name, mSignature) ?: return null
        val newMethodName = mappings.map(mMethod) ?: return null
        return newMethodName
    }

    override fun remap(project: Project, mappings: Mappings, mClasses: ClassMappings, pFile: PsiJavaFile, write: Writer) {
        val psi = JavaPsiFacade.getInstance(project).elementFactory

        pFile.process r@{ pRef: PsiJavaCodeReferenceElement ->
            val pTarget = pRef.resolve() ?: return@r

            if (pTarget is PsiField) {
                val pClass = pTarget.containingClass ?: return@r
                val pClassName = pClass.qualifiedName ?: return@r
                val mClass = mClasses[pClassName] ?: return@r
                val mField = mClass.getField(pTarget.name, null) ?: return@r
                val newFieldName = mappings.map(mField) ?: return@r

                val pRefElt = pRef.referenceNameElement as PsiIdentifier
                write { pRefElt.replace(psi.createIdentifier(newFieldName)) }
                return@r
            }

            if (pTarget is PsiMethod) {
                val newMethodName = newMethodName(mappings, mClasses, pTarget) ?: return@r

                val pRefElt = pRef.referenceNameElement as PsiIdentifier
                write { pRefElt.replace(psi.createIdentifier(newMethodName)) }
                return@r
            }

            fun replaceClass(pClass: PsiClass, pClassRef: PsiJavaCodeReferenceElement) {
                val pClassName = pClass.qualifiedName ?: return
                val mClass = mClasses[pClassName] ?: return
                var newClassName = mappings.map(mClass) ?: return
                newClassName = replaceAllQualifier(newClassName)

                val pRefElt = pClassRef.referenceNameElement as PsiIdentifier
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
            val newMethodName = newMethodName(mappings, mClasses, pMethod) ?: return@m
            write { pMethod.name = newMethodName }
        }
    }

}
