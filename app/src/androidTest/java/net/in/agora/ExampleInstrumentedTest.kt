package net.in.agora

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        // Asserts the INSTALLED package id. Namespace and applicationId are
        // both net.in.agora (docs/engineering-notes.md §9), so this also
        // guards the manifest wiring: a namespace/app-id divergence would keep
        // this test green while the launcher component resolved to a
        // non-existent class.
        assertEquals("net.in.agora", appContext.packageName)
    }
}