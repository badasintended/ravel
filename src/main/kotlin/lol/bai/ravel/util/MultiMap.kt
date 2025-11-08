package lol.bai.ravel.util

class MultiMap<K, V>(
    private val map: MutableMap<K, MutableCollection<V>> = linkedMapOf(),
    private val factory: () -> MutableCollection<V>,
) : MutableMap<K, MutableCollection<V>> by map {

    fun put(key: K, value: V) {
        map.getOrPut(key, factory).add(value)
    }

}

fun <K, V> setMultiMap() = MultiMap<K, V> { HashSet() }
fun <K, V> linkedSetMultiMap() = MultiMap<K, V> { LinkedHashSet() }
fun <K, V> listMultiMap() = MultiMap<K, V> { ArrayList() }
