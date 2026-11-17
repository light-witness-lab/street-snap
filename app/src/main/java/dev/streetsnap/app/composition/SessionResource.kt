package dev.streetsnap.app.composition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Builds a blocking native resource off Main and transfers it to a still-current session.
 * Keep ownership outside withContext: cancellation can discard its return value after the
 * factory has already allocated the resource. Callers own a non-null return immediately.
 */
internal suspend fun <T : AutoCloseable> createSessionResource(
    isCurrent: () -> Boolean,
    create: () -> T,
): T? {
    var pending: T? = null
    try {
        withContext(Dispatchers.IO) { pending = create() }
        if (!isCurrent()) return null
        return pending.also { pending = null }
    } finally {
        pending?.let { resource ->
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { resource.close() }
            }
        }
    }
}
