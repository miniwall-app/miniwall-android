// Sample app for trying the Miniwall Android SDK on an emulator.
//
// Launch extras (adb shell am start -n com.miniwall.example/.MainActivity --es tpl <name>):
//   tpl <name>         present templates/<name>.json (or components/<name>.json: carousel-pages, tabs-buttons, video-basic, choice-multiple...) on launch (template sweep, see ../../../sweep-templates.sh)
//   mode light|dark    the screen's color mode (default: the system's)
//   purchase true|false  fake purchase outcome (default true: 0.8 s, then success)
// The "result" text (content description `result`) shows what Miniwall.present returned: `purchased:monthly`, `closed`,
// `action:finish`, ... or `error:<message>`.
package com.miniwall.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.miniwall.core.logic.Period
import com.miniwall.core.logic.ProductInfo
import com.miniwall.core.session.PurchaseResult
import com.miniwall.sdk.ColorMode
import com.miniwall.sdk.Miniwall
import com.miniwall.sdk.PresentOptions
import com.miniwall.sdk.PresentationStyle
import com.miniwall.sdk.ScreenSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class MainActivity : ComponentActivity() {
    private var log by mutableStateOf("Tap a button.")
    private var templateShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Miniwall.configure(applicationContext, "pk_example", Miniwall.Options(analytics = false, timeoutMs = 3000))
        setContent {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                Text("Miniwall sample")
                Button(onClick = { template("calm-money")?.let { present(it, null) } }) { Text("Present calm-money") }
                Text(log, Modifier.semantics { contentDescription = "result" })
            }
        }
        val name = intent.getStringExtra("tpl")
        if (name != null && !templateShown && savedInstanceState == null) {
            templateShown = true
            lifecycleScope.launch {
                delay(600)
                val json = template(name)
                if (json == null) log = "error:template $name is not in the app assets" else present(json, styleFor(name), template("welcome"))
            }
        }
    }

    /**
     * A template of the library (`{description, paywall}`), bundled from the spec's fixtures/templates, or one of the small
     * component screens (carousel, tabs, video, multiple choice) from fixtures/components.
     */
    private fun template(name: String): JsonObject? = runCatching {
        val file = listOf("templates", "components").map { "$it/$name.json" }.first { path -> runCatching { assets.open(path).close() }.isSuccess }
        val root = Json.parseToJsonElement(assets.open(file).bufferedReader().use { it.readText() }).jsonObject
        (root["paywall"] as? JsonObject) ?: root
    }.getOrNull()

    /** How a placement would show the template: the library names its display in the file name; everything else is a full screen. */
    private fun styleFor(name: String): PresentationStyle? = when {
        name.endsWith("-toast") -> PresentationStyle.Toast(60.0)
        name.endsWith("-bar") || name.endsWith("-banner") -> PresentationStyle.Banner(top = true)
        name.endsWith("-slide-in") -> PresentationStyle.Sheet
        name.endsWith("-drawer") || name.endsWith("-launcher") -> PresentationStyle.Modal
        else -> null
    }

    private fun present(json: JsonObject, style: PresentationStyle?, next: JsonObject? = null) {
        log = "presenting"
        val purchase = intent.getStringExtra("purchase") != "false"
        val options = PresentOptions(
            products = mapOf(
                "monthly" to ProductInfo(9.99, "USD", Period.Every(Period.Unit.MONTH)),
                "annual" to ProductInfo(79.99, "USD", Period.Every(Period.Unit.YEAR), offer = ProductInfo.Offer(0.0, Period.Every(Period.Unit.DAY, 7))),
            ),
            style = style,
            colorMode = when (intent.getStringExtra("mode")) { "dark" -> ColorMode.DARK; "light" -> ColorMode.LIGHT; else -> null },
            screens = if (next != null) mapOf("next" to next) else emptyMap(),
            onPurchase = { delay(800); purchase },
            onRestore = { delay(300); false },
            onAction = { id, answers -> log = "Action $id, answers $answers"; true },
            onError = { log = "Error: ${it.message}" },
        )
        lifecycleScope.launch {
            log = try {
                describe(Miniwall.present(this@MainActivity, ScreenSource.Json(json), options))
            } catch (e: Throwable) {
                "error:${e.message}"
            }
        }
    }

    private fun describe(result: PurchaseResult): String = when (result) {
        is PurchaseResult.Purchased -> "purchased:${result.packageId}"
        is PurchaseResult.Restored -> "restored"
        is PurchaseResult.Closed -> "closed"
        is PurchaseResult.ActionTaken -> "action:${result.actionId}"
        is PurchaseResult.Skipped -> "skipped:${result.reason}"
    }
}
