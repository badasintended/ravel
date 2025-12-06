package lol.bai.ravel.remapper

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.psi.*
import lol.bai.ravel.mapping.ClassMapping
import lol.bai.ravel.psi.jvmDesc
import lol.bai.ravel.psi.jvmName
import lol.bai.ravel.util.capitalizeFirstChar
import lol.bai.ravel.util.decapitalizeFirstChar
import lol.bai.ravel.util.setMultiMap

// @formatter:off
private const val mixin          = "org.spongepowered.asm.mixin"
private const val Mixin          = "${mixin}.Mixin"
private const val Shadow         = "${mixin}.Shadow"
private const val Unique         = "${mixin}.Unique"
private const val Final          = "${mixin}.Final"
private const val Debug          = "${mixin}.Debug"
private const val Intrinsic      = "${mixin}.Intrinsic"
private const val Mutable        = "${mixin}.Mutable"
private const val Overwrite      = "${mixin}.Overwrite"
private const val Dynamic        = "${mixin}.Dynamic"
private const val Pseudo         = "${mixin}.Pseudo"
private const val Invoker        = "${mixin}.gen.Invoker"
private const val Accessor       = "${mixin}.gen.Accessor"
private const val At             = "${mixin}.injection.At"
private const val Slice          = "${mixin}.injection.Slice"
private const val Inject         = "${mixin}.injection.Inject"
private const val ModifyArg      = "${mixin}.injection.ModifyArg"
private const val ModifyArgs     = "${mixin}.injection.ModifyArgs"
private const val ModifyConstant = "${mixin}.injection.ModifyConstant"
private const val ModifyVariable = "${mixin}.injection.ModifyVariable"
private const val Redirect       = "${mixin}.injection.Redirect"
private const val Coerce         = "${mixin}.injection.Coerce"
private const val Constant       = "${mixin}.injection.Constant"

private const val mixinextras           = "com.llamalad7.mixinextras"
private const val ModifyExpressionValue = "${mixinextras}.injector.ModifyExpressionValue"
private const val ModifyReceiver        = "${mixinextras}.injector.ModifyReceiver"
private const val ModifyReturnValue     = "${mixinextras}.injector.ModifyReturnValue"
private const val WrapWithCondition     = "${mixinextras}.injector.WrapWithCondition"
private const val WrapWithCondition2    = "${mixinextras}.injector.v2.WrapWithCondition"
private const val WrapMethod            = "${mixinextras}.injector.wrapmethod.WrapMethod"
private const val WrapOperation         = "${mixinextras}.injector.wrapoperation.WrapOperation"
private const val Cancellable           = "${mixinextras}.sugar.Cancellable"
private const val Local                 = "${mixinextras}.sugar.Local"
private const val Share                 = "${mixinextras}.sugar.Share"
private const val Definition            = "${mixinextras}.expression.Definition"
// @formatter:on

private val INJECTS = setOf(
    Inject, ModifyArg, ModifyArgs, ModifyConstant, ModifyVariable, Redirect,
    ModifyExpressionValue, ModifyReceiver, ModifyReturnValue, WrapWithCondition, WrapWithCondition2, WrapMethod, WrapOperation
)

private object Point {
    // @formatter:off
    const val HEAD          = "HEAD"
    const val RETURN        = "RETURN"
    const val TAIL          = "TAIL"
    const val INVOKE        = "INVOKE"
    const val INVOKE_ASSIGN = "INVOKE_ASSIGN"
    const val FIELD         = "FIELD"
    const val NEW           = "NEW"
    const val INVOKE_STRING = "INVOKE_STRING"
    const val JUMP          = "JUMP"
    const val CONSTANT      = "CONSTANT"
    const val STORE         = "STORE"
    const val LOAD          = "LOAD"
    const val EXPRESSION    = "MIXINEXTRAS:EXPRESSION"
    // @formatter:on

    val INVOKES = setOf(INVOKE, INVOKE_ASSIGN, INVOKE_STRING)
}

private object AccessorPrefixes {
    // @formatter:off
    val get    = Regex("^((\\w+[_$])?get)(.+)$")
    val set    = Regex("^((\\w+[_$])?set)(.+)$")
    val is_    = Regex("^((\\w+[_$])?is)(.+)$")

    val call   = Regex("^((\\w+[_$])?call)(.+)$")
    val invoke = Regex("^((\\w+[_$])?invoke)(.+)$")
    // @formatter:on
}

class MixinRemapperFactory : ExtensionRemapperFactory(::MixinRemapper, "java")
class MixinRemapper : JavaRemapper() {

    private val logger = thisLogger()
    override fun stages() = listOf(remapMixins)

    private fun splitClassMember(classMember: String): Pair<String?, String> {
        if (classMember.startsWith('L')) {
            // Lpath/to/Class;method()V
            var (className, memberNameAndDesc) = classMember.split(';', limit = 2)
            className = className.removePrefix("L")
            return className to memberNameAndDesc
        } else if (classMember.contains('.')) {
            // path/to/Class.method()V
            val (className, memberNameAndDesc) = classMember.split('.', limit = 2)
            return className to memberNameAndDesc
        } else {
            return null to classMember
        }
    }

    private val mixinTargets = setMultiMap<String, String>()
    private val remapMixins = psiStage a@{ pAnnotation: PsiAnnotation ->
        val pClass = pAnnotation.parent<PsiClass>() ?: return@a
        val className = pClass.qualifiedName ?: return@a
        val classJvmName = pClass.jvmName ?: return@a
        val annotationName = pAnnotation.qualifiedName ?: return@a

        if (!annotationName.startsWith(mixin) && !annotationName.startsWith(mixinextras)) return@a

        if (annotationName == Slice) return@a
        if (annotationName == Unique) return@a
        if (annotationName == Final) return@a
        if (annotationName == Debug) return@a
        if (annotationName == Intrinsic) return@a
        if (annotationName == Mutable) return@a
        if (annotationName == Cancellable) return@a
        if (annotationName == Local) return@a
        if (annotationName == Share) return@a
        if (annotationName == Dynamic) return@a
        if (annotationName == Pseudo) return@a
        if (annotationName == Coerce) return@a
        if (annotationName == Constant) return@a

        fun warnNotLiterals(pElt: PsiElement) {
            write { comment(pElt, "TODO(Ravel): target not a literal or array of literals") }
            logger.warn("$className: target not a literal or array of literals")
        }

        if (annotationName == Mixin) {
            val newTargetNames = linkedSetOf<Pair<String, String?>>()

            fun remapTarget(pTarget: PsiLiteralExpression) {
                var target = pTarget.value as String
                target = target.replace('.', '/')
                mixinTargets.put(className, target)

                val mTargetClass = mTree.getClass(target)
                val newTarget = mTargetClass?.newPkgPeriodName
                newTargetNames.add(target.substringAfterLast('/') to newTarget?.substringAfterLast('.'))
                if (newTarget != null) write { pTarget.replace(factory.createExpressionFromText("\"${newTarget}\"", pTarget)) }
            }

            val pTargets = pAnnotation.findDeclaredAttributeValue("targets")
            if (pTargets != null) when (pTargets) {
                is PsiLiteralExpression -> remapTarget(pTargets)
                is PsiArrayInitializerMemberValue -> pTargets.initializers.forEach {
                    if (it is PsiLiteralExpression) remapTarget(it)
                    else warnNotLiterals(pClass)
                }

                else -> warnNotLiterals(pClass)
            }

            fun putClassTarget(pTarget: PsiClassObjectAccessExpression) {
                val type = pTarget.operand.type
                fun warnCantResolve() {
                    write { comment(pClass, "TODO(Ravel): can not resolve target class ${type.canonicalText}") }
                    logger.warn("$className: can not resolve target class ${type.canonicalText}")
                }

                if (type is PsiClassType) {
                    val pTargetClass = type.resolve() ?: return warnCantResolve()
                    val targetClassName = pTargetClass.jvmName ?: return warnCantResolve()
                    mixinTargets.put(className, targetClassName)

                    val newTargetClassName = mTree.get(pTargetClass)?.newName?.substringAfterLast('/')
                    newTargetNames.add(targetClassName.substringAfterLast('/') to newTargetClassName)
                }
            }

            val pValues = pAnnotation.findDeclaredAttributeValue("value")
            if (pValues != null) when (pValues) {
                is PsiClassObjectAccessExpression -> putClassTarget(pValues)
                is PsiArrayInitializerMemberValue -> pValues.initializers.forEach {
                    if (it is PsiClassObjectAccessExpression) putClassTarget(it)
                    else warnNotLiterals(pClass)
                }

                else -> warnNotLiterals(pClass)
            }

            if (newTargetNames.size == 1) {
                val (targetClassName, newTargetClassName) = newTargetNames.first()

                if (newTargetClassName != null && className.contains(targetClassName)) {
                    rerun { mTree.putClass(classJvmName, classJvmName.replace(targetClassName, newTargetClassName)) }
                }
            }

            return@a
        }

        fun targetClassName(pMember: PsiMember): String? {
            val targetClassName = mixinTargets[className].orEmpty()
            if (targetClassName.size != 1) {
                write { comment(pMember, "TODO(Ravel): Could not determine a single target") }
                logger.warn("$className#${pMember.name}: Could not determine a single target")
                return null
            }
            return targetClassName.first()
        }

        fun targetClass(pMember: PsiMember): Pair<PsiClass?, ClassMapping?>? {
            val targetClassName = targetClassName(pMember) ?: return null
            val pTargetClass = findClass(targetClassName)
            val mTargetClass = mTree.getClass(targetClassName)
            if (pTargetClass == null && mTargetClass == null) return null
            return pTargetClass to mTargetClass
        }

        if (annotationName == Invoker) {
            val pMethod = pAnnotation.parent<PsiMethod>() ?: return@a
            val methodName = pMethod.name
            val (pTargetClass, mTargetClass) = targetClass(pMethod) ?: return@a

            var targetSignature: String? = null
            var invokerPrefix = (
                AccessorPrefixes.call.matchEntire(methodName)
                    ?: AccessorPrefixes.invoke.matchEntire(methodName)
                )?.groups?.get(1)?.value

            var targetMethodName =
                if (invokerPrefix != null) methodName.removePrefix(invokerPrefix).decapitalizeFirstChar()
                else null

            val pValue = pAnnotation.findDeclaredAttributeValue("value")
            if (pValue is PsiLiteralExpression) {
                var explicitTargetMethodName: String? = null
                val value = pValue.value as String
                if (value == "<init>") return@a
                if (value.contains('(')) {
                    explicitTargetMethodName = value.substringBefore('(')
                    targetSignature = value.removePrefix(explicitTargetMethodName)
                } else {
                    explicitTargetMethodName = value
                }
                if (explicitTargetMethodName == methodName) invokerPrefix = ""
                else if (explicitTargetMethodName != targetMethodName) invokerPrefix = null
                targetMethodName = explicitTargetMethodName
            }

            if (targetMethodName == null) {
                write { comment(pMethod, "TODO(Ravel): No target method") }
                logger.warn("$className#$methodName: No target method")
                return@a
            }

            if (targetSignature == null) targetSignature = pMethod.jvmDesc

            var newMethodName: String
            if (pTargetClass != null) {
                val pTargetMethod = findMethod(pTargetClass, targetMethodName, targetSignature) ?: return@a
                newMethodName = remap(pMethod, pTargetMethod) ?: return@a
            } else {
                val mTargetMethod = mTargetClass!!.getMethod(targetMethodName, targetSignature) ?: return@a
                newMethodName = mTargetMethod.newName ?: return@a
            }

            if (pValue != null) {
                write { pAnnotation.setDeclaredAttributeValue("value", factory.createExpressionFromText("\"${newMethodName}\"", pAnnotation)) }
            }
            if (invokerPrefix != null) {
                val newInvokerName = invokerPrefix + newMethodName.capitalizeFirstChar()
                rerun { mTree.getOrPut(pClass).putMethod(pMethod.name, pMethod.jvmDesc, newInvokerName) }
            }
            return@a
        }

        if (annotationName == Accessor) {
            val pMethod = pAnnotation.parent<PsiMethod>() ?: return@a
            val methodName = pMethod.name
            val mTargetClass = targetClass(pMethod)?.second ?: return@a

            var accessorPrefix = (
                AccessorPrefixes.get.matchEntire(methodName)
                    ?: AccessorPrefixes.set.matchEntire(methodName)
                    ?: AccessorPrefixes.is_.matchEntire(methodName)
                )?.groups?.get(1)?.value

            var targetFieldName =
                if (accessorPrefix != null) methodName.removePrefix(accessorPrefix).decapitalizeFirstChar()
                else null

            val pValue = pAnnotation.findDeclaredAttributeValue("value")
            if (pValue is PsiLiteralExpression) {
                val explicitTargetFieldName = pValue.value as String
                if (explicitTargetFieldName == methodName) accessorPrefix = ""
                else if (explicitTargetFieldName != targetFieldName) accessorPrefix = null
                targetFieldName = explicitTargetFieldName
            }

            if (targetFieldName == null) {
                write { comment(pMethod, "TODO(Ravel): No target field") }
                logger.warn("$className#$methodName: No target field")
                return@a
            }

            val mTargetField = mTargetClass.getField(targetFieldName) ?: return@a
            val newFieldName = mTargetField.newName ?: return@a

            if (pValue != null) {
                write { pAnnotation.setDeclaredAttributeValue("value", factory.createExpressionFromText("\"${newFieldName}\"", null)) }
            }
            if (accessorPrefix != null) {
                val newAccessorName = accessorPrefix + newFieldName.capitalizeFirstChar()
                rerun { mTree.getOrPut(pClass).putMethod(methodName, pMethod.jvmDesc, newAccessorName) }
            }
            return@a
        }

        fun isWildcardOrRegex(pMethod: PsiMethod, target: String): Boolean {
            if (target.contains('*') || target.contains(' ')) {
                write { comment(pMethod, "TODO(Ravel): wildcard and regex target are not supported") }
                logger.warn("$className#${pMethod.name}: wildcard and regex target are not supported")
                return true
            }
            return false
        }

        if (INJECTS.contains(annotationName)) {
            val pMethod = pAnnotation.parent<PsiMethod>() ?: return@a
            val methodName = pMethod.name

            val pDesc = pAnnotation.findDeclaredAttributeValue("target")
            if (pDesc != null) {
                write { comment(pMethod, "TODO(Ravel): target desc is not supported") }
                logger.warn("$className#$methodName: target desc is not supported")
                return@a
            }

            fun remapTargetMethod(pTarget: PsiLiteralExpression) {
                val targetClassNames = mixinTargets[className].orEmpty()
                if (targetClassNames.isEmpty()) {
                    write { comment(pMethod, "TODO(Ravel): no target class") }
                    logger.warn("$className#$methodName: no target class")
                    return
                }

                val targetMethod = pTarget.value as String
                if (isWildcardOrRegex(pMethod, targetMethod)) return

                val targetMethodAndDesc = splitClassMember(targetMethod).second
                val targetMethodName = targetMethodAndDesc.substringBefore('(')
                val targetMethodDesc = targetMethodAndDesc.removePrefix(targetMethodName)

                fun write(newTargetMethodName: String) {
                    val newTargetMethodDesc = mTree.remapDesc(targetMethodDesc)
                    val newTarget = "\"${newTargetMethodName}${newTargetMethodDesc}\""

                    write { pTarget.replace(factory.createExpressionFromText(newTarget, pTarget)) }
                }

                if (targetMethodName == "<init>" || targetMethodName == "<clinit>") {
                    return write(targetMethodName)
                }

                fun notFound() {
                    write { comment(pMethod, "TODO(Ravel): target method $targetMethodName with the signature not found") }
                    logger.warn("$className#$methodName: target method $targetMethodName not found")
                }

                fun ambiguous() {
                    write { comment(pMethod, "TODO(Ravel): target method $targetMethodName is ambiguous") }
                    logger.warn("$className#$methodName: target method $targetMethodName is ambiguous")
                }

                val newTargetMethodNames = linkedMapOf<String, String>()
                for (targetClassName in targetClassNames) {
                    val key = "${targetClassName}#${targetMethodName}"
                    newTargetMethodNames[key] = targetMethodName

                    val pTargetClass = findClass(targetClassName)
                    if (pTargetClass != null) {
                        var newTargetMethodName: String? = null
                        if (targetMethodDesc.isNotEmpty()) {
                            val pTargetMethod = findMethod(pTargetClass, targetMethodName, targetMethodDesc) ?: return notFound()
                            newTargetMethodName = remap(pMethod, pTargetMethod) ?: targetMethodName
                        } else {
                            for (pTargetMethod in pTargetClass.findMethodsByName(targetMethodName, false)) {
                                val newTargetMethodName0 =
                                    remap(pMethod, pTargetMethod) ?: targetMethodName
                                if (newTargetMethodName != null && newTargetMethodName != newTargetMethodName0) return ambiguous()
                                newTargetMethodName = newTargetMethodName0
                            }
                        }
                        newTargetMethodNames[key] = newTargetMethodName ?: targetMethodName
                    } else {
                        val mTargetClass = mTree.getClass(targetClassName) ?: continue
                        var newTargetMethodName: String? = null
                        if (targetMethodDesc.isNotEmpty()) {
                            val mTargetMethod = mTargetClass.getMethod(targetMethodName, targetMethodDesc) ?: return notFound()
                            newTargetMethodName = mTargetMethod.newName ?: targetMethodName
                        } else {
                            for (mTargetMethod in mTargetClass.getMethods(targetMethodName)) {
                                val newTargetMethodName0 = mTargetMethod.newName ?: targetMethodName
                                if (newTargetMethodName != null && newTargetMethodName != newTargetMethodName0) return ambiguous()
                                newTargetMethodName = newTargetMethodName0
                            }
                        }
                        newTargetMethodNames[key] = newTargetMethodName ?: targetMethodName
                    }
                }

                val uniqueNewTargetMethodNames = newTargetMethodNames.values.toSet()
                if (uniqueNewTargetMethodNames.size != 1) {
                    logger.warn("method target have different new names")
                    val comment = newTargetMethodNames.map { (k, v) -> "  $k -> $v" }.joinToString(separator = "\n")
                    write { comment(pMethod, "TODO(Ravel): method target have different new names\n$comment") }
                    return
                }

                return write(uniqueNewTargetMethodNames.first())
            }

            val pTargetMethods = pAnnotation.findDeclaredAttributeValue("method") ?: return@a
            when (pTargetMethods) {
                is PsiLiteralExpression -> remapTargetMethod(pTargetMethods)
                is PsiArrayInitializerMemberValue -> pTargetMethods.initializers.forEach {
                    if (it is PsiLiteralExpression) remapTargetMethod(it)
                    else warnNotLiterals(pMethod)
                }

                else -> warnNotLiterals(pMethod)
            }
            return@a
        }

        fun remapAtField(pMethod: PsiMethod, key: String, target: String) {
            if (isWildcardOrRegex(pMethod, target)) return

            if (!target.contains(':')) {
                write { comment(pMethod, "TODO(Ravel): target field doesn't have a description") }
                logger.warn("$className#${pMethod.name}: target field doesn't have a description")
                return
            }

            var (targetClassName, targetFieldAndDesc) = splitClassMember(target)
            if (targetClassName == null) targetClassName = targetClassName(pMethod)
            if (targetClassName == null) {
                write { comment(pMethod, "TODO(Ravel): Could not determine target field owner") }
                logger.warn("$className#${pMethod.name}: Could not determine target field owner")
                return
            }

            val targetFieldName = targetFieldAndDesc.substringBefore(':')
            val targetFieldDesc = targetFieldAndDesc.substringAfter(':')

            var newTargetClassName = targetClassName
            var newTargetFieldName = targetFieldName
            val mTargetClass = mTree.getClass(targetClassName)
            if (mTargetClass != null) {
                newTargetClassName = mTargetClass.newName ?: targetClassName
                val mField = mTargetClass.getField(targetFieldName)
                if (mField != null) {
                    newTargetFieldName = mField.newName ?: targetFieldName
                }
            }

            val newTargetFieldDesc = mTree.remapDesc(targetFieldDesc)
            val newTarget = "\"L${newTargetClassName};${newTargetFieldName}:${newTargetFieldDesc}\""

            write { pAnnotation.setDeclaredAttributeValue(key, factory.createExpressionFromText(newTarget, pAnnotation)) }
        }

        fun remapAtInvoke(pMethod: PsiMethod, key: String, target: String) {
            if (isWildcardOrRegex(pMethod, target)) return

            if (!target.contains('(')) {
                write { comment(pMethod, "TODO(Ravel): target method doesn't have a description") }
                logger.warn("$className#${pMethod.name}: target method doesn't have a description")
                return
            }

            var (targetClassName, targetMethodAndDesc) = splitClassMember(target)
            if (targetClassName == null) targetClassName = targetClassName(pMethod)
            if (targetClassName == null) {
                write { comment(pMethod, "TODO(Ravel): Could not determine target method owner") }
                logger.warn("$className#${pMethod.name}: Could not determine target method owner")
                return
            }

            val targetMethodName = targetMethodAndDesc.substringBefore('(')
            val targetMethodDesc = targetMethodAndDesc.removePrefix(targetMethodName)

            var newTargetClassName = targetClassName
            var newTargetMethodName = targetMethodName
            val mTargetClass = mTree.getClass(targetClassName)
            if (mTargetClass != null) {
                newTargetClassName = mTargetClass.newName ?: targetClassName
                val mMethod = mTargetClass.getMethod(targetMethodName, targetMethodDesc)
                if (mMethod != null) {
                    newTargetMethodName = mMethod.newName ?: targetMethodName
                }
            }

            val newTargetMethodDesc = mTree.remapDesc(targetMethodDesc)
            val newTarget = "\"L${newTargetClassName};${newTargetMethodName}${newTargetMethodDesc}\""

            write { pAnnotation.setDeclaredAttributeValue(key, factory.createExpressionFromText(newTarget, pAnnotation)) }
        }

        if (annotationName == Definition) {
            val pMethod = pAnnotation.parent<PsiMethod>() ?: return@a

            fun remapTarget(pTargetElt: PsiElement, key: String, remap: (PsiMethod, String, String) -> Unit) = when (pTargetElt) {
                is PsiLiteralExpression -> remap(pMethod, key, pTargetElt.value as String)
                is PsiArrayInitializerMemberValue -> pTargetElt.initializers.forEach {
                    if (it is PsiLiteralExpression) remap(pMethod, key, it.value as String)
                    else warnNotLiterals(pMethod)
                }

                else -> warnNotLiterals(pMethod)
            }

            val pTargetFields = pAnnotation.findDeclaredAttributeValue("field")
            if (pTargetFields != null) remapTarget(pTargetFields, "field", ::remapAtField)

            val pTargetMethods = pAnnotation.findDeclaredAttributeValue("method")
            if (pTargetMethods != null) remapTarget(pTargetMethods, "method", ::remapAtInvoke)
            return@a
        }

        if (annotationName == At) {
            val pMethod = pAnnotation.parent<PsiMethod>() ?: return@a
            val methodName = pMethod.name

            val pPoint = pAnnotation.findDeclaredAttributeValue("value") ?: return@a
            pPoint as PsiLiteralExpression
            val point = pPoint.value as String

            if (point == Point.HEAD) return@a
            if (point == Point.RETURN) return@a
            if (point == Point.TAIL) return@a
            if (point == Point.JUMP) return@a
            if (point == Point.CONSTANT) return@a
            if (point == Point.STORE) return@a
            if (point == Point.LOAD) return@a
            if (point == Point.EXPRESSION) return@a

            val pDesc = pAnnotation.findDeclaredAttributeValue("desc")
            if (pDesc != null) {
                write { comment(pMethod, "TODO(Ravel): @At.desc is not supported") }
                logger.warn("$className#$methodName: @At.desc is not supported")
            }

            val pArgs = pAnnotation.findDeclaredAttributeValue("args")
            if (pArgs != null) {
                write { comment(pMethod, "TODO(Ravel): @At.args is not supported") }
                logger.warn("$className#$methodName: @At.args is not supported")
            }

            val pTarget = pAnnotation.findDeclaredAttributeValue("target") ?: return@a
            pTarget as PsiLiteralExpression
            val target = pTarget.value as String

            if (point == Point.FIELD) {
                remapAtField(pMethod, "target", target)
                return@a
            }

            if (Point.INVOKES.contains(point)) {
                remapAtInvoke(pMethod, "target", target)
                return@a
            }

            if (isWildcardOrRegex(pMethod, target)) return@a

            if (point == Point.NEW) {
                val newTarget = if (target.startsWith('(')) mTree.remapDesc(target) else {
                    val mClass = mTree.getClass(target) ?: return@a
                    mClass.newName ?: target
                }

                write { pAnnotation.setDeclaredAttributeValue("target", factory.createExpressionFromText("\"${newTarget}\"", pAnnotation)) }
                return@a
            }

            write { comment(pMethod, "TODO(Ravel): Unknown injection point $point") }
            logger.warn("$className#$methodName: Unknown injection point $point")
            return@a
        }

        if (annotationName == Shadow || annotationName == Overwrite) {
            val pMember = pAnnotation.parent<PsiMember>() ?: return@a
            val memberName = pMember.name ?: return@a

            val alias = pAnnotation.findDeclaredAttributeValue("alias")
            if (alias != null) {
                write { comment(pMember, "TODO(Ravel): @Shadow.alias is not supported") }
                logger.warn("$className#$memberName: @Shadow.alias is not supported")
                return@a
            }

            val (pTargetClass, mTargetClass) = targetClass(pMember) ?: return@a

            val pPrefix = pAnnotation.findDeclaredAttributeValue("prefix")
            val prefix = if (pPrefix is PsiLiteralExpression) (pPrefix.value as String) else "shadow$"
            val memberNameHasPrefix = annotationName == Shadow && memberName.startsWith(prefix)
            val targetName = if (memberNameHasPrefix) memberName.substring(prefix.length) else memberName

            var newMemberName: String? = when (pMember) {
                is PsiField -> {
                    if (mTargetClass == null) return@a
                    val mTargetField = mTargetClass.getField(targetName) ?: return@a
                    mTargetField.newName
                }

                is PsiMethod -> {
                    val targetMethodSignature = pMember.jvmDesc
                    if (pTargetClass != null) {
                        val pTargetMethod = findMethod(pTargetClass, targetName, targetMethodSignature) ?: return@a
                        remap(pMember, pTargetMethod)
                    } else {
                        val mTargetMethod = mTargetClass!!.getMethod(targetName, targetMethodSignature) ?: return@a
                        mTargetMethod.newName
                    }
                }

                else -> return@a
            }
            if (newMemberName == null) return@a
            if (memberNameHasPrefix) newMemberName = prefix + newMemberName

            rerun {
                val mClass = mTree.getOrPut(pClass)
                if (pMember is PsiField) mClass.putField(memberName, newMemberName)
                else if (pMember is PsiMethod) mClass.putMethod(memberName, pMember.jvmDesc, newMemberName)
            }
            return@a
        }

        val pMember = pAnnotation.parent<PsiMember>() ?: pClass
        write { comment(pMember, "TODO(Ravel): remapper for $annotationName is not implemented") }
        logger.warn("$className: remapper for $annotationName is not implemented")
    }
}
