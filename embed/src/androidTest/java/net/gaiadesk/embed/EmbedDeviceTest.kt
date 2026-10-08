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
                // A wrapper's declaration: false never unlocks a native password field…
                assertEquals("masked", Guided.secretOf(pw, emptyList(), false))
                // …and a whole-screen View under a mask is not refused once declared not secret.
                val root = pw.parent as android.view.View
                val everywhere = listOf(doubleArrayOf(0.0, 0.0, 10000.0, 10000.0))
                assertEquals("masked", Guided.secretOf(root, everywhere, null))
                assertEquals(null, Guided.secretOf(root, everywhere, false))
                assertEquals("masked", Guided.secretOf(root, emptyList(), true))
                // A masked plain EditText (a card number, an IBAN; every React Native TextInput
                // is one) stays refused after a wrapper declared false on a blur.
                val card = android.widget.EditText(a)
                (a.button.parent as FrameLayout).addView(card, FrameLayout.LayoutParams(400, 120).apply { topMargin = 600 })
                card.measure(0, 0)
                card.layout(0, 600, 400, 720)
                val cardMask = listOf(doubleArrayOf(0.0, 0.0, 10000.0, 10000.0))
                assertEquals("masked", Guided.secretOf(card, cardMask, false))
                assertEquals("an unmasked field types", null, Guided.secretOf(card, emptyList(), false))
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
            }
            // A View delivers the click from a runnable it posts on the up: let it run.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            s.onActivity { a -> assertEquals(1, a.taps) }
        }
    }
}
