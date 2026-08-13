/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android

import io.opentelemetry.api.trace.Span

/**
 * The `app.start` span that is currently in flight, or null when no app start is in
 * progress.
 *
 * This exists so first-party wrappers (Flutter, React Native) can stamp their own
 * startup phases onto the span the native SDK owns, instead of emitting a parallel
 * startup trace that consumers would have to join. The wrapper's phases happen
 * inside the startup window but are invisible to the native layer, which cannot see
 * past the engine handoff.
 *
 * The write path is deliberately not public. A publicly settable global would let
 * any library on the classpath redirect wrapper telemetry onto a span of its
 * choosing, or clear it and drop the telemetry silently. [current] is maintained by
 * [AppStartSpanTracker], which observes every `app.start` span the SDK creates —
 * cold, warm and hot alike, regardless of which instrumentation started it.
 *
 * Null before a span is created and again once it ends. The tracker is notified
 * *after* the span ends, so a caller on another thread can briefly observe a
 * non-null span that has already closed; the SDK discards writes to an ended span,
 * so such an event is dropped rather than misfiled onto the wrong record.
 *
 * Callers must treat null as "startup window closed" and drop the event. Buffering
 * and replaying it later would attach a phase to a span that has already been
 * exported, or to the next startup's span.
 */
object AppStartSpans {
    @Volatile
    @JvmStatic
    var current: Span? = null
        internal set
}
