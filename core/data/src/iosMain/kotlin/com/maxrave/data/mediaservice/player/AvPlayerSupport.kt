package com.maxrave.data.mediaservice.player

import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.PlayerConstants
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext
import kotlin.native.concurrent.ThreadLocal

/**
 * Which [SerialDispatcher] (by owner) the running thread is executing a task for, or null.
 *
 * Thread-local, so "am I on the adapter's serial context?" can be answered from a plain,
 * non-suspending function — the public getters and the synchronous mutators need that answer to
 * decide between reading live state and reading the published snapshot, and between running inline
 * and hopping over.
 */
@ThreadLocal
private var serialOwner: Any? = null

/**
 * The adapter's serial context: one task at a time on [inner] (a `limitedParallelism(1)` view), with
 * two additions the adapter relies on.
 *
 *  - While a task runs, the thread is marked as belonging to [owner] ([isCurrent]).
 *  - After every task — including each resumption of a suspended coroutine — [afterTask] runs, still
 *    marked. The adapter uses it to publish an immutable queue snapshot for other threads and to push
 *    lock-screen state to the engine, so no mutation site can forget to do either.
 */
internal class SerialDispatcher(
    private val owner: Any,
    private val inner: CoroutineDispatcher,
    private val afterTask: () -> Unit,
) : CoroutineDispatcher() {
    fun isCurrent(): Boolean = serialOwner === owner

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        inner.dispatch(
            context,
            object : Runnable {
                override fun run() {
                    val previous = serialOwner
                    serialOwner = owner
                    try {
                        block.run()
                    } finally {
                        try {
                            afterTask()
                        } catch (e: Throwable) {
                            Logger.e("AvPlayerAdapter", "afterTask failed: ${e.message}", e)
                        }
                        serialOwner = previous
                    }
                }
            },
        )
    }
}

/**
 * A set that holds its members by identity (`===`), not by `equals`.
 *
 * [GenericMediaItem] is a data class, and a queue can hold the same song twice: an equality-based
 * set would pin both copies when only one was put there with Play next. The desktop adapter gets this
 * from `IdentityHashMap`; Kotlin/Native has none, and the set never holds more than a handful of
 * items, so a list scanned with `===` is enough. Confined to the adapter's serial context.
 */
internal class IdentitySet<T : Any> {
    private val items = ArrayList<T>()

    fun add(item: T) {
        if (!contains(item)) items.add(item)
    }

    fun remove(item: T) {
        val iterator = items.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() === item) iterator.remove()
        }
    }

    operator fun contains(item: T): Boolean = items.any { it === item }

    fun clear() = items.clear()
}

/**
 * Queue state plus the navigation rules that read it, exactly as `MpvPlayerAdapter` computes them.
 *
 * Two kinds of instance exist. On the serial context the adapter builds one over its live lists (no
 * copy; nothing else can touch them there). For every other thread it publishes one built over
 * copies after each serial task, so a getter on Main reads a consistent playlist, index and shuffle
 * order instead of racing the serial thread through three mutable lists.
 */
internal class QueueView(
    val playlist: List<GenericMediaItem>,
    val currentIndex: Int,
    val shuffleEnabled: Boolean,
    val shuffleOrder: List<Int>,
    val shuffleIndices: List<Int>,
    val repeatMode: Int,
) {
    val currentItem: GenericMediaItem?
        get() = playlist.getOrNull(currentIndex)

    // Repeat One only loops a track that ends on its own (handleTrackEndInternal);
    // Next and Previous move through the queue as they do with repeat off.
    fun hasNext(): Boolean =
        when (repeatMode) {
            PlayerConstants.REPEAT_MODE_ALL -> true
            else ->
                if (shuffleEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(currentIndex) ?: -1
                    currentShufflePos >= 0 && currentShufflePos < shuffleOrder.lastIndex
                } else {
                    currentIndex < playlist.size - 1
                }
        }

    fun hasPrevious(): Boolean =
        when (repeatMode) {
            PlayerConstants.REPEAT_MODE_ALL -> true
            else ->
                if (shuffleEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(currentIndex) ?: -1
                    currentShufflePos > 0
                } else {
                    currentIndex > 0
                }
        }

    /** The index Next plays after [from]; [from] itself when nothing follows it. [from] defaults to the
     *  current track, and precaching walks it forward to reach the track after next. */
    fun nextIndex(from: Int = currentIndex): Int =
        when (repeatMode) {
            PlayerConstants.REPEAT_MODE_ALL -> {
                if (shuffleEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(from) ?: 0
                    val nextShufflePos = (currentShufflePos + 1) % shuffleOrder.size
                    shuffleOrder.getOrNull(nextShufflePos) ?: from
                } else {
                    if (from < playlist.size - 1) {
                        from + 1
                    } else {
                        0
                    }
                }
            }
            else -> {
                if (shuffleEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(from) ?: 0
                    val nextShufflePos = currentShufflePos + 1
                    if (nextShufflePos < shuffleOrder.size) {
                        shuffleOrder.getOrNull(nextShufflePos) ?: from
                    } else {
                        from
                    }
                } else {
                    (from + 1).coerceAtMost(playlist.size - 1)
                }
            }
        }

    fun previousIndex(): Int =
        when (repeatMode) {
            PlayerConstants.REPEAT_MODE_ALL -> {
                if (shuffleEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(currentIndex) ?: 0
                    val prevShufflePos =
                        if (currentShufflePos > 0) {
                            currentShufflePos - 1
                        } else {
                            shuffleOrder.size - 1
                        }
                    shuffleOrder.getOrNull(prevShufflePos) ?: currentIndex
                } else {
                    if (currentIndex > 0) {
                        currentIndex - 1
                    } else {
                        playlist.size - 1
                    }
                }
            }
            else -> {
                if (shuffleEnabled && shuffleOrder.isNotEmpty()) {
                    val currentShufflePos = shuffleIndices.getOrNull(currentIndex) ?: 0
                    val prevShufflePos = currentShufflePos - 1
                    if (prevShufflePos >= 0) {
                        shuffleOrder.getOrNull(prevShufflePos) ?: currentIndex
                    } else {
                        currentIndex
                    }
                } else {
                    (currentIndex - 1).coerceAtLeast(0)
                }
            }
        }

    /** What getCurrentMediaTimeLine() returns: the shuffled order whenever shuffle is on. */
    fun timeline(): List<GenericMediaItem> =
        if (shuffleEnabled) {
            shuffleOrder.mapNotNull { shuffledIndex -> playlist.getOrNull(shuffledIndex) }
        } else {
            playlist.toList()
        }

    /** What the timeline / shuffle notifications carry: falls back to the playlist when no order exists. */
    fun shuffledList(): List<GenericMediaItem> {
        if (!shuffleEnabled || shuffleOrder.isEmpty()) {
            return playlist.toList()
        }
        return shuffleOrder.mapNotNull { playlist.getOrNull(it) }
    }

    fun unshuffledIndex(shuffledIndex: Int): Int =
        if (shuffleEnabled) {
            shuffleOrder.getOrNull(shuffledIndex) ?: -1
        } else {
            shuffledIndex
        }

    companion object {
        val EMPTY =
            QueueView(
                playlist = emptyList(),
                currentIndex = -1,
                shuffleEnabled = false,
                shuffleOrder = emptyList(),
                shuffleIndices = emptyList(),
                repeatMode = PlayerConstants.REPEAT_MODE_OFF,
            )
    }
}
