package lol.bai.ravel.remapper

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiTypes

object JavaRemapper : Remapper<PsiJavaFile>("java", { it as? PsiJavaFile }) {

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
        val newMethodName = mappings.newName(mMethod) ?: return null
        return newMethodName
    }

    override fun remap(project: Project, mappings: Mappings, mClasses: ClassMappings, pFile: PsiJavaFile, writers: Writers) {
        val psi = JavaPsiFacade.getInstance(project).elementFactory

        pFile.process r@{ pRef: PsiJavaCodeReferenceElement ->
            val pTarget = pRef.resolve() ?: return@r

            if (pTarget is PsiField) {
                val pClass = pTarget.containingClass ?: return@r
                val pClassName = pClass.qualifiedName ?: return@r
                val mClass = mClasses[pClassName] ?: return@r
                val mField = mClass.getField(pTarget.name, null) ?: return@r
                val newFieldName = mappings.newName(mField) ?: return@r

                val pRefElt = pRef.referenceNameElement as PsiIdentifier
                writers.add { pRefElt.replace(psi.createIdentifier(newFieldName)) }
                return@r
            }

            if (pTarget is PsiMethod) {
                val newMethodName = newMethodName(mappings, mClasses, pTarget) ?: return@r

                val pRefElt = pRef.referenceNameElement as PsiIdentifier
                writers.add { pRefElt.replace(psi.createIdentifier(newMethodName)) }
                return@r
            }

            fun replaceClass(pClass: PsiClass, pClassRef: PsiJavaCodeReferenceElement) {
                val pClassName = pClass.qualifiedName ?: return
                val mClass = mClasses[pClassName] ?: return
                var newClassName = mappings.newName(mClass) ?: return
                newClassName = replaceAllQualifier(newClassName)

                val pRefElt = pClassRef.referenceNameElement as PsiIdentifier
                val newRefName = newClassName.substringAfterLast('.')
                writers.add { pRefElt.replace(psi.createIdentifier(newRefName)) }

                val pRefQual = pClassRef.qualifier as? PsiJavaCodeReferenceElement
                if (pRefQual != null) {
                    val pRefQualTarget = pRefQual.resolve()
                    if (pRefQualTarget is PsiClass) {
                        replaceClass(pRefQualTarget, pRefQual)
                    } else {
                        pRefQualTarget as PsiPackage
                        val newQualName = newClassName.substringBeforeLast('.')
                        writers.add { pRefQual.replace(psi.createPackageReferenceElement(newQualName)) }
                    }
                }
            }

            if (pTarget is PsiClass) replaceClass(pTarget, pRef)
        }

        pFile.process m@{ pMethod: PsiMethod ->
            val newMethodName = newMethodName(mappings, mClasses, pMethod) ?: return@m
            writers.add { pMethod.name = newMethodName }
        }
    }

}
