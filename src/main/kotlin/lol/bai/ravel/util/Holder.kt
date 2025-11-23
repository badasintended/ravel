package lol.bai.ravel.util

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder

typealias HolderKey<T> = Key<Holder<T?>>

data class Holder<T>(val value: T) {
    companion object {
        val nullHolder = Holder(null)

        fun <T> key(key: String): HolderKey<T> {
            return Key.create<Holder<T?>>("lol.bai.ravel.${key}")
        }
    }
}

@Suppress("UNCHECKED_CAST")
val <T> T?.held: Holder<T?>
    get() = if (this == null) Holder.nullHolder as Holder<T?> else Holder(this)

fun <T> HolderKey<T>.put(holder: UserDataHolder, value: T?): T? {
    holder.putUserData(this, value.held)
    return value
}
