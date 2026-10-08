package net.gaiadesk.embed

import android.graphics.RectF
import net.gaiadesk.embed.internal.EmbedJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedJsonTest {
    private val token = "gdemb_" + "0123456789abcdef".repeat(4)

    /** android.jar's RectF is a stub on the JVM: set its fields directly. */
    private fun rect(l: Float, t: Float, r: Float, b: Float) = RectF().apply { left = l; top = t; right = r; bottom = b }

    @Test
    fun configCarriesConsentCaptureAndMasks() {
        val c = GaiaDeskEmbed.Configuration(token, "Acme", server = "http://127.0.0.1:9000", guided = true, maskedRects = listOf(rect(10f, 20f, 110f, 50f)))
        val o = JSONObject(EmbedJson.config(c, consentGranted = true))
        assertEquals(true, o.getBoolean("consent_granted"))
        assertEquals("app", o.getString("capture"))
        assertEquals(true, o.getBoolean("guided"))
        assertEquals("http://127.0.0.1:9000", o.getString("server"))
        val m = o.getJSONArray("masks").getJSONObject(0)
        assertEquals(100.0, m.getDouble("w"), 0.0)
        assertEquals(30.0, m.getDouble("h"), 0.0)
        assertTrue(!o.has("window_id"))
        val screen = JSONObject(EmbedJson.config(c.copy(capture = GaiaDeskEmbed.Capture.SCREEN, maskedRects = emptyList()), false))
        assertEquals("screen", screen.getString("capture"))
        assertEquals(false, screen.getBoolean("consent_granted"))
        assertTrue(!screen.has("masks"))
    }

    @Test
    fun masksFlattenToQuadruples() {
        val a = EmbedJson.flatten(listOf(rect(1f, 2f, 4f, 6f), rect(0f, 0f, 10f, 10f)))
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0, 0.0, 0.0, 10.0, 10.0), a.toList())
    }

    @Test
    fun eventsParse() {
        assertEquals(GaiaDeskEmbed.Event.Code("123456789", "ss_1", "cobrowse"), EmbedJson.event("""{"type":"code","code":"123456789","session_id":"ss_1","mode":"cobrowse","expires_at":1}"""))
        assertEquals(GaiaDeskEmbed.Event.Joined(null), EmbedJson.event("""{"type":"joined","agent":null}"""))
        assertEquals(GaiaDeskEmbed.Event.Action("click", false, "masked"), EmbedJson.event("""{"type":"action","action":"click","applied":false,"reason":"masked"}"""))
        assertEquals(GaiaDeskEmbed.Event.Action("key", true, null), EmbedJson.event("""{"type":"action","action":"key","applied":true,"reason":null}"""))
        assertEquals(GaiaDeskEmbed.Event.Ended("stopped"), EmbedJson.event("""{"type":"ended","reason":"stopped"}"""))
        assertEquals(GaiaDeskEmbed.Event.Left, EmbedJson.event("""{"type":"left"}"""))
        assertNull(EmbedJson.event("""{"type":"future"}"""))
        assertNull(EmbedJson.event("not json"))
    }

    @Test
    fun errorsParse() {
        assertEquals("consent_required" to "say yes", EmbedJson.error("""{"code":"consent_required","message":"say yes"}"""))
        assertEquals("unknown", EmbedJson.error("garbage").first)
    }

    @Test
    fun consentSaysWhatIsShared() {
        val (t, m) = EmbedJson.consentText("Acme", false, GaiaDeskEmbed.Capture.APP)
        assertEquals("Share this app's screen with Acme support?", t)
        assertTrue(m.contains("Nothing outside this app"))
        val (ts, ms) = EmbedJson.consentText("Acme", true, GaiaDeskEmbed.Capture.SCREEN)
        assertTrue(ts.contains("your screen"))
        assertTrue(ms.contains("Android will ask"))
        assertTrue(ms.contains("pause"))
    }

    @Test
    fun picturesFitAndAlignTo16() {
        assertEquals(576 to 1280, EmbedJson.fit(1080, 2400))
        assertEquals(1280 to 800, EmbedJson.fit(2560, 1600))
        assertEquals(400 to 640, EmbedJson.fit(400, 640), )
        val (w, h) = EmbedJson.fit(1001, 777)
        assertTrue(w % 16 == 0 && h % 16 == 0)
    }

    @Test
    fun theConsolesKeysMapToAndroidKeyCodes() {
        assertEquals(66, EmbedJson.keyCode("Enter"))
        assertEquals(67, EmbedJson.keyCode("Backspace"))
        assertEquals(22, EmbedJson.keyCode("ArrowRight"))
        assertNull(EmbedJson.keyCode("F4"))
    }
}
