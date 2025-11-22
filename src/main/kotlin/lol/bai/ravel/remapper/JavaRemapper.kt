package lol.bai.ravel.remapper

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.psi.*
import com.intellij.psi.javadoc.PsiDocTagValue
import lol.bai.ravel.mapping.rawQualifierSeparators
import lol.bai.ravel.psi.implicitly
import lol.bai.ravel.psi.jvmDesc
import lol.bai.ravel.psi.jvmName
import lol.bai.ravel.psi.jvmRaw
import lol.bai.ravel.util.linkedSetMultiMap

class JavaRemapperFactory : ConstRemapperFactory(::JavaRemapper, "java")
open class JavaRemapper : JvmRemapper<PsiJavaFile>({ it as? PsiJavaFile }) {
    private val logger = thisLogger()

    protected abstract inner class JavaStage : JavaRecursiveElementWalkingVisitor(), Stage {
        override fun invoke() = pFile.accept(this)
    }

    override fun stages() = listOf<Stage>(
        collectClassNames,
        remapMembers,
        remapReferences,
        remapStaticImports,
        remapDocTagValues,
    )

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

    // TODO: solve in-project class remapping
    private val nonFqnClassNames = hashMapOf<String, String>()
    private val collectClassNames = object : JavaStage() {
        override fun visitClass(pClass: PsiClass) {
            val className = pClass.name ?: return
            val classJvmName = pClass.jvmName ?: return
            nonFqnClassNames[className] = classJvmName
        }
    }
    private val remapMembers = object : JavaStage() {
        override fun visitField(pField: PsiField) {
            val newFieldName = remap(pField) ?: return
            write { pField.name = newFieldName }
        }

        override fun visitMethod(pMethod: PsiMethod) {
            val newMethodName = remap(pMethod, pMethod) ?: return
            write { pMethod.name = newMethodName }
        }

        override fun visitRecordComponent(pRecordComponent: PsiRecordComponent) {
            val pClass = pRecordComponent.containingClass ?: return
            val className = pClass.qualifiedName ?: return

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

            if (newGetterName.isEmpty()) return

            val uniqueNewGetterNames = newGetterName.values.toSet()
            if (uniqueNewGetterNames.size != 1) {
                logger.warn("$className: record component '$recordComponentName' overrides methods with different new names")
                val comment = newGetterName.map { (k, v) -> "$k -> $v" }.joinToString(separator = "\n")
                write { comment(pClass, "TODO(Ravel): record component '$recordComponentName' overrides methods with different new names\n$comment") }
                return
            }

            val uniqueNewGetterName = uniqueNewGetterNames.first()
            mTree.getOrPut(pClass).putField(recordComponentName, uniqueNewGetterName)
            write { pRecordComponent.name = uniqueNewGetterName }
        }
    }
    private val pStaticImportUsages = linkedSetMultiMap<String, PsiMember>()
    private val remapReferences = object : JavaStage() {
        override fun visitReferenceElement(pRef: PsiJavaCodeReferenceElement) {
            if (pRef is PsiImportStaticReferenceElement) return
            val pRefId = pRef.referenceNameElement as? PsiIdentifier ?: return

            val pTarget = pRef.resolve() ?: return
            val pSafeParent = pRef.parent<PsiNamedElement>() ?: pFile

            if (pTarget is PsiField) {
                if (pTarget.implicitly(PsiModifier.STATIC) && pRef.qualifier == null) {
                    pStaticImportUsages.put(pTarget.name, pTarget)
                }
                val newFieldName = remap(pTarget) ?: return
                write { pRefId.replace(factory.createIdentifier(newFieldName)) }
                return
            }

            if (pTarget is PsiMethod) {
                if (pTarget.implicitly(PsiModifier.STATIC) && pRef.qualifier == null) {
                    pStaticImportUsages.put(pTarget.name, pTarget)
                }
                val newMethodName = remap(pSafeParent, pTarget) ?: return
                write { pRefId.replace(factory.createIdentifier(newMethodName)) }
                return
            }

            fun replaceClass(pClass: PsiClass, pClassRef: PsiJavaCodeReferenceElement) {
                pClassRef.parameterList?.typeParameterElements?.forEach p@{ pParam ->
                    val pParamRef = pParam.innermostComponentReferenceElement ?: return@p
                    val pParamClass = pParamRef.resolve() as? PsiClass ?: return@p
                    replaceClass(pParamClass, pParamRef)
                }

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
    }
    private val remapStaticImports = object : JavaStage() {
        override fun visitImportStaticReferenceElement(pRef: PsiImportStaticReferenceElement) {
            val pRefId = pRef.referenceNameElement as? PsiIdentifier ?: return
            val pStatement = pRef.parent<PsiImportStaticStatement>() ?: return
            val pClass = pRef.classReference.resolve() as? PsiClass ?: return
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
                return
            }

            write { pRefId.replace(factory.createIdentifier(uniqueNewMemberNames.first())) }
            return
        }
    }
    private val remapDocTagValues = object : JavaStage() {
        override fun visitDocTagValue(pValue: PsiDocTagValue) {
            val pRef = pValue.reference ?: return
            val pRefTarget = pRef.resolve() ?: return

            if (pRefTarget is PsiField) {
                val newFieldName = remap(pRefTarget) ?: return
                write { pRef.handleElementRename(newFieldName) }
                return
            }

            if (pRefTarget is PsiMethod) {
                val pSafeElt = pValue.parent<PsiMember>() ?: pFile
                val newMethodName = remap(pSafeElt, pRefTarget) ?: return
                write { pRef.handleElementRename(newMethodName) }
                return
            }
        }
    }
}
