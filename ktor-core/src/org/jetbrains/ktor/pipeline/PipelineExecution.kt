package org.jetbrains.ktor.pipeline

import java.util.concurrent.*

interface PipelineControl<T> {
    fun finish()
    fun pause()
    fun proceed()
    fun fail(exception: Throwable)

    fun fork(subject: T, pipeline: Pipeline<T>)

}

fun <T> PipelineControl<T>.join(future: CompletionStage<*>) {
    pause()
    future.whenComplete { unit, throwable ->
        if (throwable == null)
            proceed()
        else
            fail(throwable)
    }
}


class PipelineExecution<T>(val subject: T, val blockBuilders: List<PipelineContext<T>.(T) -> Unit>) {

    enum class State {
        Execute, Pause, Finished;

        fun finished(): Boolean = this == Finished
    }

    var state = State.Pause
    private val stack = mutableListOf<PipelineContext<T>>()

    fun fork(subject: T, pipeline: Pipeline<T>) {
        val master = this@PipelineExecution
        val chain: PipelineContext<T>.(T) -> Unit = { subject ->
            onFinish {
                master.proceed()
            }
            onFail {
                master.fail(it)
            }
        }
        val interceptors = listOf(chain) + pipeline.interceptors
        val secondary = PipelineExecution(subject, interceptors)

        val currentMaster = stack.last()
        currentMaster.state = State.Pause
        secondary.proceed()
        if (secondary.state.finished()) {
            // if secondary completed, master it already finished in `chain`
            currentMaster.state = State.Finished
        }
    }

    internal fun proceed() {
        state = State.Execute
        loop@while (stack.size < blockBuilders.size) {
            val index = stack.size
            val builder = blockBuilders[index]
            val context = PipelineContext(this, builder)
            stack.add(context)
            try {
                context.state = State.Execute
                context.function(context, subject)
            } catch(assertion: AssertionError) {
                throw assertion // do not prevent tests from failing
            } catch(exception: Throwable) {
                fail(exception)
                return
            }

            when (context.state) {
                State.Pause -> {
                    state = State.Pause
                    return
                }
                State.Finished -> break@loop
                State.Execute -> continue@loop
            }
        }
        if (state == State.Execute)
            finish()
    }

    private fun finish() {
        for (block in stack.asReversed()) {
            block.exits.forEach { it() }
        }
        state = State.Finished
    }

    internal fun fail(exception: Throwable) {
        for (block in stack.asReversed()) {
            block.failures.forEach { it(exception) }
        }
        state = State.Finished
    }
}