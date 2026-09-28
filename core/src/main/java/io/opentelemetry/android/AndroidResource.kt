/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.VisibleForTesting
import io.opentelemetry.android.common.RumConstants.APP_FRAMEWORK_KEY
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.semconv.ServiceAttributes.SERVICE_NAME
import io.opentelemetry.semconv.ServiceAttributes.SERVICE_VERSION
import io.opentelemetry.semconv.incubating.AndroidIncubatingAttributes.ANDROID_OS_API_LEVEL
import io.opentelemetry.semconv.incubating.AppIncubatingAttributes.APP_INSTALLATION_ID
import io.opentelemetry.semconv.incubating.DeviceIncubatingAttributes.DEVICE_MANUFACTURER
import io.opentelemetry.semconv.incubating.DeviceIncubatingAttributes.DEVICE_MODEL_IDENTIFIER
import io.opentelemetry.semconv.incubating.DeviceIncubatingAttributes.DEVICE_MODEL_NAME
import io.opentelemetry.semconv.incubating.OsIncubatingAttributes.OS_DESCRIPTION
import io.opentelemetry.semconv.incubating.OsIncubatingAttributes.OS_NAME
import io.opentelemetry.semconv.incubating.OsIncubatingAttributes.OS_TYPE
import io.opentelemetry.semconv.incubating.OsIncubatingAttributes.OS_VERSION
import java.util.Locale
import java.util.UUID

private const val SHARED_PREF_FILE = "opentelemetry-android"
private const val DEFAULT_APP_NAME = "unknown_service:android"

private const val FRAMEWORK_FLUTTER = "flutter"
private const val FRAMEWORK_REACT_NATIVE = "react_native"
private const val FRAMEWORK_NATIVE = "native_android"

/**
 * Marker classes that, when present on the classpath, identify a cross-platform shell. Ordered by
 * priority; the first present marker wins. Falls back to [FRAMEWORK_NATIVE] when none resolve.
 *
 * React Native is matched primarily on `ReactApplication`, a core interface that is stable across
 * the legacy bridge and the new (Fabric/bridgeless) architecture; `ReactRootView` is kept as a
 * secondary marker for older RN versions where the primary one may be absent.
 */
private val FRAMEWORK_MARKERS: List<Pair<String, String>> =
    listOf(
        "io.flutter.embedding.engine.FlutterEngine" to FRAMEWORK_FLUTTER,
        "com.facebook.react.ReactApplication" to FRAMEWORK_REACT_NATIVE,
        "com.facebook.react.ReactRootView" to FRAMEWORK_REACT_NATIVE,
    )

object AndroidResource {
    /**
     * Total device RAM in bytes. Not an OTel semconv key — matches the wire-key string
     * `instrumentation/system-metrics` used to emit as an `app.metrics` span attribute before that
     * value moved to the resource, so existing queries on the key name are unaffected; only its
     * OTLP location changed.
     */
    @JvmField
    val SYSTEM_MEMORY_TOTAL: AttributeKey<Long> = AttributeKey.longKey("system.memory.total")

    /** Total disk space of the internal data partition in bytes. Same rename history as above. */
    @JvmField
    val SYSTEM_DISK_TOTAL: AttributeKey<Long> = AttributeKey.longKey("system.disk.total")

    @JvmStatic
    fun createDefault(context: Context): Resource = createDefault(context, DefaultDeviceCapacityReader)

    /**
     * [deviceCapacityReader] is injectable only so tests can supply deterministic values; it is
     * deliberately kept off the published API, the same shape as [resolveAppFramework].
     */
    @VisibleForTesting
    internal fun createDefault(
        context: Context,
        deviceCapacityReader: DeviceCapacityReader,
    ): Resource {
        val appName = readAppName(context)
        val resourceBuilder = Resource.builder().put(SERVICE_NAME, appName)
        val appVersion = readAppVersion(context)
        appVersion?.let { resourceBuilder.put(SERVICE_VERSION, it) }

        resourceBuilder
            .put(DEVICE_MODEL_NAME, Build.MODEL)
            .put(DEVICE_MODEL_IDENTIFIER, Build.MODEL)
            .put(DEVICE_MANUFACTURER, Build.MANUFACTURER)
            .put(OS_NAME, "Android")
            .put(ANDROID_OS_API_LEVEL, Build.VERSION.SDK_INT.toString())
            .put(OS_TYPE, "linux")
            .put(OS_VERSION, Build.VERSION.RELEASE)
            .put(OS_DESCRIPTION, oSDescription)
            .put(APP_INSTALLATION_ID, readInstallId(context))
            .put(APP_FRAMEWORK_KEY, appFramework)

        // Omitted rather than reported as a sentinel when unreadable. The resource is immutable for
        // the life of the process, so a transient StatFs/getMemoryInfo failure would otherwise pin
        // a bogus value onto every log and metric until the app restarts — the same reason
        // service.version is skipped above when the version cannot be read.
        deviceCapacityReader.readTotalRamBytes(context).takeIf { it >= 0 }?.let {
            resourceBuilder.put(SYSTEM_MEMORY_TOTAL, it)
        }
        deviceCapacityReader.readTotalDiskBytes().takeIf { it >= 0 }?.let {
            resourceBuilder.put(SYSTEM_DISK_TOTAL, it)
        }

        return resourceBuilder.build()
    }

    /**
     * Host app framework, resolved once per process. The classpath is fixed for the process
     * lifetime, so the marker-class probes are memoized; the agent builds the default resource more
     * than once during init, and this keeps every build after the first lookup-free.
     */
    private val appFramework: String by lazy { resolveAppFramework() }

    /**
     * Resolves the framework by probing for marker classes, returning the first match in priority
     * order. Falls back to [FRAMEWORK_NATIVE] when no cross-platform marker resolves. The
     * [isPresent] probe is injectable so all branches can be exercised without the real classpath.
     */
    @VisibleForTesting
    internal fun resolveAppFramework(isPresent: (String) -> Boolean = ::isClassPresent): String {
        for ((className, framework) in FRAMEWORK_MARKERS) {
            if (isPresent(className)) {
                return framework
            }
        }
        return FRAMEWORK_NATIVE
    }

    private fun isClassPresent(className: String): Boolean =
        try {
            Class.forName(className, false, AndroidResource::class.java.classLoader)
            true
        } catch (_: Throwable) {
            // Throwable (not Exception) on purpose: a present marker with missing transitive deps
            // surfaces as NoClassDefFoundError/LinkageError, which are Errors, not Exceptions.
            false
        }

    /**
     * Resource holding only `service.name`. No longer used by the SDK: trace spans now carry the
     * full resource on every export, like logs and metrics.
     */
    @Deprecated("Trace spans carry the full resource; use createDefault(context).")
    @JvmStatic
    fun createMinimal(context: Context): Resource =
        Resource.builder().put(SERVICE_NAME, readAppName(context)).build()

    @SuppressLint("UseKtx")
    private fun readInstallId(context: Context): String {
        // install ID is persisted using the app.installation.id semconv as its key
        val prefs = context.getSharedPreferences(SHARED_PREF_FILE, 0)
        val installId = prefs.getString(APP_INSTALLATION_ID.key, null)

        if (installId == null) {
            val id = UUID.randomUUID().toString()
            prefs.edit().putString(APP_INSTALLATION_ID.key, id).apply()
            return id
        }
        return installId
    }

    /**
     * App label, used as `service.name`. Resolved against the app's *default* resource
     * configuration rather than the device's, so the value does not move when the user switches
     * the device language: `service.name` identifies the app, and a per-language value splits one
     * app across several services in the backend.
     *
     * Falls back, in order, to the device-locale label (the behaviour before this was made
     * locale-stable), the non-localized label, and the package name. Every step is
     * individually non-throwing, so one unreadable candidate does not cost the app its name.
     */
    private fun readAppName(context: Context): String =
        try {
            val ctx = context.applicationContext
            val appInfo = ctx.applicationInfo
            val labelRes = appInfo.labelRes
            val label =
                if (labelRes == 0) {
                    null
                } else {
                    labelInDefaultLocale(ctx, labelRes) ?: labelOrNull(ctx, labelRes)
                }
            label
                ?: appInfo.nonLocalizedLabel?.toString()?.takeIf(String::isNotBlank)
                ?: ctx.packageName?.takeIf(String::isNotBlank)
                ?: DEFAULT_APP_NAME
        } catch (_: Exception) {
            DEFAULT_APP_NAME
        }

    /**
     * Reads [labelRes] through a context whose configuration carries an undefined locale, which
     * makes the resource system select the app's unqualified `res/values` string whatever the
     * device language is. Returns null when the configuration context cannot be created or the
     * string resolves empty, leaving [readAppName] to fall back.
     */
    // AppBundleLocaleChanges guards against asking for a language that Play's language splitting
    // may not have installed. The opposite is asked for here: an undefined locale selects the
    // unqualified res/values resources, which are always in the base APK and are never split out.
    @SuppressLint("AppBundleLocaleChanges")
    private fun labelInDefaultLocale(
        ctx: Context,
        labelRes: Int,
    ): String? =
        try {
            val defaultLocaleConfig =
                Configuration(ctx.resources.configuration).apply { setLocale(Locale.ROOT) }
            labelOrNull(ctx.createConfigurationContext(defaultLocaleConfig), labelRes)
        } catch (_: Exception) {
            null
        }

    /**
     * [labelRes] as [ctx] resolves it, or null when it resolves empty or cannot be read at all — a
     * stale label id left by a partial upgrade throws
     * [android.content.res.Resources.NotFoundException], and that has to fall through to the
     * remaining candidates rather than abandon the name.
     */
    private fun labelOrNull(
        ctx: Context,
        labelRes: Int,
    ): String? =
        try {
            ctx.getString(labelRes).takeIf(String::isNotBlank)
        } catch (_: Exception) {
            null
        }

    private fun readAppVersion(context: Context): String? =
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName
        } catch (_: Exception) {
            null
        }

    private val oSDescription: String
        get() {
            val osDescriptionBuilder = StringBuilder()
            return osDescriptionBuilder
                .append("Android Version ")
                .append(Build.VERSION.RELEASE)
                .append(" (Build ")
                .append(Build.ID)
                .append(" API level ")
                .append(Build.VERSION.SDK_INT)
                .append(")")
                .toString()
        }
}
