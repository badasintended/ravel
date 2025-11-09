package lol.bai.ravel.remapper

import ai.grazie.utils.toLinkedSet
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.psi.*
import lol.bai.ravel.psi.implicitly
import lol.bai.ravel.psi.jvmName
import lol.bai.ravel.util.Holder
import lol.bai.ravel.util.decapitalize
import lol.bai.ravel.util.held
import org.jetbrains.kotlin.asJava.toLightElements
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

private val regex = Regex(".*\\.kt")

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
        val jElts = kProperty.toLightElements().toLinkedSet()
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
        val jMethods = kFun.toLightMethods().toLinkedSet()
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

    override fun remap() {
        val nonFqnClassNames = hashMapOf<String, String>()
        pFile.process c@{ kClass: KtClassOrObject ->
            val className = kClass.name ?: return@c
            val classJvmName = kClass.jvmName ?: return@c
            nonFqnClassNames[className] = classJvmName
        }

        pFile.process p@{ kProperty: KtProperty ->
            val newName = remap(kProperty, kProperty) ?: return@p
            write { kProperty.setName(newName) }
        }

        pFile.process f@{ kFun: KtNamedFunction ->
            val newName = remap(kFun, kFun) ?: return@f
            write { kFun.setName(newName) }
        }

        pFile.process p@{ kParam: KtParameter ->
            if (!kParam.hasValOrVar()) return@p
            val kFun = kParam.ownerFunction ?: return@p
            val pSafeElt = if (kFun is KtPrimaryConstructor) kFun.containingClassOrObject else kFun
            if (pSafeElt == null) return@p

            val newName = remap(pSafeElt, kParam) ?: return@p
            write { kParam.setName(newName) }
        }

        val lateWrite = arrayListOf<() -> Unit>()
        pFile.process r@{ kRef: KtNameReferenceExpression ->
            val pRef = kRef.reference ?: return@r
            val pTarget = pRef.resolve() ?: return@r
            val pSafeParent = kRef.parent<PsiNamedElement>() ?: pFile

            var staticTargetClassName: String? = null
            run t@{
                if (pTarget is KtProperty) {
                    if (pTarget.isTopLevel) staticTargetClassName = pTarget.containingKtFile.jvmName
                    val newTargetName = remap(pSafeParent, pTarget) ?: return@t
                    write { pRef.handleElementRename(newTargetName) }
                    return@t
                }

                if (pTarget is KtParameter) {
                    if (!pTarget.hasValOrVar()) return@t
                    val newTargetName = remap(pSafeParent, pTarget) ?: return@t
                    write { pRef.handleElementRename(newTargetName) }
                    return@t
                }

                if (pTarget is KtNamedFunction) {
                    if (pTarget.isTopLevel) staticTargetClassName = pTarget.containingKtFile.jvmName
                    val newTargetName = remap(pSafeParent, pTarget) ?: return@t
                    write { pRef.handleElementRename(newTargetName) }
                    return@t
                }

                fun remapKotlinClass(kClass: KtClassOrObject?) {
                    if (kClass == null) return
                    staticTargetClassName = kClass.jvmName
                    val newTargetName = mTree.getClass(kClass.jvmName)?.newFullPeriodName ?: return
                    write { pRef.handleElementRename(newTargetName.substringAfterLast('.')) }
                }

                if (pTarget is KtConstructor<*>) {
                    return@t remapKotlinClass(pTarget.containingClassOrObject)
                }

                if (pTarget is KtClassOrObject) {
                    return@r remapKotlinClass(pTarget)
                }

                if (pTarget is PsiField) {
                    if (pTarget.implicitly(PsiModifier.STATIC)) staticTargetClassName = pTarget.containingClass?.jvmName
                    val newTargetName = remap(pTarget) ?: return@t
                    write { pRef.handleElementRename(newTargetName) }
                    return@t
                }

                fun remapJavaClass(jClass: PsiClass?) {
                    if (jClass == null) return
                    staticTargetClassName = jClass.jvmName
                    val newTargetName = mTree.get(jClass)?.newFullPeriodName ?: return
                    write { pRef.handleElementRename(newTargetName.substringAfterLast('.')) }
                }

                if (pTarget is PsiMethod) {
                    if (pTarget.isConstructor) return@t remapJavaClass(pTarget.containingClass)
                    if (pTarget.implicitly(PsiModifier.STATIC)) staticTargetClassName = pTarget.containingClass?.jvmName
                    val newTargetName = remap(pSafeParent, pTarget) ?: return@t

                    var isGetSet = false
                    var prefix = ""
                    if (newTargetName.startsWith("get") && !pTarget.hasParameters()) {
                        isGetSet = true
                        prefix = "get"
                    } else if (newTargetName.startsWith("is") && !pTarget.hasParameters() && pTarget.returnType == PsiTypes.booleanType()) {
                        isGetSet = true
                    } else if (newTargetName.startsWith("set") && pTarget.parameterList.parametersCount == 1 && pTarget.returnType == PsiTypes.voidType()) {
                        isGetSet = true
                        prefix = "set"
                    }

                    // TODO: solve more get/set bullshit
                    val kRefParent = kRef.parent
                    if (isGetSet && kRefParent is KtCallExpression) {
                        lateWrite.add w@{
                            val kArg = kRefParent.valueArguments.firstOrNull() ?: return@w
                            val newTargetNameSansPrefix = newTargetName.removePrefix(prefix).decapitalize()

                            if (prefix == "set") {
                                kRefParent.replace(factory.createExpression("$newTargetNameSansPrefix = ${kArg.text}"))
                            } else {
                                kRefParent.replace(factory.createExpression(newTargetNameSansPrefix))
                            }
                        }
                    } else if (!isGetSet && kRefParent is KtBinaryExpression) {
                        lateWrite.add w@{
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
                        }
                    } else if (isGetSet) {
                        val newTargetNameSansPrefix = newTargetName.removePrefix(prefix).decapitalize()
                        write { pRef.handleElementRename(newTargetNameSansPrefix) }
                    } else {
                        write { pRef.handleElementRename(newTargetName) }
                    }

                    return@t
                }

                if (pTarget is PsiClass) {
                    return@t remapJavaClass(pTarget)
                }
            }

            if (staticTargetClassName == null) return@r

            val kDot = kRef.parent<KtDotQualifiedExpression>()?.receiverExpression ?: return@r
            val kDotRef =
                if (kDot is KtDotQualifiedExpression) kDot.selectorExpression as? KtNameReferenceExpression
                else kDot as? KtNameReferenceExpression
            if (kDotRef?.reference?.resolve() !is PsiPackage) return@r

            val newClassName = mTree.getClass(staticTargetClassName)?.newPkgPeriodName ?: return@r
            val newPackageName = newClassName.substringBeforeLast('.')
            write { kDot.replace(factory.createExpression(newPackageName)) }

            lateWrite.forEach { write(it) }
        }
    }

}
