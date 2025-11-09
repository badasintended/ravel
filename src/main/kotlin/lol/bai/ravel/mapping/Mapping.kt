package lol.bai.ravel.mapping

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import lol.bai.ravel.psi.jvmDesc
import lol.bai.ravel.psi.jvmName
import lol.bai.ravel.util.Cache

val rawClassRegex = Regex("L([A-Za-z_$][A-Za-z0-9_$]*(?:/[A-Za-z_$][A-Za-z0-9_$]*)*);")
val rawQualifierSeparators = Regex("[/$]")

class MappingTree {
    private val classes = linkedMapOf<String, ClassMapping>()

    fun getClass(name: String?) = if (name == null) null else classes[name]
    fun getOrPutClass(oldName: String, newName: String?) = classes.getOrPut(oldName) { BasicClassMapping(oldName, newName) }
    fun putClass(oldName: String, newName: String?) = putClass(BasicClassMapping(oldName, newName))
    fun putClass(mapping: ClassMapping) {
        classes[mapping.oldName] = mapping
    }

    fun getOrPut(pClass: PsiClass) = getOrPutClass(pClass.jvmName!!, pClass.jvmName)
    fun get(pClass: PsiClass): ClassMapping? {
        val classJvmName = pClass.jvmName ?: return null
        return getClass(classJvmName)
    }

    fun get(pField: PsiField): FieldMapping? {
        val pClass = pField.containingClass ?: return null
        val mClass = get(pClass) ?: return null
        return mClass.getField(pField.name)
    }

    fun get(pMethod: PsiMethod): MethodMapping? {
        val pClass = pMethod.containingClass ?: return null
        val mClass = get(pClass) ?: return null
        return mClass.getMethod(pMethod.name, pMethod.jvmDesc)
    }

    fun remapDesc(desc: String): String {
        return desc.replace(rawClassRegex) m@{ match ->
            val className = match.groupValues[1]
            val mClass = getClass(className) ?: return@m match.value
            val newClassName = mClass.newName ?: return@m match.value
            "L${newClassName};"
        }
    }
}

interface Mapping {
    val oldName: String
    val newName: String?
}

abstract class ClassMapping : Mapping {
    val newFullPeriodName get() = newName?.replace(rawQualifierSeparators, ".")
    val newPkgPeriodName get() = newName?.replace('/', '.')

    private val fieldCache = Cache<String, FieldMapping>()
    private val methodCache = Cache<String, MethodMapping>()

    protected open fun getAllFieldsImpl(): Collection<FieldMapping> = emptyList()
    val fields: List<FieldMapping>
        get() {
            getAllFieldsImpl().forEach { fieldCache.put(it.oldName, it) }
            return fieldCache.values
        }

    protected open fun getFieldImpl(name: String): FieldMapping? = null
    fun getField(name: String) = fieldCache.getOrPut(name) { getFieldImpl(name) }
    fun putField(mapping: FieldMapping) = fieldCache.put(mapping.oldName, mapping)
    fun putField(oldName: String, newName: String?) = putField(BasicFieldMapping(oldName, newName))

    protected open fun getAllMethodImpl(): Collection<MethodMapping> = emptyList()
    val methods: List<MethodMapping>
        get() {
            getAllMethodImpl().forEach { methodCache.put("${it.oldName}${it.oldDesc}", it) }
            return methodCache.values
        }

    protected open fun getMethodImpl(name: String, desc: String): MethodMapping? = null
    fun getMethod(name: String, desc: String) = methodCache.getOrPut("${name}${desc}") { getMethodImpl(name, desc) }
    fun getMethods(name: String) = methods.filter { it.oldName == name }
    fun putMethod(mapping: MethodMapping) = methodCache.put("${mapping.oldName}${mapping.oldDesc}", mapping)
    fun putMethod(oldName: String, oldDesc: String, newName: String?) = putMethod(BasicMethodMapping(oldName, oldDesc, newName))
}

abstract class FieldMapping : Mapping

abstract class MethodMapping : Mapping {
    abstract val oldDesc: String
}
