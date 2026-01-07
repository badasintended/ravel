package lol.bai.ravel.remapper

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.*
import com.intellij.psi.PsiModifier.ModifierConstant
import com.intellij.psi.util.childrenOfType
import lol.bai.ravel.mapping.*
import lol.bai.ravel.util.Holder
import lol.bai.ravel.util.put

private val jvmNameHolder = Holder.key<String>("jvmName")
private val jvmDescHolder = Holder.key<String>("jvmDesc")

abstract class JvmRemapper<F : PsiClassOwner>(
    caster: (PsiFile?) -> F?
) : PsiRemapper<F>(caster) {
    private val logger = thisLogger()

    fun jvmStages() = listOf(collectImports)
    protected val importedClasses = linkedSetOf<String>()
    protected abstract val collectImports: Stage

    private val pDummies by global("jvm.pDummies") { mutableMapOf<String, PsiElement>() }
    protected fun resolveReference(pRef: PsiJavaCodeReferenceElement): PsiElement? {
        val resolved = pRef.resolve()
        if (resolved != null) return resolved

        val pParent = pRef.parent

        if (pParent is PsiTypeElement || pParent is PsiImportStatement || pParent is PsiReferenceList || pParent is PsiJavaCodeReferenceElement) {
            var qn = pRef.qualifiedName
            var fqn = importedClasses.find { it.endsWith(qn) }
            while (true) {
                if (fqn != null) break
                if (qn.isEmpty()) break

                val nestedClassName = qn.substringAfterLast('.')
                qn = qn.substringBeforeLast('.', "")
                fqn = importedClasses.find { it.endsWith(qn) }
                if (fqn != null) fqn = "${fqn}.${nestedClassName}"
            }

            if (fqn == null) fqn = pRef.qualifiedName
            if (pDummies.containsKey(fqn)) return pDummies[fqn]

            var className = fqn.substringAfterLast('.')
            var packageName = fqn.substringBeforeLast('.', "")
            var foundMapping = false
            while (true) {
                if (packageName.isEmpty()) break
                if (mTree.getClass("${packageName.replace('.', '/')}/${className.replace('.', '$')}") != null) {
                    foundMapping = true
                    break
                }

                className = "${packageName.substringAfterLast('.')}.${className}"
                packageName = packageName.substringBeforeLast('.', "")
            }
            if (!foundMapping) return null

            return pDummies.getOrPut("${packageName}.${className}") {
                val classNames = className.split('.')

                val source = StringBuilder().append("package ${packageName};\n")
                classNames.forEach { source.append("public class ${it}{\n") }
                classNames.forEach { _ -> source.append("}\n") }

                var elt: PsiElement = pFileFactory.createFileFromText("_RavelDummy_.java", JavaFileType.INSTANCE, source)
                classNames.forEach { _ -> elt = elt.childrenOfType<PsiClass>().first() }
                elt
            }
        }

        return null
    }

    val PsiClass.jvmName: String?
        get() {
            val cache = jvmNameHolder.get(this)
            if (cache != null) return cache.value

            val className = qualifiedName ?: return jvmNameHolder.put(this, null)
            val classOnlyName = className.substringAfterLast('.')

            val pOuterClass = containingClass
            val jvmName = if (pOuterClass != null) {
                pOuterClass.jvmName + "$" + classOnlyName
            } else {
                val packageName = className.substringBeforeLast('.').replace('.', '/')
                "$packageName/$classOnlyName"
            }

            return jvmNameHolder.put(this, jvmName)
        }

    fun PsiModifierListOwner.implicitly(@ModifierConstant modifier: String): Boolean {
        val modifierList = modifierList ?: return false
        return modifierList.hasModifierProperty(modifier)
    }

    val PsiMethod.jvmDesc: String
        get() {
            val cache = jvmDescHolder.get(this)
            if (cache != null) return cache.value!!

            val mSignatureBuilder = StringBuilder()
            mSignatureBuilder.append("(")
            for (pParam in this.parameterList.parameters) {
                mSignatureBuilder.append(pParam.type.jvmRaw)
            }
            mSignatureBuilder.append(")")
            val pReturn = this.returnType ?: PsiTypes.voidType()
            mSignatureBuilder.append(pReturn.jvmRaw)
            val mSignature = mSignatureBuilder.toString()
            return mSignature
        }

    @Suppress("UnstableApiUsage")
    val PsiType.jvmRaw: String
        get() = when (this) {
            is PsiArrayType -> "[" + componentType.jvmRaw
            is PsiPrimitiveType -> kind.binaryName
            is PsiClassType -> {
                fun jvmName(cls: PsiClass): String? {
                    if (cls is PsiTypeParameter) {
                        val bounds = cls.extendsList.referencedTypes
                        if (bounds.isEmpty()) return "java/lang/Object"

                        val ref = resolveReference(bounds.first().psiContext as PsiJavaCodeReferenceElement)
                        return jvmName(ref as PsiClass)
                    }

                    return cls.jvmName
                }

                val ref = resolveReference(psiContext as PsiJavaCodeReferenceElement)
                val name = jvmName(ref as PsiClass)
                "L${name};"
            }

            else -> {
                val ret = canonicalText
                if (ret.contains('<')) ret.substringBefore('<') else ret
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
            write { todo(pSafeElt, "could not resolve all method origins") }
            return null
        }

        val uniqueNewMethodNames = newMethodNames.values.toSet()
        if (uniqueNewMethodNames.size != 1) {
            logger.warn("method origins have different new names")
            val comment = newMethodNames.map { (k, v) -> "$k -> $v" }.joinToString(separator = "\n")
            write { todo(pSafeElt, "method origins have different new names\n$comment") }
            return null
        }

        val newMethodName = uniqueNewMethodNames.first()
        return if (newMethodName == pMethod.name) null else newMethodName
    }

    protected fun renameFile(newPackageName: String?, topLevelClasses: Map<PsiClass, String>) {
        if (topLevelClasses.isEmpty()) return

        val fileClassName = file.nameWithoutExtension
        val (pClass, newClassJvmName) = topLevelClasses.entries
            .firstOrNull { it.key.name == fileClassName }
            ?: return
        val classJvmName = pClass.jvmName ?: return

        val packageDir = classJvmName.substringBeforeLast('/')
        val newPackageDir = newPackageName?.replace('.', '/')

        if (newPackageDir != null && packageDir != newPackageDir) write {
            var rootDir = file.parent
            repeat(packageDir.split('/').size) {
                rootDir = rootDir.parent
            }

            file.move(null, VfsUtil.createDirectoryIfMissing(rootDir, newPackageDir))
        }

        if (classJvmName != newClassJvmName) write {
            val newClassName = newClassJvmName.substringAfterLast('/')
            file.rename(null, "${newClassName}.${file.extension}")
        }
    }

    fun MappingTree.get(pClass: PsiClass): ClassMapping? {
        val classJvmName = pClass.jvmName ?: return null
        return getClass(classJvmName)
    }

    fun MappingTree.get(pField: PsiField): FieldMapping? {
        val pClass = pField.containingClass ?: return null
        val mClass = get(pClass) ?: return null
        return mClass.getField(pField.name)
    }

    fun MappingTree.get(pMethod: PsiMethod): MethodMapping? {
        val pClass = pMethod.containingClass ?: return null
        val mClass = get(pClass) ?: return null
        return mClass.getMethod(pMethod.name, pMethod.jvmDesc)
    }

    fun MutableMappingTree.getOrPut(pClass: PsiClass) =
        getOrPutClass(pClass.jvmName!!, null)
}
