/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android

import android.app.Application
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.opentelemetry.semconv.ServiceAttributes.SERVICE_NAME
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Exercises the real Android resource machinery, which the mocked [AndroidResourceTest] cannot:
 * whether an undefined locale genuinely selects the unqualified `res/values` resource, on every
 * API level the library supports.
 *
 * A framework string stands in for the app label — `:core` ships no resources of its own to
 * qualify, and the platform translates its own strings, so the device-locale and default-locale
 * values differ. Each test asserts that difference first; without it the real assertion would
 * hold vacuously.
 */
@RunWith(AndroidJUnit4::class)
internal class AndroidResourceLocaleTest {
    @Test
    @Config(qualifiers = "hi", sdk = [23, 24, 28, 34])
    fun `the label is read from the default locale, not the device locale`() {
        val context = appContextWithLabel(android.R.string.ok)

        assertThat(context.getString(android.R.string.ok)).isNotEqualTo(DEFAULT_LOCALE_OK)

        assertThat(AndroidResource.createDefault(context).getAttribute(SERVICE_NAME))
            .isEqualTo(DEFAULT_LOCALE_OK)
    }

    /** The common shape by far: one app language, so the default resource is the only one. */
    @Test
    @Config(qualifiers = "en", sdk = [23, 34])
    fun `a device on the default language is unaffected`() {
        val context = appContextWithLabel(android.R.string.ok)

        assertThat(AndroidResource.createDefault(context).getAttribute(SERVICE_NAME))
            .isEqualTo(DEFAULT_LOCALE_OK)
    }

    /**
     * "Default" means the unqualified `res/values`, not English. An app whose base language is
     * Hindi keeps its Hindi label on an English phone — the point is that the value never moves,
     * not that it is anglicised.
     */
    @Test
    @Config(qualifiers = "en", sdk = [34])
    fun `the default resource wins even when the device language has its own translation`() {
        val context = appContextWithLabel(android.R.string.ok)
        val hindi =
            context.createConfigurationContext(
                Configuration(context.resources.configuration).apply { setLocale(Locale("hi")) },
            )

        assertThat(hindi.getString(android.R.string.ok)).isNotEqualTo(DEFAULT_LOCALE_OK)
        assertThat(AndroidResource.createDefault(context).getAttribute(SERVICE_NAME))
            .isEqualTo(DEFAULT_LOCALE_OK)
    }

    /**
     * `android:label="a literal"` rather than `@string/...`, which is how several of our own
     * harness apps declare it: there is no label resource to resolve.
     */
    @Test
    @Config(sdk = [23, 34])
    fun `a non-localized label is used when there is no label resource`() {
        val context = appContextWithLabel(labelRes = 0, nonLocalizedLabel = "vupay-view")

        assertThat(AndroidResource.createDefault(context).getAttribute(SERVICE_NAME))
            .isEqualTo("vupay-view")
    }

    @Test
    @Config(sdk = [23, 34])
    fun `the package name is used when the app declares no label at all`() {
        val context = appContextWithLabel(labelRes = 0)

        assertThat(AndroidResource.createDefault(context).getAttribute(SERVICE_NAME))
            .isEqualTo(context.packageName)
    }

    /**
     * A label id left dangling by a partial upgrade throws from `getString`. That must cost the
     * lookup only its first candidate, not the whole name.
     */
    @Test
    @Config(qualifiers = "hi", sdk = [23, 34])
    fun `a dangling label resource falls through to the remaining candidates`() {
        val context = appContextWithLabel(DANGLING_LABEL_RES, nonLocalizedLabel = "vupay-view")

        assertThat(AndroidResource.createDefault(context).getAttribute(SERVICE_NAME))
            .isEqualTo("vupay-view")
    }

    @Test
    @Config(qualifiers = "hi", sdk = [23, 34])
    fun `a dangling label resource with nothing else left falls through to the package name`() {
        val context = appContextWithLabel(DANGLING_LABEL_RES)

        assertThat(AndroidResource.createDefault(context).getAttribute(SERVICE_NAME))
            .isEqualTo(context.packageName)
    }

    private fun appContextWithLabel(
        labelRes: Int,
        nonLocalizedLabel: CharSequence? = null,
    ): Application =
        ApplicationProvider.getApplicationContext<Application>().apply {
            applicationInfo.labelRes = labelRes
            applicationInfo.nonLocalizedLabel = nonLocalizedLabel
        }

    private companion object {
        /** `android.R.string.ok` as `res/values` (no locale qualifier) defines it. */
        const val DEFAULT_LOCALE_OK = "OK"

        /** Well-formed id in the app package that no resource is assigned to. */
        const val DANGLING_LABEL_RES = 0x7F0F_FFFF
    }
}
