package androidx.datastore.preferences.core

import androidx.datastore.core.DataStore

abstract class Preferences internal constructor() {
    class Key<T> internal constructor(val name: String)
    abstract operator fun <T> get(key: Key<T>): T?
    abstract operator fun <T> contains(key: Key<T>): Boolean
}

class MutablePreferences internal constructor() : Preferences() {
    override fun <T> get(key: Key<T>): T? = null
    override fun <T> contains(key: Key<T>): Boolean = false
    operator fun <T> set(key: Key<T>, value: T) {}
    fun <T> remove(key: Key<T>): T = TODO()
    fun clear() {}
}

fun stringPreferencesKey(name: String): Preferences.Key<String> = Preferences.Key(name)
fun booleanPreferencesKey(name: String): Preferences.Key<Boolean> = Preferences.Key(name)
fun intPreferencesKey(name: String): Preferences.Key<Int> = Preferences.Key(name)
fun longPreferencesKey(name: String): Preferences.Key<Long> = Preferences.Key(name)

suspend fun DataStore<Preferences>.edit(transform: suspend (MutablePreferences) -> Unit): Preferences = TODO()
