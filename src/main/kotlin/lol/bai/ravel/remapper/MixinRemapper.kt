package lol.bai.ravel.remapper

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import lol.bai.ravel.decapitalize
import net.fabricmc.mappingio.tree.MappingTree.ClassMapping
import net.fabricmc.mappingio.tree.MappingTree.MethodMapping

// TODO:
//  - verify ambiguous result when remapping injection target
//  - mixin extras
@Suppress("ConstPropertyName")
object MixinRemapper : Remapper<PsiJavaFile>("java", { it as? PsiJavaFile }) {

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

    fun isRemapped(pAnnotation: PsiAnnotation): Boolean {
        val pRemap = pAnnotation.findAttributeValue("remap") ?: return true
        pRemap as PsiLiteralExpression
        return pRemap.value as Boolean
    }

    fun remapDesc(desc: String, mappings: Mappings, mClasses: ClassMappings): String {
        return desc.replace(rawClassRegex) m@{ match ->
            val className = replaceAllQualifier(match.groupValues[1])
            val mClass = mClasses[className] ?: return@m match.value
            val newClassName = mappings.map(mClass) ?: return@m match.value
            "L${newClassName};"
        }
    }

    override fun remap(project: Project, mappings: Mappings, mClasses: ClassMappings, pFile: PsiJavaFile, write: Writer) {
        val psi = JavaPsiFacade.getInstance(project).elementFactory
        val mixinTargets = hashMapOf<String, String>()

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
                write { JavaRemapper.comment(psi, pElt, "TODO(Ravel): target not a literal or array of literals") }
                thisLogger().warn("$className: target not a literal or array of literals")
            }

            if (annotationName == Mixin) {
                if (!isRemapped(pAnnotation)) return@r

                fun remapTarget(pTarget: PsiLiteralExpression) {
                    val target = pTarget.value as String
                    val mTargetClass = mClasses[replaceAllQualifier(target)] ?: return
                    var newTarget = mappings.map(mTargetClass) ?: return
                    newTarget = replacePkgQualifier(newTarget)
                    write { pTarget.replace(psi.createExpressionFromText("\"${newTarget}\"", pTarget)) }
                }

                val pTargets = pAnnotation.findDeclaredAttributeValue("targets")
                if (pTargets != null) when (pTargets) {
                    is PsiLiteralExpression -> {
                        val value = pTargets.value as String
                        remapTarget(pTargets)
                        mixinTargets[className] = replaceAllQualifier(value)
                    }

                    is PsiArrayInitializerMemberValue -> pTargets.initializers.forEach {
                        if (it is PsiLiteralExpression) remapTarget(it)
                        else warnNotLiterals(pClass)
                    }

                    else -> warnNotLiterals(pClass)
                }

                val pValues = pAnnotation.findDeclaredAttributeValue("value")
                if (pValues != null) {
                    if (pValues is PsiClassObjectAccessExpression) {
                        mixinTargets[className] = pValues.operand.type.canonicalText
                    }
                }

                return@r
            }

            fun targetClassName(pMember: PsiMember): String? {
                val targetClassName = mixinTargets[className]
                if (targetClassName == null) {
                    write { JavaRemapper.comment(psi, pMember, "TODO(Ravel): Could not determine a single target") }
                    thisLogger().warn("$className#${pMember.name}: Could not determine a single target")
                    return null
                }
                return targetClassName
            }

            fun mTargetClass(pMember: PsiMember): ClassMapping? {
                val targetClassName = targetClassName(pMember) ?: return null
                val mTargetClass = mClasses[targetClassName] ?: return null
                return mTargetClass
            }

            if (annotationName == Invoker) {
                if (!isRemapped(pAnnotation)) return@r
                val pMethod = pAnnotation.parent<PsiMethod>() ?: return@r
                val methodName = pMethod.name
                val mTargetClass = mTargetClass(pMethod) ?: return@r

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
                    write { JavaRemapper.comment(psi, pMethod, "TODO(Ravel): No target method") }
                    thisLogger().warn("$className#$methodName: No target method")
                    return@r
                }

                if (targetSignature == null) targetSignature = JavaRemapper.signature(pMethod)

                val mTargetMethod = mTargetClass.getMethod(targetMethodName, targetSignature) ?: return@r
                val newMethodName = mappings.map(mTargetMethod) ?: return@r
                write {
                    pAnnotation.setDeclaredAttributeValue("value", psi.createExpressionFromText("\"${newMethodName}\"", pAnnotation))
                }
                return@r
            }

            if (annotationName == Accessor) {
                if (!isRemapped(pAnnotation)) return@r
                val pMethod = pAnnotation.parent<PsiMethod>() ?: return@r
                val methodName = pMethod.name
                val mTargetClass = mTargetClass(pMethod) ?: return@r

                var targetFieldName = when {
                    methodName.startsWith("get") -> methodName.removePrefix("get").decapitalize()
                    methodName.startsWith("set") -> methodName.removePrefix("set").decapitalize()
                    methodName.startsWith("is") -> methodName.removePrefix("is").decapitalize()
                    else -> null
                }

                val pValue = pAnnotation.findDeclaredAttributeValue("value")
                if (pValue is PsiLiteralExpression) targetFieldName = pValue.value as String

                if (targetFieldName == null) {
                    write { JavaRemapper.comment(psi, pMethod, "TODO(Ravel): No target field") }
                    thisLogger().warn("$className#$methodName: No target field")
                    return@r
                }

                val mTargetField = mTargetClass.getField(targetFieldName, null) ?: return@r
                val newFieldName = mappings.map(mTargetField) ?: return@r
                write {
                    pAnnotation.setDeclaredAttributeValue("value", psi.createExpressionFromText("\"${newFieldName}\"", null))
                }
                return@r
            }

            fun isWildcardOrRegex(pMethod: PsiMethod, target: String): Boolean {
                if (target.contains('*') || target.contains(' ')) {
                    write { JavaRemapper.comment(psi, pMethod, "TODO(Ravel): wildcard and regex target are not supported") }
                    thisLogger().warn("$className#${pMethod.name}: wildcard and regex target are not supported")
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
                    write { JavaRemapper.comment(psi, pMethod, "TODO(Ravel): target desc is not supported") }
                    thisLogger().warn("$className#$methodName: target desc is not supported")
                    return@r
                }

                fun remapTargetMethod(pTarget: PsiLiteralExpression) {
                    val targetClassName = targetClassName(pMethod) ?: return

                    val targetMethod = pTarget.value as String
                    if (isWildcardOrRegex(pMethod, targetMethod)) return

                    val targetMethodAndDesc = if (targetMethod.startsWith('L')) targetMethod.substringAfter(';') else targetMethod
                    val targetMethodName = targetMethodAndDesc.substringBefore('(')
                    val targetMethodDesc = targetMethodAndDesc.removePrefix(targetMethodName)

                    var newTargetMethodName = targetMethod
                    if (mClasses.contains(targetClassName)) {
                        val mClass = mClasses[targetClassName]!!

                        if (targetMethodDesc.isNotEmpty()) {
                            val mMethod = mClass.getMethod(targetMethodName, targetMethodDesc)
                            if (mMethod != null) {
                                newTargetMethodName = mappings.map(mMethod) ?: targetMethodName
                            }
                        } else {
                            var mMethod: MethodMapping? = null
                            for (it in mClass.methods) {
                                if (it.srcName != targetMethodName) continue
                                if (mMethod != null) {
                                    write { JavaRemapper.comment(psi, pMethod, "TODO(Ravel): target method is ambiguous") }
                                    thisLogger().warn("$className#$methodName: target method is ambiguous")
                                    return
                                }
                                mMethod = it
                            }
                            if (mMethod != null) newTargetMethodName = mappings.map(mMethod) ?: targetMethodName
                        }
                    }

                    val newTargetMethodDesc = remapDesc(targetMethodDesc, mappings, mClasses)
                    val newTarget = "\"${newTargetMethodName}${newTargetMethodDesc}\""

                    write { pTarget.replace(psi.createExpressionFromText(newTarget, pTarget)) }
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
                    else mixinTargets[className]
                if (targetClassName == null) {
                    write { JavaRemapper.comment(psi, pMethod, "TODO(Ravel): Could not determine target field owner") }
                    thisLogger().warn("$className#${pMethod.name}: Could not determine target field owner")
                    return
                }

                val targetFieldAndDesc = if (targetHasClassName) target.substringAfter(';') else target
                val targetFieldName = targetFieldAndDesc.substringBefore(':')
                val targetFieldDesc = targetFieldAndDesc.substringAfter(':', "")

                var newTargetClassName = targetClassName
                var newTargetFieldName = targetFieldName
                if (mClasses.contains(targetClassName)) {
                    val mClass = mClasses[targetClassName]!!
                    newTargetClassName = mappings.map(mClass) ?: targetClassName
                    val mField = mClass.getField(targetFieldName, null)
                    if (mField != null) {
                        newTargetFieldName = mappings.map(mField) ?: targetFieldName
                    }
                }

                val newTargetFieldDesc = remapDesc(targetFieldDesc, mappings, mClasses)
                val newTarget = "\"L${newTargetClassName};${newTargetFieldName}:${newTargetFieldDesc}\""

                write { pAnnotation.setDeclaredAttributeValue(key, psi.createExpressionFromText(newTarget, pAnnotation)) }
            }

            fun remapAtInvoke(pMethod: PsiMethod, key: String, target: String) {
                if (isWildcardOrRegex(pMethod, target)) return

                if (!target.contains('(')) {
                    write { JavaRemapper.comment(psi, pMethod, "TODO(Ravel): target method doesn't have a description") }
                    thisLogger().warn("$className#${pMethod.name}: target method doesn't have a description")
                }

                val targetHasClassName = target.startsWith('L')
                val targetClassName =
                    if (targetHasClassName) replaceAllQualifier(target.removePrefix("L").substringBefore(';'))
                    else mixinTargets[className]
                if (targetClassName == null) {
                    write { JavaRemapper.comment(psi, pMethod, "TODO(Ravel): Could not determine target method owner") }
                    thisLogger().warn("$className#${pMethod.name}: Could not determine target method owner")
                    return
                }

                val targetMethodAndDesc = if (targetHasClassName) target.substringAfter(';') else target
                val targetMethodName = targetMethodAndDesc.substringBefore('(')
                val targetMethodDesc = targetMethodAndDesc.removePrefix(targetMethodName)

                var newTargetClassName = targetClassName
                var newTargetMethodName = targetMethodName
                if (mClasses.contains(targetClassName)) {
                    val mClass = mClasses[targetClassName]!!
                    newTargetClassName = mappings.map(mClass) ?: targetClassName
                    val mMethod = mClass.getMethod(targetMethodName, targetMethodDesc)
                    if (mMethod != null) {
                        newTargetMethodName = mappings.map(mMethod) ?: targetMethodName
                    }
                }

                val newTargetMethodDesc = remapDesc(targetMethodDesc, mappings, mClasses)
                val newTarget = "\"L${newTargetClassName};${newTargetMethodName}${newTargetMethodDesc}\""

                write { pAnnotation.setDeclaredAttributeValue(key, psi.createExpressionFromText(newTarget, pAnnotation)) }
            }

            if (annotationName == Definition) {
                if (!isRemapped(pAnnotation)) return@r
                val pMethod = pAnnotation.parent<PsiMethod>() ?: return@r

                fun remapTarget(pTargetElt: PsiElement, key: String, remap: (PsiMethod, String, String) -> Unit) = when (pTargetElt) {
                    is PsiLiteralExpression -> remap(pMethod, key, pTargetElt.value as String)
                    is PsiArrayInitializerMemberValue -> pTargetElt.initializers.forEach {
                        if (it is PsiLiteralExpression) remap(pMethod,key, it.value as String)
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
                    write { JavaRemapper.comment(psi, pMethod, "TODO(Ravel): @At.desc is not supported") }
                    thisLogger().warn("$className#$methodName: @At.desc is not supported")
                }

                val pArgs = pAnnotation.findDeclaredAttributeValue("args")
                if (pArgs != null) {
                    write { JavaRemapper.comment(psi, pMethod, "TODO(Ravel): @At.args is not supported") }
                    thisLogger().warn("$className#$methodName: @At.args is not supported")
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
                    val newTarget = if (target.startsWith('(')) remapDesc(target, mappings, mClasses) else {
                        val mClass = mClasses[replaceAllQualifier(replaceAllQualifier(target))] ?: return@r
                        mappings.map(mClass) ?: target
                    }

                    write { pAnnotation.setDeclaredAttributeValue("target", psi.createExpressionFromText("\"${newTarget}\"", pAnnotation)) }
                    return@r
                }

                write { JavaRemapper.comment(psi, pMethod, "TODO(Ravel): Unknown injection point $point") }
                thisLogger().warn("$className#$methodName: Unknown injection point $point")
                return@r
            }

            if (annotationName == Shadow || annotationName == Overwrite) {
                if (!isRemapped(pAnnotation)) return@r
                val pMember = pAnnotation.parent<PsiMember>() ?: return@r
                val memberName = pMember.name ?: return@r

                val alias = pAnnotation.findDeclaredAttributeValue("alias")
                if (alias != null) {
                    write { JavaRemapper.comment(psi, pMember, "TODO(Ravel): @Shadow.alias is not supported") }
                    thisLogger().warn("$className#$memberName: @Shadow.alias is not supported")
                    return@r
                }

                val mTargetClass = mTargetClass(pMember) ?: return@r

                val pPrefix = pAnnotation.findDeclaredAttributeValue("prefix")
                val prefix = if (pPrefix is PsiLiteralExpression) (pPrefix.value as String) else "shadow$"
                val memberNameHasPrefix = annotationName == Shadow && memberName.startsWith(prefix)
                val targetName = if (memberNameHasPrefix) memberName.substring(prefix.length) else memberName

                var newMemberName: String? = when (pMember) {
                    is PsiField -> {
                        val mTargetField = mTargetClass.getField(targetName, null) ?: return@r
                        mappings.map(mTargetField)
                    }

                    is PsiMethod -> {
                        val targetMethodSignature = JavaRemapper.signature(pMember)
                        val mTargetMethod = mTargetClass.getMethod(targetName, targetMethodSignature) ?: return@r
                        mappings.map(mTargetMethod)
                    }

                    else -> return@r
                }
                if (newMemberName == null) return@r
                if (memberNameHasPrefix) newMemberName = prefix + newMemberName

                fun resolveReferences(pRefFile: PsiJavaFile) = pRefFile.process r@{ pRef: PsiJavaCodeReferenceElement ->
                    val pTarget = pRef.resolve() ?: return@r
                    if (pTarget != pMember) return@r

                    val pRefElt = pRef.referenceNameElement as PsiIdentifier
                    write { pRefElt.replace(psi.createIdentifier(newMemberName)) }
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
                    write { JavaRemapper.comment(psi, pMember, "TODO(Ravel): only private and package-private shadow is supported") }
                    thisLogger().warn("$className#$memberName: only private and package-private shadow is supported")
                    return@r
                }

                write { pMember.setName(newMemberName) }
                return@r
            }

            val pMember = pAnnotation.parent<PsiMember>() ?: pClass
            write { JavaRemapper.comment(psi, pMember, "TODO(Ravel): remapper for $annotationName is not implemented") }
            thisLogger().warn("$className: remapper for $annotationName is not implemented")
        }
    }

}
