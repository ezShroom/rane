package me.shro.rane.core

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import java.util.PriorityQueue

/** Main-thread task scheduling in server ticks. */
object Scheduler {
    class Task internal constructor(val due: Long, val seq: Long, val action: () -> Unit) {
        var cancelled = false
            private set

        fun cancel() {
            cancelled = true
        }
    }

    private val queue = PriorityQueue<Task>(compareBy<Task> { it.due }.thenBy { it.seq })
    private var seq = 0L
    var tick = 0L
        private set

    init {
        ServerTickEvents.END_SERVER_TICK.register {
            tick++
            while (true) {
                val t = queue.peek() ?: break
                if (t.due > tick) break
                queue.poll()
                if (!t.cancelled) runCatching(t.action).onFailure { e -> org.slf4j.LoggerFactory.getLogger("rane").error("Scheduled task failed", e) }
            }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { queue.clear() }
    }

    fun later(ticks: Long, action: () -> Unit): Task = Task(tick + maxOf(1, ticks), seq++, action).also { queue += it }

    fun nextTick(action: () -> Unit): Task = later(1, action)

    /** Repeats every [period] ticks until the action returns false or the task is cancelled. */
    fun repeat(period: Long, delay: Long = period, action: () -> Boolean): Task {
        lateinit var task: Task
        fun schedule(d: Long) {
            task = later(d) {
                if (action()) schedule(period)
            }
        }
        schedule(delay)
        return task
    }
}

fun msToTicks(ms: Long): Long = maxOf(1, ms / 50)
