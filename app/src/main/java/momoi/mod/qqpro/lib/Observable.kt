package momoi.mod.qqpro.lib

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A minimal observable value.
 *
 * Two properties matter for this app's usage:
 *
 *  1. [observeOnce] must not leak. The observer queue used to be swept on *every* [observe] call
 *     (`removeAll { it.block == null }`), which is O(queue) each time and only ever ran when
 *     somebody subscribed — so a long-running chat that called [observeOnce] in a loop (paging
 *     older history, chat search, jump-to-message) both grew the queue and paid a full sweep per
 *     subscription. Cancellation now unlinks the observer immediately, so the queue length is
 *     "live waiters" at all times and no sweep is needed on the hot path.
 *
 *  2. [update] must not let one observer's exception starve the rest, and must tolerate observers
 *     cancelling themselves while it iterates (which [observeOnce] always does).
 */
class Observable<T>(value: T) {
    var value = value
        private set
    private val observerList = ConcurrentLinkedQueue<Observer>()

    inner class Observer(var block: (Observer.(T) -> Unit)?) {
        fun cancel() {
            block = null
            // Unlink eagerly instead of waiting for the next observe()'s sweep.
            observerList.remove(this)
        }
    }

    fun observe(block: Observer.(T) -> Unit) {
        // Sweep only occasionally. The previous version posted a removeAll on EVERY subscription,
        // which races with the dispatch loop and (more importantly) allocated a main-looper message
        // per observeOnce — the paging search calls observeOnce once per page request. cancel()
        // already unlinks, so this is only a safety net for observers cancelled off-thread.
        if (observerList.size > SWEEP_THRESHOLD) {
            observerList.removeAll { it.block == null }
        }
        observerList.add(Observer(block))
    }

    inline fun observeOnce(crossinline block: Observer.(T)->Unit) {
        observe {
            block(it)
            cancel()
        }
    }

    fun update(value: T) {
        this.value = value
        if (observerList.isEmpty()) return
        observerList.forEach {
            val b = it.block ?: return@forEach
            try {
                b.invoke(it, value)
            } catch (e: Throwable) {
                // One bad waiter must not abort delivery to the rest (and must not propagate into
                // the caller's render path).
                momoi.mod.qqpro.util.Utils.log("Observable: observer threw $e")
            }
        }
    }

    private companion object {
        const val SWEEP_THRESHOLD = 32
    }
}
