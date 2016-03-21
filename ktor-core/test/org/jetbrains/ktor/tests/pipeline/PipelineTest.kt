package org.jetbrains.ktor.tests.pipeline

import org.jetbrains.ktor.pipeline.*
import org.junit.*
import kotlin.test.*

class PipelineTest {

    @Test
    fun emptyPipeline() {
        val execution = Pipeline<String>().execute("some")
        assertTrue(execution.state.finished())
    }

    @Test
    fun singleActionPipeline() {
        var value = false
        val pipeline = Pipeline<String>()
        pipeline.intercept {
            value = true
            assertEquals("some", subject)
        }
        val execution = pipeline.execute("some")
        assertTrue(value)
        assertTrue(execution.state.finished())
    }

    @Test
    fun singleActionPipelineWithFinish() {
        var value = false
        val pipeline = Pipeline<String>()
        pipeline.intercept {
            onFinish {
                assertTrue(value)
            }
            onFail {
                fail("This pipeline shouldn't fail")
            }
            assertFalse(value)
            value = true
            assertEquals("some", subject)
        }
        val execution = pipeline.execute("some")
        assertTrue(value)
        assertTrue(execution.state.finished())
    }

    @Test
    fun singleActionPipelineWithFail() {
        var failed = false
        val pipeline = Pipeline<String>()
        pipeline.intercept {
            onFinish {
                fail("This pipeline shouldn't finish")
            }
            onFail {
                assertFalse(failed)
                failed = true
            }
            assertEquals("some", subject)
            throw UnsupportedOperationException()
        }
        val execution = pipeline.execute("some")
        assertTrue(failed)
        assertTrue(execution.state.finished())
    }

    @Test
    fun actionFinishOrder() {
        var count = 0
        val pipeline = Pipeline<String>()
        pipeline.intercept {
            onFinish {
                assertEquals(1, count)
                count--
            }
            assertEquals(0, count)
            count++
        }

        pipeline.intercept {
            onFinish {
                assertEquals(2, count)
                count--
            }
            assertEquals(1, count)
            count++
        }
        val execution = pipeline.execute("some")
        assertEquals(0, count)
        assertTrue(execution.state.finished())
    }

    @Test
    fun actionFailOrder() {
        var count = 0
        val pipeline = Pipeline<String>()
        pipeline.intercept {
            onFail {
                assertEquals(1, count)
                count--
            }
            assertEquals(0, count)
            count++
        }

        pipeline.intercept {
            onFail {
                assertEquals(2, count)
                count--
            }
            assertEquals(1, count)
            count++
            throw UnsupportedOperationException()
        }
        val execution = pipeline.execute("some")
        assertEquals(0, count)
        assertTrue(execution.state.finished())
    }

    @Test
    fun pauseResume() {
        var count = 0
        val pipeline = Pipeline<String>()
        pipeline.intercept {
            assertEquals(0, count)
            count++
            pause()
        }

        pipeline.intercept {
            assertEquals(1, count)
            count++
        }

        val execution = pipeline.execute("some")
        assertEquals(PipelineExecution.State.Pause, execution.state)
        execution.proceed()
        assertEquals(2, count)
        assertTrue(execution.state.finished())
    }

    @Test
    fun fork() {
        var count = 0
        var max = 0
        var secondaryOk = false
        val pipeline = Pipeline<String>()
        pipeline.intercept {
            onFail {
                assertEquals(1, count)
                count--
            }
            assertEquals(0, count)
            count++
            max = Math.max(max, count)
        }

        pipeline.intercept {
            val secondary = Pipeline<String>()
            secondary.intercept {
                onFail {
                    fail("This pipeline shouldn't fail")
                }
                assertEquals("another", subject)
                secondaryOk = true
            }
            fork("another", secondary)
        }

        pipeline.intercept {
            onFail {
                assertEquals(2, count)
                count--
            }
            assertEquals(1, count)
            count++
            max = Math.max(max, count)
            throw UnsupportedOperationException()
        }
        val execution = pipeline.execute("some")
        assertTrue(secondaryOk, "Secondary should be run")
        assertEquals(0, count)
        assertEquals(2, max)
        assertEquals(PipelineExecution.State.Finished, execution.state)
    }

    @Test
    fun forkAndFail() {
        var count = 0
        var max = 0
        var secondaryOk = false
        val pipeline = Pipeline<String>()
        pipeline.intercept {
            onFail {
                assertEquals(1, count)
                count--
            }
            onFinish {
                fail("This pipeline shouldn't finish")
            }
            assertEquals(0, count)
            count++
            max = Math.max(max, count)
        }

        pipeline.intercept {
            val secondary = Pipeline<String>()
            secondary.intercept {
                onFinish {
                    fail("This pipeline shouldn't finish")
                }
                assertEquals("another", subject)
                secondaryOk = true
                throw UnsupportedOperationException()
            }
            fork("another", secondary)
        }

        pipeline.intercept {
            fail("This pipeline shouldn't run")
        }

        val execution = pipeline.execute("some")
        assertTrue(secondaryOk, "Secondary should be run")
        assertEquals(0, count)
        assertEquals(1, max)
        assertEquals(PipelineExecution.State.Finished, execution.state)
    }
}
