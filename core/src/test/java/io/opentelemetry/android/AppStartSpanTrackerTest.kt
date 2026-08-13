/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android

import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class AppStartSpanTrackerTest {
    private val tracker = AppStartSpanTracker()
    private val context = mockk<Context>()

    @BeforeEach
    @AfterEach
    fun reset() {
        AppStartSpans.current = null
    }

    @Test
    fun `publishes app start span on start`() {
        val span = appStartSpan(SPAN_ID_A)

        tracker.onStart(context, span)

        assertThat(AppStartSpans.current).isSameAs(span)
    }

    @Test
    fun `ignores spans that are not app start`() {
        val span = mockk<ReadWriteSpan>()
        every { span.name } returns "ui.navigation"

        tracker.onStart(context, span)

        assertThat(AppStartSpans.current).isNull()
    }

    @Test
    fun `clears when the published span ends`() {
        val span = appStartSpan(SPAN_ID_A)
        tracker.onStart(context, span)

        tracker.onEnd(endedSpan(RumConstants.APP_START_SPAN_NAME, SPAN_ID_A))

        assertThat(AppStartSpans.current).isNull()
    }

    /**
     * A warm start can open while a previous app.start is still closing. Clearing on
     * the wrong span would blind wrappers for the rest of the live span's window.
     */
    @Test
    fun `keeps the live span when a different app start span ends`() {
        val live = appStartSpan(SPAN_ID_B)
        tracker.onStart(context, live)

        tracker.onEnd(endedSpan(RumConstants.APP_START_SPAN_NAME, SPAN_ID_A))

        assertThat(AppStartSpans.current).isSameAs(live)
    }

    /** Cold, warm and hot all carry the same span name, so all three are tracked. */
    @Test
    fun `tracks a later app start span, covering warm and hot starts`() {
        val cold = appStartSpan(SPAN_ID_A)
        tracker.onStart(context, cold)
        tracker.onEnd(endedSpan(RumConstants.APP_START_SPAN_NAME, SPAN_ID_A))

        val warm = appStartSpan(SPAN_ID_B)
        tracker.onStart(context, warm)

        assertThat(AppStartSpans.current).isSameAs(warm)
    }

    private fun appStartSpan(spanId: String): ReadWriteSpan {
        val span = mockk<ReadWriteSpan>()
        every { span.name } returns RumConstants.APP_START_SPAN_NAME
        every { span.spanContext } returns spanContext(spanId)
        return span
    }

    private fun endedSpan(
        name: String,
        spanId: String,
    ): ReadableSpan {
        val span = mockk<ReadableSpan>()
        every { span.name } returns name
        every { span.spanContext } returns spanContext(spanId)
        return span
    }

    private fun spanContext(spanId: String): SpanContext =
        SpanContext.create(TRACE_ID, spanId, TraceFlags.getSampled(), TraceState.getDefault())

    private companion object {
        const val TRACE_ID = "00000000000000000000000000000042"
        const val SPAN_ID_A = "000000000000000a"
        const val SPAN_ID_B = "000000000000000b"
    }
}
