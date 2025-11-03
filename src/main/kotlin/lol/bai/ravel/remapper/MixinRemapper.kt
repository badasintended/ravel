package lol.bai.ravel.remapper

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.psi.*
import fleet.util.hashSetMultiMap
import lol.bai.ravel.decapitalize
import net.fabricmc.mappingio.tree.MappingTree.ClassMapping

@Suppress("ConstPropertyName")
object MixinRemapper : JavaRemapper() {

    private val logger = thisLogger()

    val rawClassRegex = Regex("L([A-Za-z_$][A-Za-z0-9_$]*(?:/[A-Za-z_$][A-Za-z0-9_$]*)*);")

    // @formatter:off
    const val mixin          = "org.spongepowered.asm.mixin"
    const val Mixin          = "${mixin}.Mixin"
    const val Shadow         = "${mixin}.Shadow"
    const val Unique         = "${mixin}.Unique"
    const val Final          = "${mixin}.Final"
    const val Debug          = "${mixin}.Debug"
    const val Intrinsic      = "${mixin}.Intrinsic"
    const val Mutable        = "${mixin}.Mutable"
    const val Overwrite      = "${mixin}.Overwrite"
    const val Dynamic        = "${mixin}.Dynamic"
    const val Invoker        = "${mixin}.gen.Invoker"
    const val Accessor       = "${mixin}.gen.Accessor"
    const val At             = "${mixin}.injection.At"
    const val Slice          = "${mixin}.injection.Slice"
    const val Inject         = "${mixin}.injection.Inject"
    const val ModifyArg      = "${mixin}.injection.ModifyArg"
    const val ModifyArgs     = "${mixin}.injection.ModifyArgs"
    const val ModifyConstant = "${mixin}.injection.ModifyConstant"
    const val ModifyVariable = "${mixin}.injection.ModifyVariable"
    const val Redirect       = "${mixin}.injection.Redirect"
    const val Coerce         = "${mixin}.injection.Coerce"

    const val mixinextras           = "com.llamalad7.mixinextras"
    const val ModifyExpressionValue = "${mixinextras}.injector.ModifyExpressionValue"
    const val ModifyReceiver        = "${mixinextras}.injector.ModifyReceiver"
    const val ModifyReturnValue     = "${mixinextras}.injector.ModifyReturnValue"
    const val WrapWithCondition     = "${mixinextras}.injector.WrapWithCondition"
    const val WrapWithCondition2    = "${mixinextras}.injector.v2.WrapWithCondition"
    const val WrapMethod            = "${mixinextras}.injector.wrapmethod.WrapMethod"
    const val WrapOperation         = "${mixinextras}.injector.wrapoperation.WrapOperation"
    const val Cancellable           = "${mixinextras}.sugar.Cancellable"
    const val Local                 = "${mixinextras}.sugar.Local"
    const val Share                 = "${mixinextras}.sugar.Share"
    const val Definition            = "${mixinextras}.expression.Definition"
    // @formatter:on

    val INJECTS = setOf(
        Inject, ModifyArg, ModifyArgs, ModifyConstant, ModifyVariable, Redirect,
        ModifyExpressionValue, ModifyReceiver, ModifyReturnValue, WrapWithCondition, WrapWithCondition2, WrapMethod, WrapOperation
    )

    object Point {
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

    private fun isRemapped(pAnnotation: PsiAnnotation): Boolean {
        val pRemap = pAnnotation.findAttributeValue("remap") ?: return true
        pRemap as PsiLiteralExpression
        return pRemap.value as Boolean
    }

    private fun remapDesc(desc: String): String {
        return desc.replace(rawClassRegex) m@{ match ->
            val className = replaceAllQualifier(match.groupValues[1])
            val mClass = mClasses[className] ?: return@m match.value
            val newClassName = mappings.remap(mClass) ?: return@m match.value
            "L${newClassName};"
        }
    }

    override fun remap() {
        val java = JavaPsiFacade.getInstance(project)
        val factory = java.elementFactory
        val mixinTargets = hashSetMultiMap<String, String>()

        pFile.process r@{ pAnnotation: PsiAnnotation ->
            val pClass = pAnnotation.parent<PsiClass>() ?: return@r
            val className = pClass.qualifiedName ?: return@r
            val annotationName = pAnnotation.qualifiedName ?: return@r

            if (!annotationName.startsWith(mixin) && !annotationName.startsWith(mixinextras)) return@r

            if (annotationName == Slice) return@r
            if (annotationName == Unique) return@r
            if (annotationName == Final) return@r
            if (annotationName == Debug) return@r
            if (annotationName == Intrinsic) return@r
            if (annotationName == Mutable) return@r
            if (annotationName == Cancellable) return@r
            if (annotationName == Local) return@r
            if (annotationName == Share) return@r
            if (annotationName == Dynamic) return@r
            if (annotationName == Coerce) return@r

            fun warnNotLiterals(pElt: PsiElement) {
                write { comment(pElt, "TODO(Ravel): target not a literal or array of literals") }
                logger.warn("$className: target not a literal or array of literals")
            }

            if (annotationName == Mixin) {
                if (!isRemapped(pAnnotation)) return@r

                fun remapTarget(pTarget: PsiLiteralExpression) {
                    val target = pTarget.value as String
                    mixinTargets.put(className, replaceAllQualifier(target))

                    val mTargetClass = mClasses[replaceAllQualifier(target)] ?: return
                    var newTarget = mappings.remap(mTargetClass) ?: return
                    newTarget = replacePkgQualifier(newTarget)
                    write { pTarget.replace(factory.createExpressionFromText("\"${newTarget}\"", pTarget)) }
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
                        val targetClassName = pTargetClass.qualifiedName ?: return warnCantResolve()
                        mixinTargets.put(className, targetClassName)
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

                return@r
            }

            fun targetClassName(pMember: PsiMember): String? {
                val targetClassName = mixinTargets[className]
                if (targetClassName.size != 1) {
                    write { comment(pMember, "TODO(Ravel): Could not determine a single target") }
                    logger.warn("$className#${pMember.name}: Could not determine a single target")
                    return null
                }
                return targetClassName.first()
            }

            fun targetClass(pMember: PsiMember): Pair<PsiClass?, ClassMapping?>? {
                val targetClassName = targetClassName(pMember) ?: return null
                val pTargetClass = java.findClass(targetClassName, scope)
                val mTargetClass = mClasses[targetClassName]
                if (pTargetClass == null && mTargetClass == null) return null
                return pTargetClass to mTargetClass
            }

            if (annotationName == Invoker) {
                if (!isRemapped(pAnnotation)) return@r
                val pMethod = pAnnotation.parent<PsiMethod>() ?: return@r
                val methodName = pMethod.name
                val (pTargetClass, mTargetClass) = targetClass(pMethod) ?: return@r

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
                    write { comment(pMethod, "TODO(Ravel): No target method") }
                    logger.warn("$className#$methodName: No target method")
                    return@r
                }

                if (targetSignature == null) targetSignature = signature(pMethod)

                var newMethodName: String
                if (pTargetClass != null) {
                    val pTargetMethod = findMethod(pTargetClass, targetMethodName, targetSignature) ?: return@r
                    newMethodName = newMethodName(pMethod, pTargetMethod) ?: return@r
                } else {
                    val mTargetMethod = mTargetClass!!.getMethod(targetMethodName, targetSignature) ?: return@r
                    newMethodName = mappings.remap(mTargetMethod) ?: return@r
                }

                write {
                    pAnnotation.setDeclaredAttributeValue("value", factory.createExpressionFromText("\"${newMethodName}\"", pAnnotation))
                }
                return@r
            }

            if (annotationName == Accessor) {
                if (!isRemapped(pAnnotation)) return@r
                val pMethod = pAnnotation.parent<PsiMethod>() ?: return@r
                val methodName = pMethod.name
                val mTargetClass = targetClass(pMethod)?.second ?: return@r

                var targetFieldName = when {
                    methodName.startsWith("get") -> methodName.removePrefix("get").decapitalize()
                    methodName.startsWith("set") -> methodName.removePrefix("set").decapitalize()
                    methodName.startsWith("is") -> methodName.removePrefix("is").decapitalize()
                    else -> null
                }

                val pValue = pAnnotation.findDeclaredAttributeValue("value")
                if (pValue is PsiLiteralExpression) targetFieldName = pValue.value as String

                if (targetFieldName == null) {
                    write { comment(pMethod, "TODO(Ravel): No target field") }
                    logger.warn("$className#$methodName: No target field")
                    return@r
                }

                val mTargetField = mTargetClass.getField(targetFieldName, null) ?: return@r
                val newFieldName = mappings.remap(mTargetField) ?: return@r
                write {
                    pAnnotation.setDeclaredAttributeValue("value", factory.createExpressionFromText("\"${newFieldName}\"", null))
                }
                return@r
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
                if (!isRemapped(pAnnotation)) return@r
                val pMethod = pAnnotation.parent<PsiMethod>() ?: return@r
                val methodName = pMethod.name

                val pDesc = pAnnotation.findDeclaredAttributeValue("target")
                if (pDesc != null) {
                    write { comment(pMethod, "TODO(Ravel): target desc is not supported") }
                    logger.warn("$className#$methodName: target desc is not supported")
                    return@r
                }

                fun remapTargetMethod(pTarget: PsiLiteralExpression) {
                    val targetClassNames = mixinTargets[className]
                    if (targetClassNames.isEmpty()) {
                        write { comment(pMethod, "TODO(Ravel): no target class") }
                        logger.warn("$className#$methodName: no target class")
                        return
                    }

                    val targetMethod = pTarget.value as String
                    if (isWildcardOrRegex(pMethod, targetMethod)) return

                    val targetMethodAndDesc = if (targetMethod.startsWith('L')) targetMethod.substringAfter(';') else targetMethod
                    val targetMethodName = targetMethodAndDesc.substringBefore('(')
                    val targetMethodDesc = targetMethodAndDesc.removePrefix(targetMethodName)

                    fun write(newTargetMethodName: String) {
                        val newTargetMethodDesc = remapDesc(targetMethodDesc)
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

                    val newTargetMethodNames = hashMapOf<String, String>()
                    for (targetClassName in targetClassNames) {
                        val key = "${targetClassName}#${targetMethodName}"
                        newTargetMethodNames[key] = targetMethodName

                        val pTargetClass = java.findClass(targetClassName, scope)
                        if (pTargetClass != null) {
                            var newTargetMethodName: String? = null
                            if (targetMethodDesc.isNotEmpty()) {
                                val pTargetMethod = findMethod(pTargetClass, targetMethodName, targetMethodDesc) ?: return notFound()
                                newTargetMethodName = newMethodName(pMethod, pTargetMethod) ?: targetMethodName
                            } else {
                                for (pTargetMethod in pTargetClass.findMethodsByName(targetMethodName, false)) {
                                    val newTargetMethodName0 =
                                        newMethodName(pMethod, pTargetMethod) ?: targetMethodName
                                    if (newTargetMethodName != null && newTargetMethodName != newTargetMethodName0) return ambiguous()
                                    newTargetMethodName = newTargetMethodName0
                                }
                            }
                            newTargetMethodNames[key] = newTargetMethodName ?: targetMethodName
                        } else {
                            val mTargetClass = mClasses[targetClassName] ?: continue
                            var newTargetMethodName: String? = null
                            if (targetMethodDesc.isNotEmpty()) {
                                val mTargetMethod = mTargetClass.getMethod(targetMethodName, targetMethodDesc) ?: return notFound()
                                newTargetMethodName = mappings.remap(mTargetMethod) ?: targetMethodName
                            } else {
                                for (mTargetMethod in mTargetClass.methods) {
                                    if (mTargetMethod.srcName != targetMethodName) continue
                                    val newTargetMethodName0 = mappings.remap(mTargetMethod) ?: targetMethodName
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

                val pTargetMethods = pAnnotation.findDeclaredAttributeValue("method") ?: return@r
                when (pTargetMethods) {
                    is PsiLiteralExpression -> remapTargetMethod(pTargetMethods)
                    is PsiArrayInitializerMemberValue -> pTargetMethods.initializers.forEach {
                        if (it is PsiLiteralExpression) remapTargetMethod(it)
                        else warnNotLiterals(pMethod)
                    }

                    else -> warnNotLiterals(pMethod)
                }
                return@r
            }

            fun remapAtField(pMethod: PsiMethod, key: String, target: String) {
                if (isWildcardOrRegex(pMethod, target)) return

                val targetHasClassName = target.startsWith('L')
                val targetClassName =
                    if (targetHasClassName) replaceAllQualifier(target.removePrefix("L").substringBefore(';'))
                    else targetClassName(pMethod)
                if (targetClassName == null) {
                    write { comment(pMethod, "TODO(Ravel): Could not determine target field owner") }
                    logger.warn("$className#${pMethod.name}: Could not determine target field owner")
                    return
                }

                val targetFieldAndDesc = if (targetHasClassName) target.substringAfter(';') else target
                val targetFieldName = targetFieldAndDesc.substringBefore(':')
                val targetFieldDesc = targetFieldAndDesc.substringAfter(':', "")

                var newTargetClassName = targetClassName
                var newTargetFieldName = targetFieldName
                if (mClasses.contains(targetClassName)) {
                    val mClass = mClasses[targetClassName]!!
                    newTargetClassName = mappings.remap(mClass) ?: targetClassName
                    val mField = mClass.getField(targetFieldName, null)
                    if (mField != null) {
                        newTargetFieldName = mappings.remap(mField) ?: targetFieldName
                    }
                }

                val newTargetFieldDesc = remapDesc(targetFieldDesc)
                val newTarget = "\"L${newTargetClassName};${newTargetFieldName}:${newTargetFieldDesc}\""

                write { pAnnotation.setDeclaredAttributeValue(key, factory.createExpressionFromText(newTarget, pAnnotation)) }
            }

            fun remapAtInvoke(pMethod: PsiMethod, key: String, target: String) {
                if (isWildcardOrRegex(pMethod, target)) return

                if (!target.contains('(')) {
                    write { comment(pMethod, "TODO(Ravel): target method doesn't have a description") }
                    logger.warn("$className#${pMethod.name}: target method doesn't have a description")
                }

                val targetHasClassName = target.startsWith('L')
                val targetClassName =
                    if (targetHasClassName) replaceAllQualifier(target.removePrefix("L").substringBefore(';'))
                    else targetClassName(pMethod)
                if (targetClassName == null) {
                    write { comment(pMethod, "TODO(Ravel): Could not determine target method owner") }
                    logger.warn("$className#${pMethod.name}: Could not determine target method owner")
                    return
                }

                val targetMethodAndDesc = if (targetHasClassName) target.substringAfter(';') else target
                val targetMethodName = targetMethodAndDesc.substringBefore('(')
                val targetMethodDesc = targetMethodAndDesc.removePrefix(targetMethodName)

                var newTargetClassName = targetClassName
                var newTargetMethodName = targetMethodName
                if (mClasses.contains(targetClassName)) {
                    val mClass = mClasses[targetClassName]!!
                    newTargetClassName = mappings.remap(mClass) ?: targetClassName
                    val mMethod = mClass.getMethod(targetMethodName, targetMethodDesc)
                    if (mMethod != null) {
                        newTargetMethodName = mappings.remap(mMethod) ?: targetMethodName
                    }
                }

                val newTargetMethodDesc = remapDesc(targetMethodDesc)
                val newTarget = "\"L${newTargetClassName};${newTargetMethodName}${newTargetMethodDesc}\""

                write { pAnnotation.setDeclaredAttributeValue(key, factory.createExpressionFromText(newTarget, pAnnotation)) }
            }

            if (annotationName == Definition) {
                if (!isRemapped(pAnnotation)) return@r
                val pMethod = pAnnotation.parent<PsiMethod>() ?: return@r

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
                return@r
            }

            if (annotationName == At) {
                if (!isRemapped(pAnnotation)) return@r
                val pMethod = pAnnotation.parent<PsiMethod>() ?: return@r
                val methodName = pMethod.name

                val pPoint = pAnnotation.findDeclaredAttributeValue("value") ?: return@r
                pPoint as PsiLiteralExpression
                val point = pPoint.value as String

                if (point == Point.HEAD) return@r
                if (point == Point.RETURN) return@r
                if (point == Point.TAIL) return@r
                if (point == Point.JUMP) return@r
                if (point == Point.CONSTANT) return@r
                if (point == Point.STORE) return@r
                if (point == Point.LOAD) return@r
                if (point == Point.EXPRESSION) return@r

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

                val pTarget = pAnnotation.findDeclaredAttributeValue("target") ?: return@r
                pTarget as PsiLiteralExpression
                val target = pTarget.value as String

                if (point == Point.FIELD) {
                    remapAtField(pMethod, "target", target)
                    return@r
                }

                if (Point.INVOKES.contains(point)) {
                    remapAtInvoke(pMethod, "target", target)
                    return@r
                }

                if (isWildcardOrRegex(pMethod, target)) return@r

                if (point == Point.NEW) {
                    val newTarget = if (target.startsWith('(')) remapDesc(target) else {
                        val mClass = mClasses[replaceAllQualifier(replaceAllQualifier(target))] ?: return@r
                        mappings.remap(mClass) ?: target
                    }

                    write { pAnnotation.setDeclaredAttributeValue("target", factory.createExpressionFromText("\"${newTarget}\"", pAnnotation)) }
                    return@r
                }

                write { comment(pMethod, "TODO(Ravel): Unknown injection point $point") }
                logger.warn("$className#$methodName: Unknown injection point $point")
                return@r
            }

            if (annotationName == Shadow || annotationName == Overwrite) {
                if (!isRemapped(pAnnotation)) return@r
                val pMember = pAnnotation.parent<PsiMember>() ?: return@r
                val memberName = pMember.name ?: return@r

                val alias = pAnnotation.findDeclaredAttributeValue("alias")
                if (alias != null) {
                    write { comment(pMember, "TODO(Ravel): @Shadow.alias is not supported") }
                    logger.warn("$className#$memberName: @Shadow.alias is not supported")
                    return@r
                }

                val (pTargetClass, mTargetClass) = targetClass(pMember) ?: return@r

                val pPrefix = pAnnotation.findDeclaredAttributeValue("prefix")
                val prefix = if (pPrefix is PsiLiteralExpression) (pPrefix.value as String) else "shadow$"
                val memberNameHasPrefix = annotationName == Shadow && memberName.startsWith(prefix)
                val targetName = if (memberNameHasPrefix) memberName.substring(prefix.length) else memberName

                var newMemberName: String? = when (pMember) {
                    is PsiField -> {
                        if (mTargetClass == null) return@r
                        val mTargetField = mTargetClass.getField(targetName, null) ?: return@r
                        mappings.remap(mTargetField)
                    }

                    is PsiMethod -> {
                        val targetMethodSignature = signature(pMember)
                        if (pTargetClass != null) {
                            val pTargetMethod = findMethod(pTargetClass, targetName, targetMethodSignature) ?: return@r
                            newMethodName(pMember, pTargetMethod)
                        } else {
                            val mTargetMethod = mTargetClass!!.getMethod(targetName, targetMethodSignature) ?: return@r
                            mappings.remap(mTargetMethod)
                        }
                    }

                    else -> return@r
                }
                if (newMemberName == null) return@r
                if (memberNameHasPrefix) newMemberName = prefix + newMemberName

                fun resolveReferences(pRefFile: PsiJavaFile) = pRefFile.process r@{ pRef: PsiJavaCodeReferenceElement ->
                    val pTarget = pRef.resolve() ?: return@r
                    if (pTarget != pMember) return@r

                    val pRefElt = pRef.referenceNameElement as PsiIdentifier
                    write { pRefElt.replace(factory.createIdentifier(newMemberName)) }
                }

                val pModifiers = pMember.modifierList!!
                if (pModifiers.hasModifierProperty(PsiModifier.PRIVATE)) {
                    resolveReferences(pFile)
                } else if (pModifiers.hasModifierProperty(PsiModifier.PACKAGE_LOCAL)) {
                    val siblings = pFile.virtualFile!!.parent.children!!
                    val psiManager = PsiManager.getInstance(project)
                    for (vf in siblings) {
                        if (vf.extension != "java") continue
                        val pRefFile = psiManager.findFile(vf)
                        if (pRefFile !is PsiJavaFile) continue
                        resolveReferences(pRefFile)
                    }
                } else {
                    write { comment(pMember, "TODO(Ravel): only private and package-private shadow is supported") }
                    logger.warn("$className#$memberName: only private and package-private shadow is supported")
                    return@r
                }

                write { pMember.setName(newMemberName) }
                return@r
            }

            val pMember = pAnnotation.parent<PsiMember>() ?: pClass
            write { comment(pMember, "TODO(Ravel): remapper for $annotationName is not implemented") }
            logger.warn("$className: remapper for $annotationName is not implemented")
        }
    }

}
