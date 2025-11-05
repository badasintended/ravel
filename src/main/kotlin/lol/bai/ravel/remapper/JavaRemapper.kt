package lol.bai.ravel.remapper

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.psi.*
import com.intellij.psi.javadoc.PsiDocTag
import fleet.util.Multimap
import lol.bai.ravel.mapping.rawQualifierSeparators
import lol.bai.ravel.psi.implicitly
import lol.bai.ravel.psi.jvmDesc

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
        var pAnchor: PsiElement? = null
        comment.split('\n').forEach { line ->
            val pComment = factory.createCommentFromText("// $line", pElt)
            pAnchor =
                if (pAnchor == null) pElt.addBefore(pComment, pElt.firstChild)
                else pElt.addAfter(pComment, pAnchor)
        }
    }

    protected fun remap(pField: PsiField): String? {
        val pClass = pField.containingClass ?: return null
        val mClass = mTree.get(pClass) ?: return null

        val fieldName = pField.name
        val mField = mClass.getField(fieldName) ?: return null
        val newFieldName = mField.newName ?: return null
        return if (newFieldName == fieldName) null else newFieldName
    }

    protected fun findClass(jvmName: String): PsiClass? {
        return java.findClass(jvmName.replace(rawQualifierSeparators, "."), scope)
    }

    protected fun findMethod(pClass: PsiClass, name: String, signature: String): PsiMethod? {
        return pClass.findMethodsByName(name, false).find { it.jvmDesc == signature }
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

    override fun remap() {
//        pFile.process c@{ pClass: PsiClass ->
//            val mClass = mTree.get(pClass) ?: return@c
//            val newClassName = mClass.newFullPeriodName ?: return@c
//            write { pClass.setName(newClassName) }
//        }
//
//        pFile.process f@{ pField: PsiField ->
//            val newFieldName = remap(pField) ?: return@f
//            write { pField.name = newFieldName }
//        }

        pFile.process m@{ pMethod: PsiMethod ->
            val newMethodName = remap(pMethod, pMethod) ?: return@m
            write { pMethod.name = newMethodName }
        }

        val pStaticImportUsages = Multimap<String, PsiMember> { LinkedHashSet() }
        pFile.process r@{ pRef: PsiJavaCodeReferenceElement ->
            if (pRef is PsiImportStaticReferenceElement) return@r
            val pRefElt = pRef.referenceNameElement as? PsiIdentifier ?: return@r

            val pTarget = pRef.resolve() ?: return@r
            val pSafeParent = pRef.parent<PsiNamedElement>() ?: pFile

            if (pTarget is PsiField) {
                if (pTarget.implicitly(PsiModifier.STATIC) && pRef.qualifier == null) {
                    pStaticImportUsages.put(pTarget.name, pTarget)
                }
                val newFieldName = remap(pTarget) ?: return@r
                write { pRefElt.replace(factory.createIdentifier(newFieldName)) }
                return@r
            }

            if (pTarget is PsiMethod) {
                if (pTarget.implicitly(PsiModifier.STATIC) && pRef.qualifier == null) {
                    pStaticImportUsages.put(pTarget.name, pTarget)
                }
                val newMethodName = remap(pSafeParent, pTarget) ?: return@r
                write { pRefElt.replace(factory.createIdentifier(newMethodName)) }
                return@r
            }

            fun replaceClass(pClass: PsiClass, pClassRef: PsiJavaCodeReferenceElement) {
                val mClass = mTree.get(pClass) ?: return
                val newClassName = mClass.newFullPeriodName ?: return

                val newRefName = newClassName.substringAfterLast('.')
                write { pRefElt.replace(factory.createIdentifier(newRefName)) }

                val pRefQual = pClassRef.qualifier as? PsiJavaCodeReferenceElement
                if (pRefQual != null) {
                    val pRefQualTarget = pRefQual.resolve()
                    if (pRefQualTarget is PsiClass) {
                        replaceClass(pRefQualTarget, pRefQual)
                    } else {
                        pRefQualTarget as PsiPackage
                        val newQualName = newClassName.substringBeforeLast('.')
                        write { pRefQual.replace(factory.createPackageReferenceElement(newQualName)) }
                    }
                }
            }

            if (pTarget is PsiClass) replaceClass(pTarget, pRef)
        }

        pFile.process r@{ pRef: PsiImportStaticReferenceElement ->
            val pRefElt = pRef.referenceNameElement as? PsiIdentifier ?: return@r
            val pStatement = pRef.parent<PsiImportStaticStatement>() ?: return@r
            val pClass = pRef.classReference.resolve() as? PsiClass ?: return@r
            val memberName = pRefElt.text

            val pUsages = pStaticImportUsages[memberName].ifEmpty {
                val pMembers = arrayListOf<PsiMember>()
                pMembers.addAll(pClass.findMethodsByName(memberName, false))
                val pField = pClass.findFieldByName(memberName, false)
                if (pField != null) pMembers.add(pField)
                pMembers
            }

            val newMemberNames = linkedMapOf<String, String>()
            pUsages.forEach {
                if (it is PsiMethod) newMemberNames["method " + it.name + it.jvmDesc] = mTree.get(it)?.newName ?: it.name
                else if (it is PsiField) newMemberNames["field " + it.name] = mTree.get(it)?.newName ?: it.name
            }

            val uniqueNewMemberNames = newMemberNames.values.toSet()
            if (uniqueNewMemberNames.size != 1) {
                logger.warn("ambiguous static import, members with name $memberName have different new names")
                val comment = newMemberNames.map { (k, v) -> "$k -> $v" }.joinToString(separator = "\n")
                write { comment(pStatement, "TODO(Ravel): ambiguous static import, members with name $memberName have different new names\n$comment") }
                return@r
            }

            write { pRefElt.replace(factory.createIdentifier(uniqueNewMemberNames.first())) }
            return@r
        }

        pFile.process d@{ pDocTag: PsiDocTag ->
            val pValue = pDocTag.valueElement ?: return@d
            val pRef = pValue.reference ?: return@d
            val pRefTarget = pRef.resolve() ?: return@d

            if (pRefTarget is PsiField) {
                val newFieldName = remap(pRefTarget) ?: return@d
                write { pRef.handleElementRename(newFieldName) }
                return@d
            }

            if (pRefTarget is PsiMethod) {
                val pSafeElt = pDocTag.parent<PsiMember>() ?: pFile
                val newMethodName = remap(pSafeElt, pRefTarget) ?: return@d
                write { pRef.handleElementRename(newMethodName) }
                return@d
            }
        }
    }

}
