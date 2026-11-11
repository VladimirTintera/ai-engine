package eu.tintera.ai.core

import kotlin.concurrent.atomics.AtomicReference

/**
 * Mapa pro víc vláken ve společném kódu, místo `ConcurrentHashMap` z JVM: neměnná mapa
 * v [AtomicReference], změny přes compare-and-set. Položek je pár (modely v katalogu)
 * a mění se zřídka (začátek a konec stahování), takže kopie při změně nevadí.
 */
internal class ConcurrentMap<K, V : Any> {

    private val entries = AtomicReference<Map<K, V>>(emptyMap())

    operator fun get(key: K): V? = entries.load()[key]

    operator fun set(key: K, value: V) {
        update { it + (key to value) }
    }

    /** [create] se může zavolat víckrát, když dvě vlákna přidávají najednou; vrátí se jen jedna hodnota. */
    fun getOrPut(key: K, create: () -> V): V {
        while (true) {
            val current = entries.load()
            current[key]?.let { return it }
            val value = create()
            if (entries.compareAndSet(current, current + (key to value))) return value
        }
    }

    fun remove(key: K): V? {
        while (true) {
            val current = entries.load()
            val value = current[key] ?: return null
            if (entries.compareAndSet(current, current - key)) return value
        }
    }

    /** Odebere [key], jen když k němu patří právě [value]. */
    fun remove(key: K, value: V): Boolean {
        while (true) {
            val current = entries.load()
            if (current[key] != value) return false
            if (entries.compareAndSet(current, current - key)) return true
        }
    }

    /** Nastaví [key] na [value], jen když k němu pořád patří [expected] (`null` = nic). */
    fun compareAndPut(key: K, expected: V?, value: V): Boolean {
        while (true) {
            val current = entries.load()
            if (current[key] != expected) return false
            if (entries.compareAndSet(current, current + (key to value))) return true
        }
    }

    private inline fun update(change: (Map<K, V>) -> Map<K, V>) {
        while (true) {
            val current = entries.load()
            if (entries.compareAndSet(current, change(current))) return
        }
    }
}

/** Množina pro víc vláken, stejně jako [ConcurrentMap]. */
internal class ConcurrentSet<T> {

    private val entries = AtomicReference<Set<T>>(emptySet())

    operator fun contains(value: T): Boolean = value in entries.load()

    /** Vrací `true`, když tam [value] ještě nebyla. */
    fun add(value: T): Boolean {
        while (true) {
            val current = entries.load()
            if (value in current) return false
            if (entries.compareAndSet(current, current + value)) return true
        }
    }

    operator fun plusAssign(value: T) {
        add(value)
    }

    /** Vrací `true`, když tam [value] byla. */
    fun remove(value: T): Boolean {
        while (true) {
            val current = entries.load()
            if (value !in current) return false
            if (entries.compareAndSet(current, current - value)) return true
        }
    }
}
