package lol.bai.ravel.remapper

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.psi.*
import lol.bai.ravel.psi.implicitly
import lol.bai.ravel.psi.jvmName
import lol.bai.ravel.util.Holder
import lol.bai.ravel.util.decapitalize
import lol.bai.ravel.util.held
import lol.bai.ravel.util.linkedSetMultiMap
import org.jetbrains.kotlin.asJava.canHaveSyntheticGetter
import org.jetbrains.kotlin.asJava.toLightElements
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.idea.base.psi.kotlinFqName
import org.jetbrains.kotlin.idea.structuralsearch.visitor.KotlinRecursiveElementWalkingVisitor
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.java.propertyNameByGetMethodName
import org.jetbrains.kotlin.load.java.propertyNamesBySetMethodName
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.isImportDirectiveExpression

private val regex = Regex("^.*\\.kt$")

// TODO: handle @JvmName
class KotlinRemapper : JvmRemapper<KtFile>(regex, { it as? KtFile }) {

    private val logger = thisLogger()
    private lateinit var factory: KtPsiFactory

    override fun init(): Boolean {
        if (!super.init()) return false
        this.factory = KtPsiFactory(project)
        return true
    }

    override fun comment(pElt: PsiElement, comment: String) {
        var pAnchor: PsiElement? = null
        comment.split('\n').forEach { line ->
            val pComment = factory.createComment("// $line")
            pAnchor =
                if (pAnchor == null) pElt.addBefore(pComment, pElt.firstChild)
                else pElt.addAfter(pComment, pAnchor)
        }
    }

    private fun <T> remap(pSafeElt: PsiElement, kProperty: T): String? where T : KtNamedDeclaration, T : KtValVarKeywordOwner {
        val jElts = kProperty.toLightElements().toSet()
        if (jElts.isEmpty()) return null

        val newNames = linkedMapOf<PsiElement, Holder<String?>>()
        for (jElt in jElts) when (jElt) {
            is PsiParameter -> newNames[jElt] = null.held
            is PsiField -> newNames[jElt] = remap(jElt).held
            is PsiMethod -> {
                val newMethodName = remap(pSafeElt, jElt)
                newNames[jElt] = newMethodName.held
            }
        }

        if (newNames.size != jElts.size) {
            logger.warn("cannot resolve property ${kProperty.name}")
            write { comment(pSafeElt, "TODO(Ravel): cannot resolve property ${kProperty.name}") }
            return null
        }

        val uniqueNewNames = mutableSetOf<String>()
        for ((jElt, newNameHolder) in newNames) {
            val newName = newNameHolder.value ?: continue

            if (jElt is PsiField) {
                uniqueNewNames.add(newName)
                continue
            }
            jElt as PsiMethod

            if (newName.startsWith("get")) {
                uniqueNewNames.add(newName.removePrefix("get").decapitalize())
            } else if (newName.startsWith("set")) {
                uniqueNewNames.add(newName.removePrefix("set").decapitalize())
            } else if (newName.startsWith("is")) {
                uniqueNewNames.add(newName)
            } else {
                logger.warn("property ${kProperty.name} have get/setter that overrides method which new name is not named get*/set*/is*")
                write { comment(pSafeElt, "TODO(Ravel): property ${kProperty.name} have get/setter overrides method which new name is not named get*/set*/is*") }
                return null
            }
        }

        if (uniqueNewNames.isEmpty()) return null
        if (uniqueNewNames.size != 1) {
            val comment = newNames
                .filter { it.key is PsiMethod }
                .map { (it.key as PsiMethod).name to it.value.value }
                .joinToString { "${it.first} -> ${it.second}" }
            logger.warn("property ${kProperty.name} overrides getters/setters with different new names")
            write { comment(pSafeElt, "TODO(Ravel): property ${kProperty.name} overrides getters/setters with different new names\n$comment") }
            return null
        }

        return uniqueNewNames.first()
    }

    private fun remap(pSafeElt: PsiElement, kFun: KtNamedFunction): String? {
        val jMethods = kFun.toLightMethods().toSet()
        if (jMethods.isEmpty()) return null

        val newNames = linkedMapOf<PsiMethod, Holder<String?>>()
        for (jMethod in jMethods) newNames[jMethod] = remap(pSafeElt, jMethod).held

        if (newNames.size != jMethods.size) {
            logger.warn("cannot resolve function ${kFun.name}")
            write { comment(pSafeElt, "TODO(Ravel): cannot resolve function ${kFun.name}") }
            return null
        }

        val uniqueNewNames = mutableSetOf<String>()
        for ((_, newNameHolder) in newNames) {
            val newName = newNameHolder.value ?: continue
            uniqueNewNames.add(newName)
        }

        if (uniqueNewNames.isEmpty()) return null
        if (uniqueNewNames.size != 1) {
            val comment = newNames
                .map { it.key.name to it.value.value }
                .joinToString { "${it.first} -> ${it.second}" }
            logger.warn("function ${kFun.name} overrides methods with different new names")
            write { comment(pSafeElt, "TODO(Ravel): function ${kFun.name} overrides methods with different new names\n$comment") }
            return null
        }

        return uniqueNewNames.first()
    }

    private open inner class KotlinStage : KotlinRecursiveElementWalkingVisitor(), Stage {
        override fun invoke() = pFile.accept(this)
    }

    override fun stages() = listOf<Stage>(
        classNameCollector,
        memberRemapper,
        referenceRemapper,
        importRemapper,
    )

    private val nonFqnClassNames = hashMapOf<String, String>()
    private val classNameCollector = object : KotlinStage() {
        override fun visitClass(kClass: KtClass) {
            val className = kClass.name ?: return
            val classJvmName = kClass.jvmName ?: return
            nonFqnClassNames[className] = classJvmName
        }
    }
    private val memberRemapper = object : KotlinStage() {
        override fun visitProperty(kProperty: KtProperty) {
            val newName = remap(kProperty, kProperty) ?: return
            write { kProperty.setName(newName) }
        }

        override fun visitNamedFunction(kFun: KtNamedFunction) {
            val newName = remap(kFun, kFun) ?: return
            write { kFun.setName(newName) }
        }

        override fun visitParameter(kParam: KtParameter) {
            if (!kParam.hasValOrVar()) return
            val kFun = kParam.ownerFunction ?: return
            val pSafeElt = if (kFun is KtPrimaryConstructor) kFun.containingClassOrObject else kFun
            if (pSafeElt == null) return

            val newName = remap(pSafeElt, kParam) ?: return
            write { kParam.setName(newName) }
        }
    }
    private val pMemberImportUsages = linkedSetMultiMap<FqName, PsiNamedElement>()
    private val lateRefWrites = arrayListOf<Pair<Int, () -> Unit>>()
    private val referenceRemapper = object : KotlinStage() {
        override fun visitReferenceExpression(expression: KtReferenceExpression) {
            val kRef = expression as? KtNameReferenceExpression ?: return
            val kRefParent = kRef.parent
            if (kRefParent is KtSuperExpression) return
            if (kRefParent is KtThisExpression) return

            val pRef = kRef.reference ?: return
            val pTarget = pRef.resolve() as? PsiNamedElement ?: return
            val pSafeParent = kRef.parent<PsiNamedElement>() ?: pFile

            val kDot = kRef.parent<KtDotQualifiedExpression>()?.receiverExpression

            var staticTargetClassName: String? = null
            run t@{
                if (pTarget is KtProperty) {
                    if (kRef.isImportDirectiveExpression()) return
                    if (kDot != null) pTarget.fqName?.let { pMemberImportUsages.put(it, pTarget) }

                    if (pTarget.name != kRef.getReferencedName()) return@t
                    if (pTarget.isTopLevel) staticTargetClassName = pTarget.containingKtFile.jvmName
                    val newTargetName = remap(pSafeParent, pTarget) ?: return@t
                    write { pRef.handleElementRename(newTargetName) }
                    return@t
                }

                if (pTarget is KtParameter) {
                    if (kRef.isImportDirectiveExpression()) return
                    if (!pTarget.hasValOrVar()) return@t
                    if (kDot != null) pTarget.fqName?.let { pMemberImportUsages.put(it, pTarget) }

                    if (pTarget.name != kRef.getReferencedName()) return@t
                    val newTargetName = remap(pSafeParent, pTarget) ?: return@t
                    write { pRef.handleElementRename(newTargetName) }
                    return@t
                }

                if (pTarget is KtNamedFunction) {
                    if (kRef.isImportDirectiveExpression()) return
                    if (kDot != null) pTarget.fqName?.let { pMemberImportUsages.put(it, pTarget) }

                    if (pTarget.name != kRef.getReferencedName()) return@t
                    if (pTarget.isTopLevel) staticTargetClassName = pTarget.containingKtFile.jvmName
                    val newTargetName = remap(pSafeParent, pTarget) ?: return@t
                    write { pRef.handleElementRename(newTargetName) }
                    return@t
                }

                fun remapKotlinClass(kClass: KtClassOrObject?) {
                    if (kClass == null) return
                    if (kClass.name != kRef.getReferencedName()) return
                    staticTargetClassName = kClass.jvmName
                    val newTargetName = mTree.getClass(kClass.jvmName)?.newFullPeriodName ?: return
                    write { pRef.handleElementRename(newTargetName.substringAfterLast('.')) }
                }

                if (pTarget is KtConstructor<*>) {
                    return@t remapKotlinClass(pTarget.containingClassOrObject)
                }

                if (pTarget is KtClassOrObject) {
                    return remapKotlinClass(pTarget)
                }

                if (pTarget is PsiField) {
                    if (kRef.isImportDirectiveExpression()) return
                    if (pTarget.implicitly(PsiModifier.STATIC)) {
                        staticTargetClassName = pTarget.containingClass?.jvmName
                        if (kDot != null) pTarget.kotlinFqName?.let { pMemberImportUsages.put(it, pTarget) }
                        if (pTarget.name != kRef.getReferencedName()) return@t
                    }

                    val newTargetName = remap(pTarget) ?: return@t
                    write { pRef.handleElementRename(newTargetName) }
                    return@t
                }

                fun remapJavaClass(jClass: PsiClass?) {
                    if (jClass == null) return
                    if (jClass.name != kRef.getReferencedName()) return
                    staticTargetClassName = jClass.jvmName
                    val newTargetName = mTree.get(jClass)?.newFullPeriodName ?: return
                    write { pRef.handleElementRename(newTargetName.substringAfterLast('.')) }
                }

                if (pTarget is PsiMethod) {
                    if (kRef.isImportDirectiveExpression()) return
                    if (pTarget.isConstructor) return@t remapJavaClass(pTarget.containingClass)

                    if (pTarget.implicitly(PsiModifier.STATIC)) {
                        staticTargetClassName = pTarget.containingClass?.jvmName
                        if (kDot != null) pTarget.kotlinFqName?.let { pMemberImportUsages.put(it, pTarget) }
                        if (pTarget.name != kRef.getReferencedName()) return@t
                    }

                    val newTargetName = remap(pSafeParent, pTarget) ?: return@t
                    val newTargetNameName = Name.guessByFirstCharacter(newTargetName)

                    var newTargetSetter = propertyNamesBySetMethodName(newTargetNameName).firstOrNull()?.asString()
                    if (pTarget.parameterList.parametersCount != 1 || pTarget.returnType != PsiTypes.voidType()) newTargetSetter = null

                    // TODO: more robust handling
                    if (newTargetSetter == null && kRefParent is KtBinaryExpression && kRefParent.operationToken == KtTokens.EQ && kRefParent.left == kRef) {
                        // from setter to function call
                        // TODO: +=, -= and co
                        lateRefWrites.add(kRefParent.depth to w@{
                            val kLeft = kRefParent.left ?: return@w
                            val kRight = kRefParent.right ?: return@w

                            val receiverText =
                                if (kLeft is KtDotQualifiedExpression) kLeft.receiverExpression.text
                                else null

                            val callText = if (receiverText != null) {
                                "${receiverText}.${newTargetName}(${kRight.text})"
                            } else {
                                "${newTargetName}(${kRight.text})"
                            }

                            kRefParent.replace(factory.createExpression(callText))
                        })
                        return@t
                    }
                    if (newTargetSetter != null && kRefParent is KtCallExpression) {
                        // from function call to setter
                        lateRefWrites.add(kRefParent.depth to w@{
                            val kArg = kRefParent.valueArguments.firstOrNull() ?: return@w
                            kRefParent.replace(factory.createExpression("$newTargetSetter = ${kArg.text}"))
                        })
                        return@t
                    }

                    var newTargetGetter = propertyNameByGetMethodName(newTargetNameName)?.asString()
                    if (pTarget.hasParameters() || pTarget.returnType == PsiTypes.voidType()) newTargetGetter = null

                    if (newTargetGetter == null && pTarget.canHaveSyntheticGetter) {
                        // from getter to function call
                        lateRefWrites.add(kRef.depth to w@{ kRef.replace(factory.createExpression("${newTargetName}()")) })
                        return@t
                    }
                    if (newTargetGetter != null && kRefParent is KtCallExpression) {
                        // from function call to getter
                        lateRefWrites.add(kRef.depth to w@{ kRef.replace(factory.createExpression(newTargetGetter)) })
                        return@t
                    }

                    // from getter/setter to getter/setter or function call to function call
                    val newTargetAccessor = newTargetSetter ?: newTargetGetter ?: newTargetName
                    write { pRef.handleElementRename(newTargetAccessor) }
                    return@t
                }

                if (pTarget is PsiClass) {
                    return@t remapJavaClass(pTarget)
                }
            }

            if (staticTargetClassName == null) return
            if (kDot == null) return

            val kDotRef =
                if (kDot is KtDotQualifiedExpression) kDot.selectorExpression as? KtNameReferenceExpression
                else kDot as? KtNameReferenceExpression
            if (kDotRef?.reference?.resolve() !is PsiPackage) return

            val newClassName = mTree.getClass(staticTargetClassName)?.newPkgPeriodName ?: return
            val newPackageName = newClassName.substringBeforeLast('.')
            write { kDot.replace(factory.createExpression(newPackageName)) }
        }
    }
    private val importRemapper = object : KotlinStage() {
        override fun invoke() {
            lateRefWrites.sortedByDescending { it.first }.forEach { write(it.second) }
            super.invoke()
        }
        override fun visitImportDirective(kImport: KtImportDirective) {
            val kRefExp = kImport.importedReference ?: return
            val kRefSelector =
                if (kRefExp is KtDotQualifiedExpression) kRefExp.selectorExpression
                else kRefExp as? KtNameReferenceExpression
            if (kRefSelector == null) return

            val targetName = kImport.importedFqName ?: return
            val pUsages = pMemberImportUsages[targetName].orEmpty().ifEmpty { return }

            val newNames = linkedMapOf<PsiNamedElement, Holder<String?>>()
            for (pElt in pUsages) when (pElt) {
                is KtProperty -> newNames[pElt] = remap(kImport, pElt).held
                is KtParameter -> newNames[pElt] = remap(kImport, pElt).held
                is KtNamedFunction -> newNames[pElt] = remap(kImport, pElt).held
                is PsiField -> newNames[pElt] = remap(pElt).held
                is PsiMethod -> newNames[pElt] = remap(kImport, pElt).held
            }

            val uniqueNewNames = mutableSetOf<String>()
            for ((_, newNameHolder) in newNames) {
                val newName = newNameHolder.value ?: continue
                uniqueNewNames.add(newName)
            }
            if (uniqueNewNames.isEmpty()) return

            if (uniqueNewNames.size != 1) {
                val memberName = targetName.shortName().asString()
                val comment = newNames
                    .filter { it.value.value != null }
                    .map { (k, v) -> "${k.name} -> ${v.value}" }
                    .joinToString(separator = "\n")
                logger.warn("ambiguous import, members with name $memberName have different new names")
                write { comment(kImport, "TODO(Ravel): ambiguous import, members with name $memberName have different new names\n$comment") }
                return
            }

            write { kRefSelector.replace(factory.createExpression(uniqueNewNames.first())) }
        }
    }
}
