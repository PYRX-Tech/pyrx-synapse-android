/*
 * InMemoryStorage.kt
 * PYRXSynapse — Android
 *
 * Test-only [PyrxStorage] backed by a concurrent map. Lives in the main
 * source set (not testFixtures) so downstream consumer apps that want to
 * inject a deterministic store into their own debug menus / UI tests can
 * also use it.
 *
 * Mirrors the iOS test InMemoryStorage helper.
 */

package tech.pyrx.synapse.storage

import java.util.concurrent.ConcurrentHashMap

public class InMemoryStorage : PyrxStorage {
    private val map: MutableMap<String, String> = ConcurrentHashMap()

    override fun get(key: PyrxStorageKey): String? = map[key.rawValue]

    override fun set(
        key: PyrxStorageKey,
        value: String,
    ) {
        map[key.rawValue] = value
    }

    override fun delete(key: PyrxStorageKey) {
        map.remove(key.rawValue)
    }

    override fun wipe() {
        for (key in PyrxStorageKey.ALL) {
            map.remove(key.rawValue)
        }
    }

    /** Returns a defensive copy. Useful for assertions in tests. */
    public fun snapshot(): Map<String, String> = map.toMap()
}
