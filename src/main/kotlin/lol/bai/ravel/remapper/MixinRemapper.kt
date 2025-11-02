package lol.bai.ravel.remapper

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import lol.bai.ravel.decapitalize

object MixinRemapper : Remapper<PsiJavaFile>("java", { it as? PsiJavaFile }) {

    fun isRemapped(pAnnotation: PsiAnnotation): Boolean {
        val pRemap = pAnnotation.findAttributeValue("remap") ?: return true
        pRemap as PsiLiteralExpression
        return pRemap.value as Boolean
    }

    override fun remap(project: Project, mappings: Mappings, mClasses: ClassMappings, pFile: PsiJavaFile, writers: Writers) {
        val psi = JavaPsiFacade.getInstance(project).elementFactory
        val mixinTargets = hashMapOf<String, String>()

        pFile.process r@{ pAnnotation: PsiAnnotation ->
            val pClass = pAnnotation.parent<PsiClass>() ?: return@r
            val className = pClass.qualifiedName ?: return@r
            val annotationName = pAnnotation.qualifiedName ?: return@r

            if (annotationName == "org.spongepowered.asm.mixin.Mixin") {
                if (!isRemapped(pAnnotation)) return@r

                fun remapTarget(pTarget: PsiLiteralExpression) {
                    val target = pTarget.value as String
                    val mTargetClass = mClasses[replaceAllQualifier(target)] ?: return
                    var newTarget = mappings.newName(mTargetClass) ?: return
                    newTarget = replacePkgQualifier(newTarget)
                    val newTargetQuoted = "\"" + StringUtil.escapeStringCharacters(newTarget) + "\""

                    writers.add { pTarget.replace(psi.createExpressionFromText(newTargetQuoted, pTarget)) }
                }

                val pTargets = pAnnotation.findDeclaredAttributeValue("targets")
                if (pTargets != null) {
                    if (pTargets is PsiLiteralExpression) {
                        val value = pTargets.value as String
                        remapTarget(pTargets)
                        mixinTargets[className] = replaceAllQualifier(value)
                    } else {
                        pTargets as PsiArrayInitializerMemberValue
                        pTargets.initializers.forEach {
                            remapTarget(it as PsiLiteralExpression)
                        }
                    }
                }

                val pValues = pAnnotation.findDeclaredAttributeValue("value")
                if (pValues != null) {
                    if (pValues is PsiClassObjectAccessExpression) {
                        mixinTargets[className] = pValues.operand.type.canonicalText
                    }
                }

                return@r
            }

            if (annotationName == "org.spongepowered.asm.mixin.gen.Invoker") {
                if (!isRemapped(pAnnotation)) return@r
                val pMethod = pAnnotation.parent<PsiMethod>() ?: return@r
                val methodName = pMethod.name

                val targetClassName = mixinTargets[className]
                if (targetClassName == null) {
                    thisLogger().error("Could not find single target for '$className' mixin")
                    return@r
                }
                val mTargetClass = mClasses[targetClassName] ?: return@r

                var targetSignature: String? = null
                var targetMethodName = when {
                    methodName.startsWith("call") -> methodName.removePrefix("call").decapitalize()
                    methodName.startsWith("invoke") -> methodName.removePrefix("invoke").decapitalize()
                    else -> null
                }

                val pValue = pAnnotation.findDeclaredAttributeValue("value")
                if (pValue is PsiLiteralExpression) {
                    val value = pValue.value as String
                    if (value == "<init>") return@r
                    if (value.contains('(')) {
                        targetMethodName = value.substringBefore('(')
                        targetSignature = value.removePrefix(targetMethodName)
                    } else {
                        targetMethodName = value
                    }
                }

                if (targetMethodName == null) {
                    thisLogger().warn("Empty target method for '${className}#${methodName}' invoker")
                    return@r
                }

                if (targetSignature == null) targetSignature = JavaRemapper.signature(pMethod)

                val mMethod = mTargetClass.getMethod(targetMethodName, targetSignature) ?: return@r
                val newMethodName = mappings.newName(mMethod) ?: return@r
                writers.add {
                    pAnnotation.setDeclaredAttributeValue("value", psi.createExpressionFromText("\"${newMethodName}\"", pAnnotation))
                }
                return@r
            }

            if (annotationName == "org.spongepowered.asm.mixin.gen.Accessor") {
                if (!isRemapped(pAnnotation)) return@r
                val pMethod = pAnnotation.parent<PsiMethod>() ?: return@r
                val methodName = pMethod.name

                val targetClassName = mixinTargets[className]
                if (targetClassName == null) {
                    thisLogger().error("Could not find single target for '$className' mixin")
                    return@r
                }
                val mTargetClass = mClasses[targetClassName] ?: return@r

                var targetFieldName = when {
                    methodName.startsWith("get") -> methodName.removePrefix("get").decapitalize()
                    methodName.startsWith("set") -> methodName.removePrefix("set").decapitalize()
                    methodName.startsWith("is") -> methodName.removePrefix("is").decapitalize()
                    else -> null
                }

                val pValue = pAnnotation.findDeclaredAttributeValue("value")
                if (pValue is PsiLiteralExpression) targetFieldName = pValue.value as String

                if (targetFieldName == null) {
                    thisLogger().error("Empty target field for '${className}#${methodName}' accessor")
                    return@r
                }

                val mTargetField = mTargetClass.getField(targetFieldName, null) ?: return@r
                val newFieldName = mappings.newName(mTargetField) ?: return@r
                writers.add {
                    pAnnotation.setDeclaredAttributeValue("value", psi.createExpressionFromText("\"${newFieldName}\"", null))
                }
                return@r
            }

            annotationName
        }
    }

}
