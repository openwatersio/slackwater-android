package io.openwaters.slackwater.android.core.timeline

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** The detail host dispatches input on [owner]. Worker completions return to the same serial
 * executor, so the reader sees the selected instant and the data behind it change together.
 */
class ContinuousTimeline(
    private val controller: TimelineController,
    private val chunks: TimelineChunks,
    opening: TimelinePublication,
    private val owner: Executor,
) : AutoCloseable {
    init {
        require(controller.mode == TimelineMode.CONTINUOUS)
        require(opening.window == controller.state.coverage && opening === chunks.published) {
            "Initialize the controller with the store’s opening publication"
        }
    }

    var content: TimelinePublication = opening
        private set
    val state: TimelineState get() = controller.state
    private var closed = false
    private var staged: TimelinePublication? = null

    fun dispatch(action: TimelineAction): CompletableFuture<TimelineState> {
        check(!closed)
        require(when (action) {
            is TimelineAction.RequestCoverage, is TimelineAction.RequestFailed, is TimelineAction.OnlineBlock,
            is TimelineAction.Publish -> false
            is TimelineAction.Request -> action.coverage == null
            is TimelineAction.Now -> action.coverage == null
            is TimelineAction.Foreground -> action.coverage == null
            is TimelineAction.PickDay -> action.coverage == null
            else -> true
        }) { "The continuous store supplies prediction coverage" }
        val previous = state
        val next = controller.dispatch(action)
        if (next.generation != previous.generation) {
            chunks.cancelPending(explicitOnly = true)
            staged = null
        }
        if (previous.now != next.now) {
            chunks.setNow(next.now)
            chunks.published?.let {
                content = content.copy(today = it.today)
                staged = staged?.copy(today = it.today)
            }
        }
        commitStaged()
        if (next.awaitingCoverage && next.viewport > 0.0 &&
            (next.generation != previous.generation || !previous.awaitingCoverage || previous.viewport == 0.0)) {
            val generation = next.generation
            val target = requireNotNull(controller.pendingTarget)
            return chunks.jump(target, next.viewport).handleAsync({ result, failure ->
                if (!closed && generation == state.generation && state.awaitingCoverage) {
                    if (result == null || failure != null) {
                        controller.dispatch(TimelineAction.RequestFailed(generation))
                        if (failure != null) throw failure
                    } else {
                        staged = result.copy(today = content.today)
                        controller.dispatch(TimelineAction.RequestCoverage(generation, result.window))
                        commitStaged()
                    }
                }
                state
            }, owner)
        }
        return CompletableFuture.completedFuture(next)
    }

    override fun close() {
        if (closed) return
        closed = true
        staged = null
        controller.dispatch(TimelineAction.Stop)
        chunks.close()
    }

    private fun commitStaged() {
        val ready = staged ?: return
        if (state.pendingGeneration == null && state.coverage == ready.window) {
            content = ready
            staged = null
        }
    }

    /** Request adjacent weeks as the selection moves and again when the strip goes quiet. */
    fun refresh(): CompletableFuture<TimelineState> {
        check(!closed)
        if (state.pendingGeneration != null) return CompletableFuture.completedFuture(state)
        return chunks.update(state.selected, quiet = !state.busy, displayed = content.window).thenApplyAsync({ result ->
            if (!closed && result != null && state.pendingGeneration == null &&
                chunks.published?.window == result.window && result.window.clamp(state.selected, state.viewport) == state.selected) {
                val next = controller.dispatch(TimelineAction.Publish(result.window))
                if (next.coverage == result.window) content = result.copy(today = content.today)
            }
            state
        }, owner)
    }
}
