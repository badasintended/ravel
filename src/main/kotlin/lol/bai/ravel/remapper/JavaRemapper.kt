package lol.bai.ravel.remapper

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.psi.*
import com.intellij.psi.javadoc.PsiDocTag
import lol.bai.ravel.mapping.rawQualifierSeparators
import lol.bai.ravel.psi.implicitly
import lol.bai.ravel.psi.jvmDesc
import lol.bai.ravel.psi.jvmName
import lol.bai.ravel.psi.jvmRaw
import lol.bai.ravel.util.linkedSetMultiMap

private val regex = Regex("^.*\\.java$")

open class JavaRemapper : JvmRemapper<PsiJavaFile>(regex, { it as? PsiJavaFile }) {
    private val logger = thisLogger()

    protected lateinit var java: JavaPsiFacade
    protected lateinit var factory: PsiElementFactory

    override fun init(): Boolean {
        if (!super.init()) return false
        java = JavaPsiFacade.getInstance(project)
        factory = java.elementFactory
        return true
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

    protected fun findClass(jvmName: String): PsiClass? {
        return java.findClass(jvmName.replace(rawQualifierSeparators, "."), scope)
    }

    protected fun findMethod(pClass: PsiClass, name: String, signature: String): PsiMethod? {
        return pClass.findMethodsByName(name, false).find { it.jvmDesc == signature }
    }

    override fun remap() {
        val nonFqnClassNames = hashMapOf<String, String>()
        pFile.process c@{ pClass: PsiClass ->
            val className = pClass.name ?: return@c
            val classJvmName = pClass.jvmName ?: return@c
            nonFqnClassNames[className] = classJvmName
        }

        // TODO: solve in-project class remapping
        // pFile.process c@{ pClass: PsiClass ->
        //     val mClass = mTree.get(pClass) ?: return@c
        //     val newClassName = mClass.newFullPeriodName ?: return@c
        //     write { pClass.setName(newClassName) }
        // }

        pFile.process f@{ pField: PsiField ->
            val newFieldName = remap(pField) ?: return@f
            write { pField.name = newFieldName }
        }

        pFile.process m@{ pMethod: PsiMethod ->
            val newMethodName = remap(pMethod, pMethod) ?: return@m
            write { pMethod.name = newMethodName }
        }

        pFile.process r@{ pRecordComponent: PsiRecordComponent ->
            val pClass = pRecordComponent.containingClass ?: return@r
            val className = pClass.qualifiedName ?: return@r

            val recordComponentName = pRecordComponent.name
            val recordComponentDesc = pRecordComponent.type.jvmRaw

            val getterDesc = "()${recordComponentDesc}"
            val pGetter = pClass.findMethodsByName(recordComponentName, true)

            val newGetterName = linkedMapOf<String, String>()
            for (pGetter in pGetter) {
                val pGetterClass = pGetter.containingClass ?: continue
                if (pGetterClass == pClass) continue
                if (pGetter.jvmDesc != getterDesc) continue
                val key = pGetterClass.name + "#" + pGetter.name
                newGetterName[key] = remap(pClass, pGetter) ?: pGetter.name
            }

            if (newGetterName.isEmpty()) return@r

            val uniqueNewGetterNames = newGetterName.values.toSet()
            if (uniqueNewGetterNames.size != 1) {
                logger.warn("$className: record component '$recordComponentName' overrides methods with different new names")
                val comment = newGetterName.map { (k, v) -> "$k -> $v" }.joinToString(separator = "\n")
                write { comment(pClass, "TODO(Ravel): record component '$recordComponentName' overrides methods with different new names\n$comment") }
                return@r
            }

            val uniqueNewGetterName = uniqueNewGetterNames.first()
            mTree.getOrPut(pClass).putField(recordComponentName, uniqueNewGetterName)
            write { pRecordComponent.name = uniqueNewGetterName }
        }

        val pStaticImportUsages = linkedSetMultiMap<String, PsiMember>()
        pFile.process r@{ pRef: PsiJavaCodeReferenceElement ->
            if (pRef is PsiImportStaticReferenceElement) return@r
            val pRefId = pRef.referenceNameElement as? PsiIdentifier ?: return@r

            val pTarget = pRef.resolve() ?: return@r
            val pSafeParent = pRef.parent<PsiNamedElement>() ?: pFile

            if (pTarget is PsiField) {
                if (pTarget.implicitly(PsiModifier.STATIC) && pRef.qualifier == null) {
                    pStaticImportUsages.put(pTarget.name, pTarget)
                }
                val newFieldName = remap(pTarget) ?: return@r
                write { pRefId.replace(factory.createIdentifier(newFieldName)) }
                return@r
            }

            if (pTarget is PsiMethod) {
                if (pTarget.implicitly(PsiModifier.STATIC) && pRef.qualifier == null) {
                    pStaticImportUsages.put(pTarget.name, pTarget)
                }
                val newMethodName = remap(pSafeParent, pTarget) ?: return@r
                write { pRefId.replace(factory.createIdentifier(newMethodName)) }
                return@r
            }

            fun replaceClass(pClass: PsiClass, pClassRef: PsiJavaCodeReferenceElement) {
                val pClassRefId = pClassRef.referenceNameElement as? PsiIdentifier ?: return
                val mClass = mTree.get(pClass) ?: return
                val newJvmClassName = mClass.newName ?: return
                val newClassName = mClass.newFullPeriodName ?: return
                val newRefName = newClassName.substringAfterLast('.')

                val pRefQual = pClassRef.qualifier as? PsiJavaCodeReferenceElement
                if (pRefQual != null) {
                    val pRefQualTarget = pRefQual.resolve()
                    if (pRefQualTarget is PsiClass) {
                        replaceClass(pRefQualTarget, pRefQual)
                        write { pClassRefId.replace(factory.createIdentifier(newRefName)) }
                        return
                    } else {
                        pRefQualTarget as PsiPackage
                        val newQualName = newClassName.substringBeforeLast('.')
                        write { pRefQual.replace(factory.createPackageReferenceElement(newQualName)) }
                    }
                }

                if (nonFqnClassNames.contains(newRefName) && nonFqnClassNames[newRefName] != newJvmClassName) {
                    write { pClassRef.replace(factory.createReferenceFromText(newClassName, pClassRef)) }
                    return
                }

                nonFqnClassNames[newRefName] = newJvmClassName
                write { pClassRefId.replace(factory.createIdentifier(newRefName)) }
            }

            if (pTarget is PsiClass) replaceClass(pTarget, pRef)
        }

        pFile.process r@{ pRef: PsiImportStaticReferenceElement ->
            val pRefId = pRef.referenceNameElement as? PsiIdentifier ?: return@r
            val pStatement = pRef.parent<PsiImportStaticStatement>() ?: return@r
            val pClass = pRef.classReference.resolve() as? PsiClass ?: return@r
            val memberName = pRefId.text

            val pUsages = pStaticImportUsages[memberName].orEmpty().ifEmpty {
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

            write { pRefId.replace(factory.createIdentifier(uniqueNewMemberNames.first())) }
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
