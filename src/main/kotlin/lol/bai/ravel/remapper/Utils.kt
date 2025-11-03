package lol.bai.ravel.remapper

import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import lol.bai.ravel.Mapping
import net.fabricmc.mappingio.tree.MappingTree.*

typealias Mappings = List<Mapping>
typealias ClassMappings = Map<String, ClassMapping>
typealias Writer = (() -> Unit) -> Unit

private val rawQualifierSeparators = Regex("[/$]")
internal fun replaceAllQualifier(raw: String) = raw.replace(rawQualifierSeparators, ".")
internal fun replacePkgQualifier(raw: String) = raw.replace('/', '.')

internal fun Mappings.remap(cls: ClassMapping): String? {
    var className = cls.srcName

    for (m in this) {
        val mClass = m.tree.getClass(className) ?: return null
        className = mClass.getName(m.dest)
    }

    return if (className == cls.srcName) null else className
}

internal fun Mappings.remap(field: FieldMapping): String? {
    var className = field.owner.srcName
    var fieldName = field.srcName

    for (m in this) {
        val mClass = m.tree.getClass(className) ?: return null
        val mField = mClass.getField(fieldName, null) ?: return null
        className = mClass.getName(m.dest)
        fieldName = mField.getName(m.dest)
    }

    return if (fieldName == field.srcName) null else fieldName
}

internal fun Mappings.remap(method: MethodMapping): String? {
    var className = method.owner.srcName
    var methodName = method.srcName
    var methodDesc = method.srcDesc

    for (m in this) {
        val mClass = m.tree.getClass(className) ?: return null
        val mMethod = mClass.getMethod(methodName, methodDesc) ?: return null
        className = mClass.getName(m.dest)
        methodName = mMethod.getName(m.dest)
        methodDesc = mMethod.getDesc(m.dest)
    }

    return if (methodName == method.srcName) null else methodName
}

@Suppress("UnstableApiUsage")
internal fun PsiType.toRaw(): String {
    return when (this) {
        is PsiArrayType -> "[" + componentType.toRaw()
        is PsiPrimitiveType -> kind.binaryName
        is PsiClassType -> {
            fun rawName(cls: PsiClass): String? {
                if (cls is PsiTypeParameter) {
                    val bounds = cls.extendsList.referencedTypes
                    if (bounds.isEmpty()) return "Ljava/lang/Object;"
                    return rawName(bounds.first().resolve()!!)
                }

                val fullName = cls.qualifiedName ?: return null
                val parent = cls.containingClass
                if (parent != null) return rawName(parent) + "$" + cls.name
                return fullName.replace('.', '/')
            }

            val name = rawName(resolve()!!)
            "L${name};"
        }

        else -> {
            val ret = canonicalText
            if (ret.contains('<')) ret.substringBefore('<') else ret
        }
    }
}

internal inline fun <reified E : PsiElement> PsiElement.process(crossinline action: (E) -> Unit) {
    PsiTreeUtil.processElements(this, E::class.java) {
        action(it)
        true
    }
}

internal inline fun <reified E : PsiElement> PsiElement.parent(): E? {
    return PsiTreeUtil.getParentOfType(this, E::class.java)
}
