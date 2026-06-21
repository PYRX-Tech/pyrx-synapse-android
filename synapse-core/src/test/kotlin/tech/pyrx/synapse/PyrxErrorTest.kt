/*
 * PyrxErrorTest.kt
 * PYRXSynapse — Android
 *
 * Locks the typed error hierarchy. Failure-mode discrimination is a public
 * contract — apps catch on specific subclasses (e.g., `is PyrxError.Network`).
 */

package tech.pyrx.synapse

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PyrxErrorTest {
    @Test
    fun `AlreadyInitialized is a singleton`() {
        // Object identity must be stable so `catch (e: PyrxError.AlreadyInitialized)`
        // works as expected and `===` comparisons hold for sentinel-style checks.
        assertTrue(PyrxError.AlreadyInitialized === PyrxError.AlreadyInitialized)
    }

    @Test
    fun `NotInitialized is a singleton`() {
        assertTrue(PyrxError.NotInitialized === PyrxError.NotInitialized)
    }

    @Test
    fun `InvalidConfig surfaces the reason in the message`() {
        val err = PyrxError.InvalidConfig("apiKey malformed")
        assertEquals("apiKey malformed", err.reason)
        assertTrue(err.message!!.contains("apiKey malformed"))
    }

    @Test
    fun `StorageFailure surfaces the operation name`() {
        val cause = RuntimeException("disk full")
        val err = PyrxError.StorageFailure(operation = "set(anonymous_id)", underlying = cause)
        assertEquals("set(anonymous_id)", err.operation)
        assertEquals(cause, err.cause)
    }

    @Test
    fun `Network wraps a PyrxNetworkError discriminator`() {
        val inner = PyrxNetworkError.HttpStatus(statusCode = 429, body = "rate limited".toByteArray())
        val err = PyrxError.Network(inner)
        assertEquals(inner, err.inner)
        assertNotNull(err.message)
        assertTrue(err.message!!.contains("HTTP 429"))
    }

    @Test
    fun `HttpStatus equality is structural over body bytes`() {
        val a = PyrxNetworkError.HttpStatus(500, "boom".toByteArray())
        val b = PyrxNetworkError.HttpStatus(500, "boom".toByteArray())
        val c = PyrxNetworkError.HttpStatus(500, "other".toByteArray())
        assertEquals(a, b)
        assertTrue(a != c)
    }
}
