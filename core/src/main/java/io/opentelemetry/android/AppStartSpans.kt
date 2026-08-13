/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.sdk.trace.ReadWriteSpan
import java.util.concurrent.TimeUnit

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
 * choosing, or clear it and drop the telemetry silently. The value is maintained by
 * [AppStartSpanTracker], which observes every `app.start` span the SDK creates —
 * cold, warm and hot alike, regardless of which instrumentation started it.
 *
 * Callers must treat null as "startup window closed" and drop the event. Buffering
 * and replaying it later would attach a phase to a span that has already been
 * exported, or to the next startup's span.
 *
 * Not populated when the host app supplies its own pre-built `OpenTelemetrySdk` via
 * `SdkPreconfiguredRumBuilder`: a span processor cannot be added to a tracer
 * provider that is already built, so wrapper startup phases are unavailable on that
 * path.
 */
object AppStartSpans {
    /**
     * Upper bound on how long a published span is trusted, mirroring
     * `AppStartupTimer.MAX_TIME_TO_UI_INIT`.
     *
     * A span that is abandoned rather than ended produces no `onEnd`, so nothing
     * would ever clear it: `AppStartupTimer` discards its span without ending it
     * when UI init arrives too late, which is the normal outcome when Android
     * starts the process in the background and the user opens the app minutes
     * later. Without this bound, [current] would keep returning a span that will
     * never be exported, and every wrapper write for the rest of the process would
     * vanish into it while still looking like a success.
     *
     * Startup is bounded by definition, so treating anything open this long as
     * closed costs nothing real and converts silent loss into a reported drop.
     */
    private val MAX_WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1)

    @Volatile
    private var tracked: ReadWriteSpan? = null

    /**
     * The live `app.start` span, or null when the startup window is closed.
     *
     * Returns null for a span that has already ended — the tracker is notified after
     * the fact, so without this a caller could observe a closed span and write into
     * it — and for one that has been open beyond [MAX_WINDOW_NANOS].
     */
    @JvmStatic
    val current: Span?
        get() {
            val span = tracked ?: return null
            if (span.hasEnded() || span.latencyNanos > MAX_WINDOW_NANOS) {
                return null
            }
            return span
        }

    internal fun publish(span: ReadWriteSpan) {
        tracked = span
    }

    /**
     * Clears only when [spanContext] identifies the tracked span, so a span ending
     * after a newer one has been published does not blind wrappers for the rest of
     * the newer span's window.
     */
    internal fun clearIfTracked(spanContext: SpanContext) {
        if (tracked?.spanContext == spanContext) {
            tracked = null
        }
    }

    internal fun clear() {
        tracked = null
    }
}
