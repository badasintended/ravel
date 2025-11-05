package lol.bai.ravel.psi

import com.intellij.psi.*
import com.intellij.psi.PsiModifier.ModifierConstant
import lol.bai.ravel.util.Holder
import lol.bai.ravel.util.put

private val jvmNameHolder = Holder.key<String>("jvmName")
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

fun PsiModifierListOwner.explicitly(@ModifierConstant modifier: String): Boolean {
    val modifierList = modifierList ?: return false
    return modifierList.hasExplicitModifier(modifier)
}

private val jvmDescHolder = Holder.key<String>("jvmDesc")
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
                    return jvmName(bounds.first().resolve()!!)
                }

                return cls.jvmName
            }

            val name = jvmName(resolve()!!)
            "L${name};"
        }

        else -> {
            val ret = canonicalText
            if (ret.contains('<')) ret.substringBefore('<') else ret
        }
    }
