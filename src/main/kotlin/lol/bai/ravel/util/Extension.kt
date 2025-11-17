package lol.bai.ravel.util

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.RequiredElement
import com.intellij.util.xmlb.annotations.Attribute

class Extension<T>(name: String) {
    private val ep = ExtensionPointName.create<ExtensionBean>(name)

    @Suppress("UNCHECKED_CAST")
    fun createInstances(): List<T> {
        val result = mutableListOf<T>()
        ep.forEachExtensionSafe {
            result.add(Class.forName(it.implementation).getConstructor().newInstance() as T)
        }
        return result
    }
}

class ExtensionBean {

    @field:Attribute
    @field:RequiredElement
    lateinit var implementation: String

}
