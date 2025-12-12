package lol.bai.ravel.remapper

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.psi.*
import com.intellij.psi.javadoc.PsiDocTagValue
import com.intellij.psi.util.childrenOfType
import lol.bai.ravel.mapping.rawQualifierSeparators
import lol.bai.ravel.psi.implicitly
import lol.bai.ravel.psi.jvmDesc
import lol.bai.ravel.psi.jvmName
import lol.bai.ravel.psi.jvmRaw
import lol.bai.ravel.util.linkedSetMultiMap

class JavaRemapperFactory : ExtensionRemapperFactory(::JavaRemapper, "java")
open class JavaRemapper : JvmRemapper<PsiJavaFile>({ it as? PsiJavaFile }) {
    private val logger = thisLogger()

    protected abstract inner class JavaStage : JavaRecursiveElementWalkingVisitor(), Stage {
        override fun invoke() = pFile.accept(this)
    }

    override fun stages() = listOf(
        collectImports,
        remapClassName,
        remapPackage,
        remapMembers,
        remapReferences,
        remapStaticImports,
        remapDocTagValues,
        renameFile,
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

    private val importedClasses = linkedSetOf<String>()
    private val collectImports = object : JavaStage() {
        override fun visitImportStatement(pStatement: PsiImportStatement) {
            super.visitImportStatement(pStatement)
            if (pStatement.isOnDemand) return
            val fqn = pStatement.qualifiedName ?: return
            importedClasses.add(fqn)
        }
    }
    private val topLevelClasses = linkedMapOf<PsiClass, String>()
    private val nonFqnClassNames = hashMapOf<String, String>()
    private val remapClassName = object : JavaStage() {
        override fun visitClass(pClass: PsiClass) {
            super.visitClass(pClass)
            val className = pClass.name ?: return
            val classJvmName = pClass.jvmName ?: return

            val mClass = mTree.get(pClass)
            val newClassJvmName = mClass?.newName
            val newClassName = mClass?.newFullPeriodName?.substringAfterLast('.')

            nonFqnClassNames[newClassName ?: className] = newClassJvmName ?: classJvmName
            if (pClass.containingClass == null) topLevelClasses[pClass] = newClassJvmName ?: classJvmName

            if (newClassName == null) return
            val pId = pClass.nameIdentifier ?: return
            write { pId.replace(factory.createIdentifier(newClassName)) }
        }
    }
    private var newPackageName: String? = null
    private val remapPackage = object : JavaStage() {
        override fun visitPackageStatement(pStatement: PsiPackageStatement) {
            super.visitPackageStatement(pStatement)

            val newPackageNames = topLevelClasses.values.map { it.substringBeforeLast('/') }.toSet()
            if (newPackageNames.size != 1) {
                logger.warn("File contains classes with different new packages")
                val comment = topLevelClasses.map { (k, v) -> "${k.name} -> $v" }.joinToString(separator = "\n")
                write { comment(pStatement, "TODO(Ravel): file contains classes with different new packages\n$comment") }
                return
            }

            newPackageName = newPackageNames.first().replace('/', '.')
            write { pStatement.replace(factory.createPackageStatement(newPackageName!!)) }
        }
    }
    private val remapMembers = object : JavaStage() {
        override fun visitField(pField: PsiField) {
            super.visitField(pField)
            val pId = pField.nameIdentifier
            val newFieldName = remap(pField) ?: return
            write { pId.replace(factory.createIdentifier(newFieldName)) }
        }

        override fun visitMethod(pMethod: PsiMethod) {
            super.visitMethod(pMethod)
            val pClass = pMethod.containingClass ?: return
            val pId = pMethod.nameIdentifier ?: return

            val newMethodName =
                if (pMethod.isConstructor) mTree.get(pClass)?.newFullPeriodName?.substringAfterLast('.')
                else remap(pMethod, pMethod)
            if (newMethodName == null) return

            write { pId.replace(factory.createIdentifier(newMethodName)) }
        }

        override fun visitRecordComponent(pRecordComponent: PsiRecordComponent) {
            super.visitRecordComponent(pRecordComponent)
            val pId = pRecordComponent.nameIdentifier ?: return
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
            if (recordComponentName == uniqueNewGetterName) return

            write { pId.replace(factory.createIdentifier(uniqueNewGetterName)) }
            rerun { mTree.getOrPut(pClass).putField(recordComponentName, uniqueNewGetterName) }
        }
    }
    private val pStaticImportUsages = linkedSetMultiMap<String, PsiMember>()
    private val remapReferences = object : JavaStage() {
        override fun visitReferenceElement(pRef: PsiJavaCodeReferenceElement) {
            super.visitReferenceElement(pRef)
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

            if (pTarget is PsiClass) {
                val className = pTarget.qualifiedName ?: return
                val pClassRefId = pRef.referenceNameElement as? PsiIdentifier ?: return
                val mClass = mTree.get(pTarget) ?: return
                val newJvmClassName = mClass.newName ?: return
                val newClassName = mClass.newFullPeriodName ?: return
                val newRefName = newClassName.substringAfterLast('.')

                var isQualified = false
                val pRefQual = pRef.qualifier as? PsiJavaCodeReferenceElement
                if (pRefQual != null) {
                    isQualified = true
                    val pRefQualTarget = pRefQual.resolve()
                    if (pRefQualTarget is PsiPackage) {
                        val newQualName = newClassName.substringBeforeLast('.')
                        write { pRefQual.replace(factory.createPackageReferenceElement(newQualName)) }
                    }
                }

                if (nonFqnClassNames.contains(newRefName) && nonFqnClassNames[newRefName] != newJvmClassName) {
                    write { pRef.replace(factory.createReferenceFromText(newClassName, pRef)) }
                    return
                }

                nonFqnClassNames[newRefName] = newJvmClassName
                write { pClassRefId.replace(factory.createIdentifier(newRefName)) }

                if (!isQualified && pRef.parent !is PsiImportStatement && pTarget.containingFile != pFile && !importedClasses.contains(className)) {
                    importedClasses.add(className)

                    write {
                        val pDummyClass = pFileFactory.createFileFromText(
                            "_RavelDummy_.java", JavaFileType.INSTANCE, """
                                package ${newClassName.substringBeforeLast('.')};
                                public class $newRefName {}
                            """.trimIndent()
                        ).childrenOfType<PsiClass>().first()

                        pFile.importClass(pDummyClass)
                    }
                }
                return
            }
        }
    }
    private val remapStaticImports = object : JavaStage() {
        override fun visitImportStaticReferenceElement(pRef: PsiImportStaticReferenceElement) {
            super.visitImportStaticReferenceElement(pRef)
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
            super.visitDocTagValue(pValue)
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
    private val renameFile = Stage s@{ renameFile(newPackageName, topLevelClasses) }
}
