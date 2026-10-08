// HelpDesk (Compose): a Jetpack Compose app ("Acme Bank") with a Get help button.
//
// The card is masked: `Modifier.track("card")` below reads its window rect (boundsInWindow) on
// every layout and hands it to the library (start-time `maskedRects`, then `setMaskedRects`). Guided clicks are real taps dispatched to the
// app's own window, so Compose buttons respond as to a finger.
//
// Test hooks: the same intent extras as the Views example (token, server, autostart, test_consent,
// guided, cmd=stop|pause|resume); events are logged as JSON lines under GaiaDeskDemo.
package net.gaiadesk.examples.compose

import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.gaiadesk.embed.GaiaDeskEmbed
import net.gaiadesk.embed.GaiaDeskEmbedException
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private var help: GaiaDeskEmbed? = null
    private var token by mutableStateOf("")
    private var status by mutableStateOf("Press Get help to share this app with Acme support.")
    private var bought = 0
    private val boxes = mutableMapOf<String, RectF>()
    private var reported = false

    private fun emit(o: JSONObject) = Log.i("GaiaDeskDemo", o.toString())

    /** The card's window rect, kept current for the library. */
    private fun mask(key: String, r: RectF) {
        if (boxes[key] == r) return
        boxes[key] = r
        if (key == "card") runCatching { help?.setMaskedRects(listOf(r)) }
        if (!reported && boxes.size == 3) {
            reported = true
            fun box(k: String) = boxes[k]!!.let { JSONArray(listOf(it.left, it.top, it.width(), it.height())) }
            val decor = window.decorView
            emit(JSONObject().put("type", "demo").put("build", GaiaDeskEmbed.buildInfo).put("frame", JSONArray(listOf(decor.width, decor.height)))
                .put("card", box("card")).put("blue", box("blue")).put("buy", box("buy")))
            if (intent.getBooleanExtra("autostart", false)) getHelp()
        }
    }

    private fun Modifier.track(key: String) = onGloballyPositioned { c ->
        val b = c.boundsInWindow()
        mask(key, RectF(b.left, b.top, b.right, b.bottom))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getStringExtra("token") ?: ""
        setContent {
            MaterialTheme {
                Column(
                    Modifier.fillMaxSize().background(Color(0xFF34C759)).padding(start = 20.dp, top = 60.dp, end = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Acme Bank", fontSize = 26.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(200.dp, 120.dp).background(Color.Red).track("card"), contentAlignment = Alignment.Center) {
                            Text("Card 4242 4242 4242 4242")
                        }
                        Box(Modifier.size(110.dp, 120.dp).background(Color.Blue).track("blue"))
                    }
                    Button(onClick = {
                        bought++
                        emit(JSONObject().put("bought", bought))
                    }, modifier = Modifier.size(160.dp, 56.dp).track("buy")) { Text("Buy") }
                    OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("gdemb_… (from your backend)") })
                    Button(onClick = ::getHelp) { Text("Get help") }
                    Text(status)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.getStringExtra("cmd")) {
            "stop" -> help?.stop()
            "pause" -> help?.setPaused(true)
            "resume" -> help?.setPaused(false)
        }
    }

    private fun getHelp() {
        val guided = intent.getBooleanExtra("guided", false)
        val go = { consent: GaiaDeskEmbed.Consent? -> if (consent != null) start(guided, consent) }
        if (intent.getBooleanExtra("test_consent", false)) go(GaiaDeskEmbed.recordConsent("Acme", guided)) else GaiaDeskEmbed.requestConsent(this, "Acme", guided, answer = go)
    }

    private fun start(guided: Boolean, consent: GaiaDeskEmbed.Consent) {
        val cfg = GaiaDeskEmbed.Configuration(token, "Acme", server = intent.getStringExtra("server"), guided = guided, maskedRects = listOfNotNull(boxes["card"]))
        try {
            help = GaiaDeskEmbed.start(this, cfg, consent = consent) { e -> on(e) }
        } catch (e: GaiaDeskEmbedException) {
            emit(JSONObject().put("start_error", e.code).put("message", e.message))
            status = "Could not start: ${e.message}"
        }
    }

    private fun on(e: GaiaDeskEmbed.Event) {
        val o = JSONObject()
        when (e) {
            is GaiaDeskEmbed.Event.State -> o.put("type", "state").put("state", e.state)
            is GaiaDeskEmbed.Event.Code -> {
                o.put("type", "code").put("code", e.code).put("session_id", e.sessionId).put("mode", e.mode)
                status = "Your support code: ${e.code}"
            }
            is GaiaDeskEmbed.Event.Joined -> o.put("type", "joined").put("agent", e.agent ?: JSONObject.NULL)
            is GaiaDeskEmbed.Event.Left -> o.put("type", "left")
            is GaiaDeskEmbed.Event.Control -> o.put("type", "control").put("allowed", e.allowed)
            is GaiaDeskEmbed.Event.Action -> o.put("type", "action").put("action", e.action).put("applied", e.applied).put("reason", e.reason ?: JSONObject.NULL)
            is GaiaDeskEmbed.Event.Error -> o.put("type", "error").put("code", e.code).put("message", e.message)
            is GaiaDeskEmbed.Event.Ended -> {
                o.put("type", "ended").put("reason", e.reason)
                status = "Sharing ended (${e.reason})."
                help = null
            }
        }
        emit(o)
    }
}
