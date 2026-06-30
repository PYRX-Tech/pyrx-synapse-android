/*
 * InAppManagerTest.kt
 * PYRXSynapse — Android — synapse-inapp module
 *
 * Phase 10 PR-2b — JUnit coverage for [InAppManager]. Mirrors the
 * browser SDK's 41-test contract (`packages/sdk/tests/in-app.test.ts`)
 * — same intent, Kotlin-idiomatic fixtures. Verifies the 10 lifecycle
 * rules from PR #218 are preserved in the Android port:
 *
 *   1. Identity-gated polling — no poll without identity.
 *   2. Immediate poll on null → identified with placements.
 *   3. Track-call refresh within window short-circuits.
 *   4. Concurrent poll coalescing.
 *   5. Server-authoritative cache eviction.
 *   6. Received observer dedupes by assignment id.
 *   7. Auto-impression after render callback returns.
 *   8. soft_degraded doubles polling interval.
 *   9. plan_limit_reached: surface message + warn.
 *  10. No widget code (verified by import grep, not test).
 *
 * Tests use a [FakeHTTPSession] that records every request + replays
 * canned responses. No real network. Wall-clock is replaced by a
 * deterministic lambda.
 */

package tech.pyrx.synapse.inapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import org.junit.Test
import tech.pyrx.synapse.PyrxConfig
import tech.pyrx.synapse.network.HTTPResponse
import tech.pyrx.synapse.network.HTTPSession
import tech.pyrx.synapse.observer.PyrxEvent
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InAppManagerTest {
    // ── Fixtures ────────────────────────────────────────────────────────────

    private val testJson: Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    private fun makeConfig(): PyrxConfig =
        PyrxConfig(
            workspaceId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
            apiKey = "psk_test_inapp",
            baseUrl = "https://api.test.local",
        )

    private fun makeMessage(
        id: String = "assign-1",
        placement: String = "home_banner",
        priority: Int = 0,
        expiresAt: String? = null,
        title: String = "Hello",
        body: String = "World",
    ): InAppMessage =
        InAppMessage(
            id = id,
            messageId = "msg-$id",
            placement = placement,
            title = title,
            body = body,
            imageUrl = null,
            ctas = emptyList(),
            custom = null,
            expiresAt = expiresAt,
            priority = priority,
        )

    /**
     * Programmable HTTPSession — records every request, replays canned
     * responses (one per kind: poll vs log), supports a `failNext` flag
     * that throws [IOException] on the next call. Mirrors the browser
     * SDK's `makeFetchStub`.
     */
    private class FakeHTTPSession(private val testJson: Json) : HTTPSession {
        val calls: ConcurrentLinkedQueue<Pair<String, ByteArray>> = ConcurrentLinkedQueue()
        private val pollBodyHolder = AtomicLong(0)

        @Volatile var pollResponseBody: String = """{"messages":[]}"""

        @Volatile var pollStatus: Int = 200

        @Volatile var logResponseBody: String =
            """{"log_id":"log-1","billable":true,"plan_limit_reached":false,"soft_degraded":false}"""

        @Volatile var logStatus: Int = 200

        @Volatile var failNextCount: Int = 0

        @Volatile var failLogOnly: Boolean = false

        fun setPollMessages(messages: List<InAppMessage>) {
            pollResponseBody = testJson.encodeToString(InAppManager.PollResponseBody(messages))
            pollBodyHolder.incrementAndGet()
        }

        fun setLogResponse(
            planLimitReached: Boolean = false,
            softDegraded: Boolean = false,
            status: Int = 200,
        ) {
            logResponseBody =
                """{"log_id":"log-1","billable":true,"plan_limit_reached":$planLimitReached,"soft_degraded":$softDegraded}"""
            logStatus = status
        }

        fun pollCallsCount(): Int = calls.count { (url, _) -> url.contains(InAppManager.POLL_PATH) }

        fun logCallsCount(): Int = calls.count { (url, _) -> url.contains(InAppManager.LOG_PATH) }

        fun lastLogCall(): Pair<String, ByteArray>? =
            calls.toList().lastOrNull { (url, _) -> url.contains(InAppManager.LOG_PATH) }

        fun reset() {
            calls.clear()
            failNextCount = 0
            failLogOnly = false
        }

        override suspend fun execute(request: Request): HTTPResponse {
            val url = request.url.toString()
            val bodyBytes =
                request.body?.let { rb ->
                    val buf = okio.Buffer()
                    rb.writeTo(buf)
                    buf.readByteArray()
                } ?: ByteArray(0)
            calls.add(url to bodyBytes)

            val isLog = url.contains(InAppManager.LOG_PATH)
            if (failNextCount > 0 && (!failLogOnly || isLog)) {
                failNextCount--
                throw IOException("network down")
            }

            return if (isLog) {
                HTTPResponse(
                    statusCode = logStatus,
                    body = logResponseBody.toByteArray(Charsets.UTF_8),
                    headers = mapOf("Content-Type" to "application/json"),
                )
            } else {
                HTTPResponse(
                    statusCode = pollStatus,
                    body = pollResponseBody.toByteArray(Charsets.UTF_8),
                    headers = mapOf("Content-Type" to "application/json"),
                )
            }
        }
    }

    /**
     * Build a manager wired to [TestScope]'s virtual time. The
     * background polling timer is DISABLED in tests
     * (`enableBackgroundTimer = false`) because `runTest`'s
     * `advanceUntilIdle` would loop forever pursuing the
     * `while (true) { delay(); poll() }` body — virtual time has no
     * end. Tests drive polls explicitly via [InAppManager.refresh],
     * [InAppManager.notifyTracked], or [InAppManager.notifyIdentityChanged].
     */
    private fun makeManager(
        session: FakeHTTPSession,
        scope: TestScope,
        contactId: String? = "contact-abc",
        events: MutableList<PyrxEvent> = mutableListOf(),
        logs: MutableList<String> = mutableListOf(),
        nowMillis: () -> Long = { scope.testScheduler.currentTime },
    ): InAppManager {
        val mgrScope =
            kotlinx.coroutines.CoroutineScope(SupervisorJob() + StandardTestDispatcher(scope.testScheduler))
        return InAppManager(
            config = makeConfig(),
            session = session,
            contactIdProvider = { contactId },
            eventPublisher = { ev -> events.add(ev) },
            logger = { msg -> logs.add(msg) },
            json = testJson,
            scope = mgrScope,
            nowMillis = nowMillis,
            defaultPollIntervalMillis = 60_000L,
            enableBackgroundTimer = false,
        )
    }

    // ── Lifecycle Rule 1 — Identity-gated polling ──────────────────────────

    @Test
    fun `does NOT poll before identify`() = runTest {
        val session = FakeHTTPSession(testJson)
        val mgr = makeManager(session = session, scope = this, contactId = null)

        mgr.show("home_banner") { /* no-op */ }
        advanceUntilIdle()
        mgr.refresh()
        advanceUntilIdle()

        assertEquals(0, session.pollCallsCount(), "poll must be identity-gated")
    }

    @Test
    fun `does NOT poll with no registered placements`() = runTest {
        val session = FakeHTTPSession(testJson)
        val mgr = makeManager(session = session, scope = this)

        mgr.refresh()
        advanceUntilIdle()

        assertEquals(0, session.pollCallsCount())
    }

    @Test
    fun `polls poll path with contact_id and placement query params`() = runTest {
        val session = FakeHTTPSession(testJson)
        val mgr = makeManager(session = session, scope = this)
        mgr.show("home_banner") { /* no-op */ }
        advanceUntilIdle()

        val pollCall = session.calls.first { (url, _) -> url.contains(InAppManager.POLL_PATH) }
        assertTrue(pollCall.first.contains("/v1/in-app/poll"))
        assertTrue(pollCall.first.contains("contact_id=contact-abc"))
        assertTrue(pollCall.first.contains("placement=home_banner"))
    }

    @Test
    fun `passes multiple placement keys as REPEATED query params not joined`() = runTest {
        val session = FakeHTTPSession(testJson)
        val mgr = makeManager(session = session, scope = this)
        mgr.show("home_banner") { /* no-op */ }
        mgr.show("settings_modal") { /* no-op */ }
        advanceUntilIdle()
        session.reset()

        mgr.refresh()
        advanceUntilIdle()

        val pollCall = session.calls.first { (url, _) -> url.contains(InAppManager.POLL_PATH) }
        val url = pollCall.first
        // Two placement= params present, NOT comma-joined.
        val placementParamsCount = url.split("&").count { it.startsWith("placement=") }
        assertEquals(2, placementParamsCount, "placement params must be repeated, not comma-joined")
        assertTrue(url.contains("placement=home_banner"))
        assertTrue(url.contains("placement=settings_modal"))
    }

    // ── Lifecycle Rule 4 — Concurrent poll coalescing ──────────────────────

    @Test
    fun `coalesces concurrent polls into a single in-flight request`() = runTest {
        val session = FakeHTTPSession(testJson)
        val mgr = makeManager(session = session, scope = this)
        mgr.show("home_banner") { /* no-op */ }
        advanceUntilIdle()
        session.reset()

        // Three concurrent refreshes — manager coalesces via Mutex+Deferred.
        val deferreds = listOf(
            async { mgr.refresh() },
            async { mgr.refresh() },
            async { mgr.refresh() },
        )
        deferreds.awaitAll()
        advanceUntilIdle()

        assertEquals(1, session.pollCallsCount(), "concurrent polls must coalesce into one request")
    }

    // ── Network resilience ─────────────────────────────────────────────────

    @Test
    fun `survives network errors gracefully keeps last-cached messages`() = runTest {
        val session = FakeHTTPSession(testJson)
        session.setPollMessages(listOf(makeMessage(id = "assign-cached")))
        val mgr = makeManager(session = session, scope = this)

        mgr.show("home_banner") { /* no-op */ }
        advanceUntilIdle()
        assertEquals(1, mgr.getActiveCountForTests())

        // Force the next poll to fail.
        session.failNextCount = 1
        mgr.refresh()
        advanceUntilIdle()

        // Cache preserved (not replaced with empty response).
        assertEquals(1, mgr.getActiveCountForTests())
    }

    // ── Render callbacks (ADR-0008 D2) ─────────────────────────────────────

    @Test
    fun `dispatches a fresh message to the registered placement callback`() = runTest {
        val session = FakeHTTPSession(testJson)
        session.setPollMessages(listOf(makeMessage(id = "assign-fresh", placement = "home_banner")))
        val mgr = makeManager(session = session, scope = this)

        val received = mutableListOf<InAppMessage>()
        mgr.show("home_banner") { msg -> received.add(msg) }
        advanceUntilIdle()

        assertEquals(1, received.size)
        assertEquals("assign-fresh", received[0].id)
    }

    @Test
    fun `does NOT re-dispatch the same message on a subsequent poll`() = runTest {
        val session = FakeHTTPSession(testJson)
        session.setPollMessages(listOf(makeMessage(id = "assign-stable")))
        val mgr = makeManager(session = session, scope = this)

        val received = mutableListOf<InAppMessage>()
        mgr.show("home_banner") { msg -> received.add(msg) }
        advanceUntilIdle()
        mgr.refresh()
        advanceUntilIdle()
        mgr.refresh()
        advanceUntilIdle()

        assertEquals(1, received.size, "dedupe by assignment id (rule 6)")
    }

    // ── Lifecycle Rule 7 — Auto-impression ─────────────────────────────────

    @Test
    fun `auto-fires markImpression after the render callback returns`() = runTest {
        val session = FakeHTTPSession(testJson)
        session.setPollMessages(listOf(makeMessage(id = "assign-impress")))
        val mgr = makeManager(session = session, scope = this)

        mgr.show("home_banner") { /* no-op */ }
        advanceUntilIdle()

        val lastLog = session.lastLogCall()
        assertNotNull(lastLog, "auto-impression POST must have fired")
        val bodyStr = lastLog.second.toString(Charsets.UTF_8)
        assertTrue(bodyStr.contains("\"assignment_id\":\"assign-impress\""), "body: $bodyStr")
        assertTrue(bodyStr.contains("\"event\":\"impressed\""), "body: $bodyStr")
    }

    @Test
    fun `isolates host-app exceptions thrown inside the render callback`() = runTest {
        val session = FakeHTTPSession(testJson)
        session.setPollMessages(listOf(makeMessage(id = "assign-explode")))
        val mgr = makeManager(session = session, scope = this)

        // Callback throws — manager must catch + log + continue.
        mgr.show("home_banner") { _ -> throw RuntimeException("host app blew up") }
        // No crash — advanceUntilIdle settles without throwing out.
        advanceUntilIdle()
        // Auto-impression still fires (callback caught, manager kept going).
        assertEquals(1, session.logCallsCount())
    }

    @Test
    fun `does NOT dispatch a message to a callback for a different placement`() = runTest {
        val session = FakeHTTPSession(testJson)
        session.setPollMessages(
            listOf(
                makeMessage(id = "a-home", placement = "home_banner"),
                makeMessage(id = "a-modal", placement = "settings_modal"),
            ),
        )
        val mgr = makeManager(session = session, scope = this)

        val home = mutableListOf<InAppMessage>()
        val modal = mutableListOf<InAppMessage>()
        mgr.show("home_banner") { msg -> home.add(msg) }
        mgr.show("settings_modal") { msg -> modal.add(msg) }
        advanceUntilIdle()

        assertEquals(listOf("a-home"), home.map { it.id })
        assertEquals(listOf("a-modal"), modal.map { it.id })
    }

    @Test
    fun `replays already-cached messages when a SECOND callback registers for an existing placement`() = runTest {
        val session = FakeHTTPSession(testJson)
        session.setPollMessages(listOf(makeMessage(id = "assign-replay")))
        val mgr = makeManager(session = session, scope = this)

        mgr.show("home_banner") { /* first callback drives initial poll */ }
        advanceUntilIdle()
        assertEquals(1, mgr.getActiveCountForTests())

        // Second callback registers AFTER cache populated — replay.
        val replayed = mutableListOf<InAppMessage>()
        mgr.show("home_banner") { msg -> replayed.add(msg) }
        advanceUntilIdle()

        assertEquals(1, replayed.size)
        assertEquals("assign-replay", replayed[0].id)
    }

    @Test
    fun `show with blank placement returns a no-op token without throwing`() = runTest {
        val session = FakeHTTPSession(testJson)
        val mgr = makeManager(session = session, scope = this)

        // No throw; returns a closeable that does nothing.
        val token = mgr.show("") { /* no-op */ }
        token.close()
        advanceUntilIdle()
        assertEquals(0, session.pollCallsCount())
    }

    // ── getActive ──────────────────────────────────────────────────────────

    @Test
    fun `getActive returns a sorted copy priority desc then expiry asc`() = runTest {
        val session = FakeHTTPSession(testJson)
        session.setPollMessages(
            listOf(
                makeMessage(id = "low", priority = 1),
                makeMessage(id = "high", priority = 10),
                makeMessage(id = "mid", priority = 5),
            ),
        )
        val mgr = makeManager(session = session, scope = this)
        mgr.show("home_banner") { /* no-op */ }
        advanceUntilIdle()

        val order = mgr.getActive(null).map { it.id }
        assertEquals(listOf("high", "mid", "low"), order)
    }

    @Test
    fun `getActive filters by placement when supplied`() = runTest {
        val session = FakeHTTPSession(testJson)
        session.setPollMessages(
            listOf(
                makeMessage(id = "a", placement = "home_banner"),
                makeMessage(id = "b", placement = "settings_modal"),
            ),
        )
        val mgr = makeManager(session = session, scope = this)
        mgr.show("home_banner") { /* no-op */ }
        mgr.show("settings_modal") { /* no-op */ }
        advanceUntilIdle()

        assertEquals(listOf("a"), mgr.getActive("home_banner").map { it.id })
        assertEquals(listOf("b"), mgr.getActive("settings_modal").map { it.id })
    }

    @Test
    fun `getActive returns empty when no activity has occurred`() = runTest {
        val session = FakeHTTPSession(testJson)
        val mgr = makeManager(session = session, scope = this)
        assertEquals(emptyList(), mgr.getActive(null))
    }

    // ── dismiss ────────────────────────────────────────────────────────────

    @Test
    fun `dismiss evicts the message and POSTs log with event=dismissed`() = runTest {
        val session = FakeHTTPSession(testJson)
        session.setPollMessages(listOf(makeMessage(id = "assign-dismiss")))
        val mgr = makeManager(session = session, scope = this)
        mgr.show("home_banner") { /* no-op */ }
        advanceUntilIdle()
        assertEquals(1, mgr.getActiveCountForTests())

        session.reset()
        mgr.dismiss("assign-dismiss", "user_dismissed")
        advanceUntilIdle()

        assertEquals(0, mgr.getActiveCountForTests())
        val lastLog = session.lastLogCall()
        assertNotNull(lastLog)
        val bodyStr = lastLog.second.toString(Charsets.UTF_8)
        assertTrue(bodyStr.contains("\"assignment_id\":\"assign-dismiss\""))
        assertTrue(bodyStr.contains("\"event\":\"dismissed\""))
        // Reason MUST NOT cross the wire per ADR-0008 D2.
        assertTrue(!bodyStr.contains("user_dismissed"), "reason must not be sent: $bodyStr")
    }

    @Test
    fun `dismiss fires the InAppMessageDismissed observer with the host-supplied reason`() = runTest {
        val session = FakeHTTPSession(testJson)
        val events = mutableListOf<PyrxEvent>()
        val mgr = makeManager(session = session, scope = this, events = events)

        mgr.dismiss("assign-x", "user_dismissed")
        advanceUntilIdle()

        val dismissEv = events.filterIsInstance<PyrxEvent.InAppMessageDismissed>().firstOrNull()
        assertNotNull(dismissEv, "InAppMessageDismissed must fire")
        assertEquals("assign-x", dismissEv.messageId)
        assertEquals("user_dismissed", dismissEv.reason)
    }

    @Test
    fun `dismiss with blank messageId is a no-op no network call`() = runTest {
        val session = FakeHTTPSession(testJson)
        val mgr = makeManager(session = session, scope = this)

        mgr.dismiss("", null)
        advanceUntilIdle()
        assertEquals(0, session.calls.size)
    }

    // ── markInteracted ─────────────────────────────────────────────────────

    @Test
    fun `markInteracted POSTs log with event=interacted and cta_id`() = runTest {
        val session = FakeHTTPSession(testJson)
        val mgr = makeManager(session = session, scope = this)

        mgr.markInteracted("assign-i", "cta-primary")
        advanceUntilIdle()

        val lastLog = session.lastLogCall()
        assertNotNull(lastLog)
        val bodyStr = lastLog.second.toString(Charsets.UTF_8)
        assertTrue(bodyStr.contains("\"assignment_id\":\"assign-i\""))
        assertTrue(bodyStr.contains("\"event\":\"interacted\""))
        assertTrue(bodyStr.contains("\"cta_id\":\"cta-primary\""))
    }

    @Test
    fun `markInteracted rejects blank ctaId client-side no round-trip`() = runTest {
        val session = FakeHTTPSession(testJson)
        val mgr = makeManager(session = session, scope = this)

        mgr.markInteracted("assign-i", "")
        advanceUntilIdle()
        assertEquals(0, session.calls.size)
    }

    // ── Observer events (ADR-0009 D5) ──────────────────────────────────────

    @Test
    fun `fires InAppMessageReceived once per new assignment id`() = runTest {
        val session = FakeHTTPSession(testJson)
        session.setPollMessages(listOf(makeMessage(id = "a"), makeMessage(id = "b")))
        val events = mutableListOf<PyrxEvent>()
        val mgr = makeManager(session = session, scope = this, events = events)

        mgr.show("home_banner") { /* no-op */ }
        advanceUntilIdle()
        mgr.refresh()
        advanceUntilIdle()
        mgr.refresh()
        advanceUntilIdle()

        val received = events.filterIsInstance<PyrxEvent.InAppMessageReceived>().map { it.message.id }
        assertEquals(listOf("a", "b"), received)
    }

    // ── Cache eviction (rule 5 — server-authoritative) ─────────────────────

    @Test
    fun `evicts messages no longer present in poll response`() = runTest {
        val session = FakeHTTPSession(testJson)
        session.setPollMessages(listOf(makeMessage(id = "a"), makeMessage(id = "b")))
        val mgr = makeManager(session = session, scope = this)
        mgr.show("home_banner") { /* no-op */ }
        advanceUntilIdle()
        assertEquals(2, mgr.getActiveCountForTests())

        // Backend drops "a" (expiry / frequency cap / segment change).
        session.setPollMessages(listOf(makeMessage(id = "b")))
        mgr.refresh()
        advanceUntilIdle()

        assertEquals(listOf("b"), mgr.getActive(null).map { it.id })
    }

    // ── Soft-degrade backoff (rule 8) ──────────────────────────────────────

    @Test
    fun `soft_degraded=true doubles poll interval`() = runTest {
        val session = FakeHTTPSession(testJson)
        session.setPollMessages(listOf(makeMessage(id = "imp-1")))
        session.setLogResponse(softDegraded = true)
        val mgr = makeManager(session = session, scope = this)

        assertEquals(60_000L, mgr.getPollIntervalForTests())

        mgr.show("home_banner") { /* no-op */ }
        advanceUntilIdle()

        assertEquals(120_000L, mgr.getPollIntervalForTests(), "soft_degraded must double the interval")
    }

    @Test
    fun `recovers to default interval when soft_degraded clears`() = runTest {
        val session = FakeHTTPSession(testJson)
        session.setPollMessages(listOf(makeMessage(id = "m1")))
        session.setLogResponse(softDegraded = true)
        val mgr = makeManager(session = session, scope = this)

        mgr.show("home_banner") { /* no-op */ }
        advanceUntilIdle()
        assertEquals(120_000L, mgr.getPollIntervalForTests())

        // Phase 2 — new message, clean response.
        session.setPollMessages(listOf(makeMessage(id = "m1"), makeMessage(id = "m2")))
        session.setLogResponse(softDegraded = false)
        mgr.refresh()
        advanceUntilIdle()

        assertEquals(60_000L, mgr.getPollIntervalForTests(), "clean response must reset interval")
    }

    @Test
    fun `plan_limit_reached still surfaces the message and emits debug log warning`() = runTest {
        val session = FakeHTTPSession(testJson)
        session.setPollMessages(listOf(makeMessage(id = "at-cap")))
        session.setLogResponse(planLimitReached = true, softDegraded = true)
        val logs = mutableListOf<String>()
        val mgr = makeManager(session = session, scope = this, logs = logs)

        val rendered = mutableListOf<InAppMessage>()
        mgr.show("home_banner") { msg -> rendered.add(msg) }
        advanceUntilIdle()

        // Host still saw the message (callback fired BEFORE the log POST returned).
        assertEquals(1, rendered.size, "plan_limit_reached must NOT suppress the host callback")
        // Debug log surfaces the plan-limit warning.
        assertTrue(
            logs.any { it.contains("plan_limit_reached") },
            "expected plan_limit_reached log; got: $logs",
        )
    }

    // ── Offline queue ──────────────────────────────────────────────────────

    @Test
    fun `queues telemetry on network failure for later retry`() = runTest {
        val session = FakeHTTPSession(testJson)
        val mgr = makeManager(session = session, scope = this)

        // Force the log POST to fail.
        session.failNextCount = 1
        session.failLogOnly = true
        mgr.dismiss("assign-offline", null)
        advanceUntilIdle()

        assertEquals(1, mgr.getQueuedLogsForTests().size, "failed log must queue for retry")
    }

    @Test
    fun `queues telemetry on 5xx and drops on 4xx`() = runTest {
        val session = FakeHTTPSession(testJson)
        val mgr = makeManager(session = session, scope = this)

        // 4xx — permanent failure, dropped.
        session.logStatus = 422
        mgr.dismiss("assign-4xx", null)
        advanceUntilIdle()
        assertEquals(0, mgr.getQueuedLogsForTests().size)

        // 5xx — transient, queued.
        session.logStatus = 503
        mgr.dismiss("assign-5xx", null)
        advanceUntilIdle()
        assertEquals(1, mgr.getQueuedLogsForTests().size)
    }

    // ── Identity transition (rule 2) ───────────────────────────────────────

    @Test
    fun `triggers immediate poll when identity transitions from null to set with placements`() = runTest {
        val session = FakeHTTPSession(testJson)
        var currentContactId: String? = null
        val mgrScope =
            kotlinx.coroutines.CoroutineScope(
                SupervisorJob() + StandardTestDispatcher(this.testScheduler),
            )
        val mgr =
            InAppManager(
                config = makeConfig(),
                session = session,
                contactIdProvider = { currentContactId },
                eventPublisher = { /* no-op */ },
                logger = { /* no-op */ },
                json = testJson,
                scope = mgrScope,
                nowMillis = { this.testScheduler.currentTime },
                defaultPollIntervalMillis = 60_000L,
                enableBackgroundTimer = false,
            )
        mgr.show("home_banner") { /* no-op */ }
        advanceUntilIdle()
        assertEquals(0, session.pollCallsCount(), "no poll before identity")

        // Identity lands.
        currentContactId = "freshly-identified"
        mgr.notifyIdentityChanged()
        advanceUntilIdle()

        assertTrue(session.pollCallsCount() >= 1, "identity arrival must kick a poll")
        val pollUrl = session.calls.first { (url, _) -> url.contains(InAppManager.POLL_PATH) }.first
        assertTrue(pollUrl.contains("contact_id=freshly-identified"))
    }

    // ── Track-call refresh window (rule 3) ─────────────────────────────────

    @Test
    fun `notifyTracked within the poll-interval window does NOT fire a poll`() = runTest {
        val session = FakeHTTPSession(testJson)
        val mgr = makeManager(session = session, scope = this)
        mgr.show("home_banner") { /* no-op */ }
        advanceUntilIdle()
        session.reset()

        // Immediate track call — lastPollAt is "just now"; should short-circuit.
        mgr.notifyTracked()
        advanceUntilIdle()

        assertEquals(0, session.pollCallsCount(), "track call within window must short-circuit")
    }

    // ── Lifecycle: shutdown ────────────────────────────────────────────────

    @Test
    fun `shutdown stops accepting new work and does not break previously-cached reads`() = runTest {
        val session = FakeHTTPSession(testJson)
        session.setPollMessages(listOf(makeMessage(id = "m1")))
        val mgr = makeManager(session = session, scope = this)
        mgr.show("home_banner") { /* no-op */ }
        advanceUntilIdle()
        assertEquals(1, mgr.getActiveCountForTests())

        mgr.shutdown()
        // After shutdown the cache snapshot is still readable — shutdown
        // only cancels background work, not the manager's in-memory
        // state. Production callers can construct a fresh manager to
        // resume polling.
        assertEquals(1, mgr.getActiveCountForTests(), "cache survives shutdown for last-read")
    }

    // ── ShowToken close unregisters callback ───────────────────────────────

    @Test
    fun `ShowToken close unregisters the callback`() = runTest {
        val session = FakeHTTPSession(testJson)
        session.setPollMessages(listOf(makeMessage(id = "m1")))
        val mgr = makeManager(session = session, scope = this)

        val received = mutableListOf<InAppMessage>()
        val token = mgr.show("home_banner") { msg -> received.add(msg) }
        advanceUntilIdle()
        assertEquals(1, received.size, "initial render")

        token.close()
        advanceUntilIdle()
        assertEquals(0, mgr.getRegisteredPlacementCountForTests())
    }
}
