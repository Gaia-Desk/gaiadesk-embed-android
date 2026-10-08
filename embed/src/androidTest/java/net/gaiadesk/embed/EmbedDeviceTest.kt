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
                fun refused(cfg: GaiaDeskEmbed.Configuration, consent: GaiaDeskEmbed.Consent, want: String, why: String) {
                    try {
                        GaiaDeskEmbed.start(a, cfg, consent) {}
                        fail("started: $why")
                    } catch (e: GaiaDeskEmbedException) {
                        assertEquals(why, want, e.code)
                    }
                }
                refused(GaiaDeskEmbed.Configuration("gdk_live_secret", "Acme"), GaiaDeskEmbed.recordConsent("Acme"), "bad_token", "an API key is not an embed token")
                // Consent is bound to what the user was asked.
                refused(GaiaDeskEmbed.Configuration(token, "Acme", guided = true), GaiaDeskEmbed.recordConsent("Acme", guided = false), "consent_required", "asked view-only, starting guided")
                refused(GaiaDeskEmbed.Configuration(token, "Acme"), GaiaDeskEmbed.recordConsent("Evil Corp"), "consent_required", "another company")
                val once = GaiaDeskEmbed.recordConsent("Acme")
                refused(GaiaDeskEmbed.Configuration("gdk_live_secret", "Acme"), once, "bad_token", "spends nothing: the config is refused first")
            }
        }
    }

    @Test
    fun guidedTypingNeverReachesAPasswordField() {
        ActivityScenario.launch(Host::class.java).use { s ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            s.onActivity { a ->
                val pw = android.widget.EditText(a).apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
                (a.button.parent as FrameLayout).addView(pw, FrameLayout.LayoutParams(400, 120))
                pw.requestFocus()
                assertEquals("masked", Guided.secret(a.currentFocus, emptyList()))
                assertEquals("masked", Guided.deliver("{\"t\":\"type\",\"text\":\"hunter2\"}", a, null))
                assertEquals("", pw.text.toString())
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
