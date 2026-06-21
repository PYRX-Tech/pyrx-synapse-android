/*
 * InMemoryStorageTest.kt
 * PYRXSynapse — Android
 *
 * Validates the test seam itself. Two reasons we test this:
 *   1. The Pyrx unit tests rely on InMemoryStorage behaving identically to
 *      EncryptedStore — a broken test seam would silently mask SDK bugs.
 *   2. The storage interface contract (round-trip, delete, wipe, missing
 *      key returns null) is exercised here so we don't need a JVM-runnable
 *      EncryptedStore test (which would require an Android Context).
 */

package tech.pyrx.synapse.storage

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemoryStorageTest {
    @Test
    fun `get returns null for unset key`() {
        val store = InMemoryStorage()
        assertNull(store.get(PyrxStorageKey.ANONYMOUS_ID))
        assertNull(store.get(PyrxStorageKey.EXTERNAL_ID))
        assertNull(store.get(PyrxStorageKey.DEVICE_TOKEN))
    }

    @Test
    fun `set then get round-trips the value`() {
        val store = InMemoryStorage()
        store.set(PyrxStorageKey.ANONYMOUS_ID, "anon-123")
        assertEquals("anon-123", store.get(PyrxStorageKey.ANONYMOUS_ID))
    }

    @Test
    fun `set overwrites prior value for the same key`() {
        val store = InMemoryStorage()
        store.set(PyrxStorageKey.EXTERNAL_ID, "user-1")
        store.set(PyrxStorageKey.EXTERNAL_ID, "user-2")
        assertEquals("user-2", store.get(PyrxStorageKey.EXTERNAL_ID))
    }

    @Test
    fun `delete removes the key`() {
        val store = InMemoryStorage()
        store.set(PyrxStorageKey.DEVICE_TOKEN, "token-xyz")
        store.delete(PyrxStorageKey.DEVICE_TOKEN)
        assertNull(store.get(PyrxStorageKey.DEVICE_TOKEN))
    }

    @Test
    fun `delete of nonexistent key is a no-op`() {
        val store = InMemoryStorage()
        // Should not throw.
        store.delete(PyrxStorageKey.ANONYMOUS_ID)
        assertNull(store.get(PyrxStorageKey.ANONYMOUS_ID))
    }

    @Test
    fun `wipe clears every well-known key`() {
        val store = InMemoryStorage()
        store.set(PyrxStorageKey.ANONYMOUS_ID, "anon")
        store.set(PyrxStorageKey.EXTERNAL_ID, "ext")
        store.set(PyrxStorageKey.DEVICE_TOKEN, "tok")

        store.wipe()

        assertNull(store.get(PyrxStorageKey.ANONYMOUS_ID))
        assertNull(store.get(PyrxStorageKey.EXTERNAL_ID))
        assertNull(store.get(PyrxStorageKey.DEVICE_TOKEN))
    }

    @Test
    fun `wipe on empty store is a no-op`() {
        val store = InMemoryStorage()
        store.wipe()
        assertEquals(0, store.snapshot().size)
    }

    @Test
    fun `snapshot returns a defensive copy`() {
        val store = InMemoryStorage()
        store.set(PyrxStorageKey.ANONYMOUS_ID, "anon")

        val first = store.snapshot()
        store.set(PyrxStorageKey.EXTERNAL_ID, "ext")
        val second = store.snapshot()

        assertEquals(1, first.size)
        assertEquals(2, second.size)
    }

    @Test
    fun `well-known keys carry stable raw values`() {
        // Renaming these strings would orphan every installed user's anonymousId.
        // Lock the contract.
        assertEquals("anonymous_id", PyrxStorageKey.ANONYMOUS_ID.rawValue)
        assertEquals("external_id", PyrxStorageKey.EXTERNAL_ID.rawValue)
        assertEquals("device_token", PyrxStorageKey.DEVICE_TOKEN.rawValue)
        assertEquals(3, PyrxStorageKey.ALL.size)
    }
}
