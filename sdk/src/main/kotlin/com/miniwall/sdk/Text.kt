package com.miniwall.sdk

import android.graphics.Typeface
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer as ComposeTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.miniwall.core.logic.TextLogic
import com.miniwall.core.render.FontSpec
import com.miniwall.core.render.TextAlign
import com.miniwall.core.render.TextMeasurer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Fonts for texts: a downloaded custom font (`components_config.fonts[name].android`, TTF/OTF),
 * else the system font at the requested weight.
 */
internal object Fonts {
    private val families = ConcurrentHashMap<String, FontFamily>()

    /**
     * A TTF/OTF for a font name (`Family@weight`). A variable font is offered at every weight
     * (the `wght` axis; API 26+), so bold runs and other weights of the family pick the right one.
     */
    @Suppress("DEPRECATION")
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    fun register(name: String, file: File) {
        runCatching {
            Typeface.createFromFile(file) // throws on a file that is not a font
            families[name] = FontFamily((1..9).map { n ->
                val weight = FontWeight(n * 100)
                // Variation settings need API 26; before it the file's default weight is used for every weight.
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    androidx.compose.ui.text.font.Font(file, weight, FontStyle.Normal, androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(n * 100)))
                } else {
                    androidx.compose.ui.text.font.Font(file, weight, FontStyle.Normal)
                }
            })
        }
    }

    fun isRegistered(name: String) = families.containsKey(name)

    fun family(spec: FontSpec, code: Boolean): FontFamily =
        if (code) FontFamily.Monospace else spec.name?.let { families[it] } ?: FontFamily.SansSerif
}

internal object TextStyles {
    /**
     * The paywall's text style: every line exactly 1.5 x the font size with the glyphs centered
     * (CSS `line-height: 1.5` and its half-leading), sizes in CSS px = dp (font scale off: the
     * layout is in dp like the web's px).
     */
    fun style(font: FontSpec, align: TextAlign = TextAlign.START, brush: Brush? = null, color: Color = Color.Black): TextStyle = TextStyle(
        // No TextStyle overload takes both a color and a brush: build with the color, then switch to the brush.
        color = if (brush == null) color else Color.Unspecified,
        fontSize = font.size.sp,
        fontWeight = FontWeight(font.weight.coerceIn(1, 1000)),
        fontFamily = Fonts.family(font, code = false),
        lineHeight = 1.5.em,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        textAlign = when (align) {
            TextAlign.START -> ComposeTextAlign.Start
            TextAlign.CENTER -> ComposeTextAlign.Center
            TextAlign.END -> ComposeTextAlign.End
        },
    ).let { if (brush != null) it.copy(brush = brush) else it }

    /** Runs as styled text; links open through [onLink] (the screen's URL handling). */
    fun annotated(runs: List<TextLogic.Run>, font: FontSpec, onLink: ((String) -> Unit)? = null): AnnotatedString = buildAnnotatedString {
        for (run in runs) {
            val style = SpanStyle(
                fontWeight = if (run.bold) FontWeight(maxOf(font.weight, 700).coerceIn(1, 1000)) else null,
                fontStyle = if (run.italic) FontStyle.Italic else null,
                textDecoration = if (run.strikethrough) TextDecoration.LineThrough else null,
                fontFamily = if (run.code) FontFamily.Monospace else null,
            )
            val link = run.link
            if (link != null && onLink != null) {
                withLink(LinkAnnotation.Clickable(link) { onLink(link) }) { withStyle(style) { append(run.text) } }
            } else {
                withStyle(style) { append(run.text) }
            }
        }
    }
}

/**
 * Measures with Compose's text layout, the engine the text is drawn with. Font scale is fixed
 * at 1 so sizes match the web; sizes in dp.
 */
internal class ComposeMeasurer(private val measurer: ComposeTextMeasurer, private val density: Density) : TextMeasurer {
    private val unscaled = Density(density.density, fontScale = 1f)

    override fun measure(runs: List<TextLogic.Run>, font: FontSpec, maxWidth: Double?): Pair<Double, Double> {
        val maxPx = maxWidth?.let { ceil(it * unscaled.density).toInt().coerceAtLeast(0) } ?: Constraints.Infinity
        val result: TextLayoutResult = measurer.measure(
            text = TextStyles.annotated(runs, font),
            style = TextStyles.style(font),
            constraints = Constraints(maxWidth = maxPx),
            density = unscaled,
            softWrap = maxWidth != null,
        )
        // Whole pixels, rounded up: a box 45.3 px wide is laid out 45 px wide, and the text, drawn in it, no longer fits and
        // wraps: its last word or letter ("Monthly" -> "Monthl") falls on a second line that the box clips.
        val widthPx = ceil((0 until result.lineCount).maxOfOrNull { result.getLineRight(it) - result.getLineLeft(it) } ?: 0f)
        return (widthPx / unscaled.density).toDouble() to (result.size.height / unscaled.density).toDouble()
    }
}
