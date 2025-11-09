package lol.bai.ravel.util

class Cache<K, V> {
    private val map = hashMapOf<K, Holder<V?>>()

    val values get() = map.values
        .filter { it.value != null }
        .map { it.value!! as V }

    fun has(key: K): Boolean = map.containsKey(key)
    fun get(key: K): V? = map.getOrPut(key) { null.held }.value
    fun put(key: K, value: V?) {
        map[key] = value.held
    }

    inline fun getOrPut(key: K, defaultValue: () -> V?): V? {
        if (has(key)) return get(key)
        val new = defaultValue()
        put(key, new)
        return new
    }
}

