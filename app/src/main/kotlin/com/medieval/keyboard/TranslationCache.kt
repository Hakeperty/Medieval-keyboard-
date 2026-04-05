package com.medieval.keyboard

object TranslationCache {
    private const val MAX_SIZE = 200

    private val cache = object : LinkedHashMap<String, String>(MAX_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_SIZE
        }
    }

    @Synchronized
    fun get(key: String): String? = cache[key.lowercase()]

    @Synchronized
    fun put(key: String, value: String) {
        cache[key.lowercase()] = value
    }

    @Synchronized
    fun clear() = cache.clear()
}
