/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android

import io.opentelemetry.android.common.RumConstants
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor

/**
 * Publishes the in-flight `app.start` span to [AppStartSpans] so wrapper SDKs can
 * stamp their own startup phases onto it.
 *
 * Implemented as a span processor rather than wired into a specific instrumentation
 * because `app.start` is created from more than one place: `AppStartupTimer` opens
 * the cold-start span, while `ActivityTracer` opens the warm and hot ones. A
 * processor sees all three, so returning from the background behaves the same as a
 * fresh launch without either class knowing this exists.
 *
 * The end hook compares span contexts rather than clearing unconditionally: two
 * `app.start` spans can legitimately overlap (a warm start beginning while a
 * previous span is closing), and clearing on the wrong one would blind wrappers for
 * the rest of the live span's window.
 */
internal class AppStartSpanTracker : SpanProcessor {
    override fun onStart(
        parentContext: Context,
        span: ReadWriteSpan,
    ) {
        if (span.name == RumConstants.APP_START_SPAN_NAME) {
            AppStartSpans.current = span
        }
    }

    override fun isStartRequired(): Boolean = true

    override fun onEnd(span: ReadableSpan) {
        if (span.name != RumConstants.APP_START_SPAN_NAME) {
            return
        }
        if (AppStartSpans.current?.spanContext == span.spanContext) {
            AppStartSpans.current = null
        }
    }

    override fun isEndRequired(): Boolean = true
}
