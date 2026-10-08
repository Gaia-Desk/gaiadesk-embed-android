package net.gaiadesk.embed

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.gaiadesk.embed.internal.Guided
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** On a device or emulator, with the real libgaiadesk_embed.so. */
@RunWith(AndroidJUnit4::class)
class EmbedDeviceTest {
    private val token = "gdemb_" + "0123456789abcdef".repeat(4)

    class Host : Activity() {
        var taps = 0
        lateinit var button: Button
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val root = FrameLayout(this)
            button = Button(this).apply {
                text = "Buy"
                setOnClickListener { taps++ }
            }
            root.addView(button, FrameLayout.LayoutParams(400, 200).apply { leftMargin = 100; topMargin = 300 })
            setContentView(root)
        }
    }

    @Test
    fun theLibraryLoadsAndSaysWhatItIs() {
        val info = GaiaDeskEmbed.buildInfo
        assertTrue(info, info.contains("\"os\":\"android\""))
        assertTrue(info, info.contains("\"test_source\":false"))
        assertTrue(info, info.contains("H264"))
    }

    @Test
    fun theLibraryRefusesWithoutConsentOrWithAnApiKey() {
        ActivityScenario.launch(Host::class.java).use { s ->
            s.onActivity { a ->
                for ((cfg, consent, want) in listOf(
                    Triple(GaiaDeskEmbed.Configuration(token, "Acme"), false, "consent_required"),
                    Triple(GaiaDeskEmbed.Configuration("gdk_live_secret", "Acme"), true, "bad_token"),
                )) {
                    try {
                        GaiaDeskEmbed.start(a, cfg, consent) {}
                        fail("started without $want")
                    } catch (e: GaiaDeskEmbedException) {
                        assertEquals(want, e.code)
                    }
                }
            }
        }
    }

    @Test
    fun aGuidedClickPressesTheAppsOwnButton() {
        ActivityScenario.launch(Host::class.java).use { s ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            s.onActivity { a ->
                val at = IntArray(2)
                a.button.getLocationInWindow(at)
                assertEquals(null, Guided.tap(a, at[0] + 50f, at[1] + 50f))
                assertEquals(1, a.taps)
            }
        }
    }
}
