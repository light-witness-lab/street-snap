package dev.streetsnap.app.composition

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionResourceTest {
    @Test
    fun cancellingDuringBlockingConstructionClosesTheDiscardedResource() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finishConstruction = CountDownLatch(1)
        val resource = Resource()
        var delivered: Resource? = null
        val loading = launch {
            delivered = createSessionResource(isCurrent = { true }) {
                started.complete(Unit)
                check(finishConstruction.await(5, TimeUnit.SECONDS))
                resource
            }
        }
        try {
            withTimeout(5_000L) { started.await() }
            loading.cancel()
        } finally {
            finishConstruction.countDown()
        }
        withTimeout(5_000L) { loading.join() }

        assertTrue(loading.isCancelled)
        assertNull(delivered)
        assertEquals(1, resource.closes.get())
    }

    @Test
    fun aSupersededSessionClosesItsResourceWithoutTransferringIt() = runBlocking {
        val resource = Resource()
        val delivered = createSessionResource(isCurrent = { false }) { resource }

        assertNull(delivered)
        assertEquals(1, resource.closes.get())
    }

    @Test
    fun aCurrentSessionOwnsTheResourceUntilItExplicitlyClosesIt() = runBlocking {
        val resource = Resource()
        val delivered = createSessionResource(isCurrent = { true }) { resource }

        assertSame(resource, delivered)
        assertEquals(0, resource.closes.get())
        requireNotNull(delivered).close()
        assertEquals(1, resource.closes.get())
    }

    @Test
    fun anExceptionBeforeTransferStillClosesTheResource() = runBlocking {
        val resource = Resource()
        val failure = IllegalStateException("session check failed")
        var caught: Throwable? = null
        try {
            createSessionResource(isCurrent = { throw failure }) { resource }
        } catch (error: IllegalStateException) {
            caught = error
        }

        assertSame(failure, caught)
        assertEquals(1, resource.closes.get())
    }

    private class Resource : AutoCloseable {
        val closes = AtomicInteger()
        override fun close() {
            closes.incrementAndGet()
        }
    }
}
