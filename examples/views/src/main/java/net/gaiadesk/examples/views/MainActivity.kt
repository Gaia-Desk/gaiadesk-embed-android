// HelpDesk (Views): an Android Views app ("Acme Bank") with a Get help button.
//
// Your backend creates the support session (POST /v1/support/sessions with a secret key) and
// hands the app the embed token; here it comes from the text field or the launch intent. The red
// "card number" view is masked: your support team sees it painted black.
//
// Test hooks (intent extras, e.g. `adb shell am start -n net.gaiadesk.examples.views/.MainActivity
// --es token gdemb_… --es server http://127.0.0.1:9000 --ez autostart true --ez test_consent true`):
//   token, server     the session
//   autostart         press Get help at launch
//   test_consent      answer the consent dialog "Share" (tests only)
//   guided            allow guided input
//   cmd               stop | pause | resume (to the running app, singleTop)
// Every event is logged as one JSON line under the tag GaiaDeskDemo.
package net.gaiadesk.examples.views

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import net.gaiadesk.embed.GaiaDeskEmbed
import net.gaiadesk.embed.GaiaDeskEmbedException
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private var help: GaiaDeskEmbed? = null
    private lateinit var token: EditText
    private lateinit var status: TextView
    private lateinit var card: TextView
    private lateinit var blue: FrameLayout
    private lateinit var buy: Button
    private var bought = 0

    private fun emit(o: JSONObject) = Log.i("GaiaDeskDemo", o.toString())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(52, 199, 89))
            setPadding(px(20), px(60), px(20), px(20))
        }
        col.addView(TextView(this).apply { text = "Acme Bank"; textSize = 26f; setTextColor(Color.BLACK) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        card = TextView(this).apply {
            text = "Card 4242 4242 4242 4242"
            gravity = Gravity.CENTER
            setBackgroundColor(Color.RED)
        }
        blue = FrameLayout(this).apply { setBackgroundColor(Color.BLUE) }
        row.addView(card, LinearLayout.LayoutParams(px(200), px(120)).apply { rightMargin = px(10) })
        row.addView(blue, LinearLayout.LayoutParams(px(110), px(120)))
        col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(16) })
        buy = Button(this).apply {
            text = "Buy"
            setOnClickListener {
                bought++
                emit(JSONObject().put("bought", bought))
            }
        }
        col.addView(buy, LinearLayout.LayoutParams(px(160), px(56)).apply { topMargin = px(16) })
        token = EditText(this).apply { hint = "gdemb_… (from your backend)"; setText(intent.getStringExtra("token") ?: "") }
        col.addView(token, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(Button(this).apply { text = "Get help"; setOnClickListener { getHelp() } }, LinearLayout.LayoutParams(px(160), px(56)))
        status = TextView(this).apply { text = "Press Get help to share this app with Acme support."; setTextColor(Color.BLACK) }
        col.addView(status)
        setContentView(col)
        col.post {
            fun box(v: android.view.View) = GaiaDeskEmbed.maskedRectOf(v)!!.let { JSONArray(listOf(it.left, it.top, it.width(), it.height())) }
            val decor = window.decorView
            emit(JSONObject().put("type", "demo").put("build", GaiaDeskEmbed.buildInfo).put("frame", JSONArray(listOf(decor.width, decor.height)))
                .put("card", box(card)).put("blue", box(blue)).put("buy", box(buy)))
            if (intent.getBooleanExtra("autostart", false)) getHelp()
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
        val go = { agreed: Boolean -> if (agreed) start(guided) }
        if (intent.getBooleanExtra("test_consent", false)) go(true) else GaiaDeskEmbed.requestConsent(this, "Acme", guided, answer = go)
    }

    private fun start(guided: Boolean) {
        val cfg = GaiaDeskEmbed.Configuration(
            embedToken = token.text.toString(),
            company = "Acme",
            server = intent.getStringExtra("server"),
            guided = guided,
            maskedRects = listOfNotNull(GaiaDeskEmbed.maskedRectOf(card)),
        )
        try {
            help = GaiaDeskEmbed.start(this, cfg, consentGranted = true, onEvent = ::on)
        } catch (e: GaiaDeskEmbedException) {
            emit(JSONObject().put("start_error", e.code).put("message", e.message))
            status.text = "Could not start: ${e.message}"
        }
    }

    private fun on(e: GaiaDeskEmbed.Event) {
        val o = JSONObject()
        when (e) {
            is GaiaDeskEmbed.Event.State -> o.put("type", "state").put("state", e.state)
            is GaiaDeskEmbed.Event.Code -> {
                o.put("type", "code").put("code", e.code).put("session_id", e.sessionId).put("mode", e.mode)
                status.text = "Your support code: ${e.code}"
            }
            is GaiaDeskEmbed.Event.Joined -> o.put("type", "joined").put("agent", e.agent ?: JSONObject.NULL)
            is GaiaDeskEmbed.Event.Left -> o.put("type", "left")
            is GaiaDeskEmbed.Event.Control -> o.put("type", "control").put("allowed", e.allowed)
            is GaiaDeskEmbed.Event.Action -> o.put("type", "action").put("action", e.action).put("applied", e.applied).put("reason", e.reason ?: JSONObject.NULL)
            is GaiaDeskEmbed.Event.Error -> o.put("type", "error").put("code", e.code).put("message", e.message)
            is GaiaDeskEmbed.Event.Ended -> {
                o.put("type", "ended").put("reason", e.reason)
                status.text = "Sharing ended (${e.reason})."
                help = null
            }
        }
        emit(o)
    }
}
