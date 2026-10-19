package com.miniwall.sdk

import android.net.Uri
import android.provider.Settings
import com.miniwall.core.logic.PathOp
import com.miniwall.core.logic.SvgIcon
import android.widget.VideoView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.miniwall.core.layout.Rect
import com.miniwall.core.render.Content
import com.miniwall.core.render.Corners
import com.miniwall.core.render.Fill
import com.miniwall.core.render.GradientStop
import com.miniwall.core.render.Mask
import com.miniwall.core.render.Paint
import com.miniwall.core.render.RenderNode
import com.miniwall.core.render.Rgba
import com.miniwall.core.render.ScreenController
import com.miniwall.core.render.ScreenRender
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal fun Rgba.color() = Color(r.toFloat(), g.toFloat(), b.toFloat(), a.toFloat())
private fun d(v: Double): Dp = v.toFloat().dp

/** Rounded corners per corner; pill = half the shorter side. */
internal fun Corners.shape(): Shape = when {
    pill -> RoundedCornerShape(50)
    isZero -> RectangleShape
    else -> RoundedCornerShape(d(topLeading), d(topTrailing), d(bottomTrailing), d(bottomLeading))
}

internal fun Mask.shape(): Shape = when (this) {
    is Mask.Rect -> corners.shape()
    Mask.Circle -> CircleShape
    Mask.Concave -> GenericShape { size, _ ->
        moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height)
        // 54 CSS px up in the middle, in pixels of this shape (scaled by density at draw time).
        quadraticTo(size.width / 2, size.height - 54f * densityHint, 0f, size.height); close()
    }
    Mask.Convex -> GenericShape { size, _ ->
        val k = 27f * densityHint
        moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height - k)
        quadraticTo(size.width / 2, size.height + k, 0f, size.height - k); close()
    }
}

/** Set from the screen's density before shapes draw (GenericShape gets pixels). */
@Volatile internal var densityHint = 1f

/** A Brush for color and gradient fills, sized to the box (CSS linear-gradient angles: 0deg up, 90deg right). */
internal fun Fill?.brush(widthPx: Float, heightPx: Float): Brush? = when (this) {
    is Fill.Solid -> SolidColor(color.color())
    is Fill.Linear -> {
        val rad = Math.toRadians(degrees)
        val length = (abs(widthPx * sin(rad)) + abs(heightPx * cos(rad))).toFloat()
        val dx = (sin(rad) * length / 2).toFloat()
        val dy = (-cos(rad) * length / 2).toFloat()
        val c = Offset(widthPx / 2, heightPx / 2)
        Brush.linearGradient(*stops.toStops(), start = c - Offset(dx, dy), end = c + Offset(dx, dy))
    }
    is Fill.Radial -> Brush.radialGradient(
        *stops.toStops(),
        center = Offset(widthPx / 2, heightPx / 2),
        radius = sqrt(widthPx * widthPx / 4 + heightPx * heightPx / 4).coerceAtLeast(1f),
    )
    is Fill.Image, null -> null
}

/**
 * How bright a fill looks (WCAG relative luminance, 0 black to 1 white; gradients average their stops), to pick
 * light or dark system-bar icons over it. Null when it cannot tell (an image without an overlay).
 */
internal fun Fill?.luminance(): Double? = when (this) {
    is Fill.Solid -> color.luminance()
    is Fill.Linear -> stops.map { it.color }.averageLuminance()
    is Fill.Radial -> stops.map { it.color }.averageLuminance()
    is Fill.Image -> overlay.luminance()
    null -> null
}

private fun List<Rgba>.averageLuminance(): Double? = if (isEmpty()) null else map { it.luminance() }.average()

/** Transparent colors are judged over white, the page the screen would sit on. */
private fun Rgba.luminance(): Double {
    fun channel(c: Double): Double {
        val v = (c * a + (1 - a)).coerceIn(0.0, 1.0)
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
}

/** Dark icons (true) are for backgrounds brighter than the point where black and white text contrast equally. */
internal fun Fill?.isLight(dark: Boolean): Boolean = (luminance() ?: if (dark) 0.0 else 1.0) > 0.179

/**
 * The screen's own page background, painted over the whole window (behind the system bars too) so no
 * strip of another color shows around a dark paywall. Without a background it falls back to white or black.
 */
@Composable
internal fun PageBackground(fill: Fill?, dark: Boolean, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val fallback = if (dark) Color.Black else Color.White
    Box(modifier.background(fallback).drawBehind { fill.brush(size.width, size.height)?.let { drawRect(it) } }) {
        if (fill is Fill.Image) {
            val image by rememberImage(fill.url)
            image?.let {
                Image(
                    bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize(),
                    contentScale = if (fill.cover) ContentScale.Crop else ContentScale.Fit,
                    alignment = if (fill.cover) Alignment.Center else Alignment.TopCenter,
                )
            }
            Box(Modifier.fillMaxSize().drawBehind { fill.overlay.brush(size.width, size.height)?.let { drawRect(it) } })
        }
        content()
    }
}

private fun List<GradientStop>.toStops() = map { (it.percent / 100).toFloat().coerceIn(0f, 1f) to it.color.color() }.toTypedArray()

/** A remote image, empty until loaded. */
@Composable
internal fun rememberImage(url: String) = produceState(initialValue = Images.cached(url), url) {
    // The state keeps the last image across a url change: replace it with the new link's, never leave it.
    value = Images.cached(url)
    if (value == null) value = Images.load(url)
}

/** Background, border and shadow of a box. */
@Composable
internal fun BoxPaint(paint: Paint, w: Double, h: Double) {
    val shape = paint.corners.shape()
    val density = LocalDensity.current
    val wPx = with(density) { d(w).toPx() }
    val hPx = with(density) { d(h).toPx() }
    var modifier = Modifier.requiredSize(d(w), d(h))
    paint.shadow?.let {
        modifier = modifier.shadow(elevation = d(it.radius / 2), shape = shape, clip = false, ambientColor = it.color.color(), spotColor = it.color.color())
    }
    val background = paint.background
    if (background is Fill.Image) {
        Box(modifier.clip(shape)) {
            val image by rememberImage(background.url)
            image?.let {
                Image(
                    bitmap = it, contentDescription = null, modifier = Modifier.fillMaxSize(),
                    contentScale = if (background.cover) ContentScale.Crop else ContentScale.Fit,
                    alignment = if (background.cover) Alignment.Center else Alignment.TopCenter,
                )
            }
            background.overlay.brush(wPx, hPx)?.let { Box(Modifier.fillMaxSize().background(it)) }
        }
    } else {
        background.brush(wPx, hPx)?.let { modifier = modifier.background(it, shape) }
        if (paint.borderWidth > 0) paint.borderFill.brush(wPx, hPx)?.let { modifier = modifier.border(d(paint.borderWidth), it, shape) }
        Box(modifier)
        return
    }
    if (paint.borderWidth > 0) paint.borderFill.brush(wPx, hPx)?.let { Box(Modifier.requiredSize(d(w), d(h)).border(d(paint.borderWidth), it, shape)) }
}

/** What nodes call back into. */
internal class RenderActions(val controller: ScreenController, val openUrl: (String) -> Unit)

/** One node at its frame, relative to its parent's origin; children follow. */
@Composable
internal fun NodeContent(node: RenderNode, originX: Double, originY: Double, actions: RenderActions) {
    val f = node.layout.frame ?: return
    var modifier = Modifier
        .offset(d(f.x - originX), d(f.y - originY))
        .requiredSize(d(f.width), d(f.height))
        .alpha(node.paint.opacity.toFloat())
    val action = node.action
    if (action != null && !node.disabled) {
        modifier = modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { actions.controller.handle(action) }
    }
    val sem = node.semantics
    modifier = if (sem.hidden) {
        modifier.clearAndSetSemantics {}
    } else {
        modifier.semantics(mergeDescendants = sem.isButton) {
            if (sem.isButton) role = Role.Button
            sem.label?.let { contentDescription = it }
            sem.selected?.let { selected = it }
            if (sem.disabled) disabled()
        }
    }
    Box(modifier, contentAlignment = Alignment.TopStart) {
        BoxPaint(node.paint, f.width, f.height)
        Leaf(node, f, actions)
        node.connector?.let { c ->
            val height = (f.height - c.top - c.bottomInset).coerceAtLeast(0.0)
            Box(
                Modifier.offset(d(c.centerX - c.width / 2), d(c.top)).requiredSize(d(c.width), d(height))
                    .background(c.fill.brush(c.width.toFloat(), height.toFloat()) ?: SolidColor(Color.Transparent)),
            )
        }
        val carousel = node.carousel
        when {
            carousel != null -> Slider(node, carousel, f, actions)
            node.clipsChildren -> Box(Modifier.requiredSize(d(f.width), d(f.height)).clip(node.paint.corners.shape())) {
                node.children.forEach { NodeContent(it, f.x, f.y, actions) }
            }
            else -> node.children.forEach { NodeContent(it, f.x, f.y, actions) }
        }
        node.badge?.let { NodeContent(it, f.x, f.y, actions) }
    }
}

@Composable
private fun Leaf(node: RenderNode, f: Rect, actions: RenderActions) {
    val l = node.layout
    val innerW = (f.width - l.padding.horizontal - 2 * l.border).coerceAtLeast(0.0)
    val innerH = (f.height - l.padding.vertical - 2 * l.border).coerceAtLeast(0.0)
    val inner = Modifier.offset(d(l.border + l.padding.leading), d(l.border + l.padding.top)).requiredSize(d(innerW), d(innerH))
    val density = LocalDensity.current
    val wPx = with(density) { d(innerW).toPx() }
    val hPx = with(density) { d(innerH).toPx() }
    when (val content = node.content) {
        is Content.Text -> {
            val brush = content.color.takeIf { it !is Fill.Solid }?.brush(wPx, hPx)
            val color = (content.color as? Fill.Solid)?.color?.color() ?: Color.Black
            BasicText(
                text = TextStyles.annotated(content.runs, content.font, actions.openUrl),
                modifier = inner,
                style = TextStyles.style(content.font, content.align, brush, color),
                softWrap = true,
            )
        }
        is Content.Image -> {
            val image by rememberImage(content.url)
            val shape = content.mask.shape()
            Box(
                inner.then(if (content.shadow != null) Modifier.shadow(d(content.shadow!!.radius / 2), shape, clip = false) else Modifier).clip(shape),
            ) {
                image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = if (content.cover) ContentScale.Crop else ContentScale.Fit) }
                content.overlay.brush(wPx, hPx)?.let { Box(Modifier.fillMaxSize().background(it)) }
                if (content.borderWidth > 0) content.borderFill.brush(wPx, hPx)?.let { Box(Modifier.fillMaxSize().border(d(content.borderWidth), it, shape)) }
            }
        }
        is Content.Icon -> {
            val tint = content.color?.approximateColor?.color() ?: Color.Black
            if (content.url.substringBefore('?').endsWith(".svg", ignoreCase = true)) {
                val icon by produceState(Icons.cached(content.url), content.url) {
                    // A selected package swaps its icon file: the previous link's icon must not outlive it.
                    value = Icons.cached(content.url)
                    if (value == null) value = Icons.load(content.url)
                }
                icon?.let { SvgIconCanvas(it, tint, inner) }
            } else {
                val image by rememberImage(content.url)
                image?.let { Image(it, null, inner, contentScale = ContentScale.Fit, colorFilter = ColorFilter.tint(tint, BlendMode.SrcIn)) }
            }
        }
        is Content.Video -> AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    setVideoURI(Uri.parse(content.url))
                    setOnPreparedListener { player ->
                        player.isLooping = content.loop
                        if (content.muted) player.setVolume(0f, 0f)
                        if (content.autoPlay) start()
                    }
                }
            },
            modifier = inner.clip(content.mask.shape()),
        )
        is Content.Toggle -> {
            val thumbX by animateFloatAsState(if (content.on) 22f else 2f, tween(300), label = "thumb")
            Box(Modifier.requiredSize(51.dp, 31.dp).background(content.track.brush(51f, 31f) ?: SolidColor(Color.LightGray), RoundedCornerShape(50))) {
                Box(Modifier.offset(thumbX.dp, 2.dp).size(27.dp).shadow(1.dp, CircleShape).background(content.thumb.brush(27f, 27f) ?: SolidColor(Color.White), CircleShape))
            }
        }
        is Content.TextField -> {
            var value by remember(content.fieldId) { mutableStateOf(content.value) }
            BasicTextField(
                value = value,
                onValueChange = {
                    value = it
                    actions.controller.setText(content.fieldId, it, valid = isValidField(it, content.keyboard))
                },
                modifier = inner.onFocusChanged { state ->
                    if (state.isFocused) actions.controller.focus(content.fieldId)
                    else if (actions.controller.state.focusedField == content.fieldId) actions.controller.blur(content.fieldId, isValidField(value, content.keyboard))
                },
                singleLine = true,
                textStyle = TextStyles.style(content.font, content.align, color = (content.color as? Fill.Solid)?.color?.color() ?: Color.Black),
                keyboardOptions = KeyboardOptions(
                    capitalization = when (content.capitalize) {
                        "sentences" -> KeyboardCapitalization.Sentences
                        "words" -> KeyboardCapitalization.Words
                        "characters" -> KeyboardCapitalization.Characters
                        else -> KeyboardCapitalization.None
                    },
                    keyboardType = when (content.keyboard) {
                        "email" -> KeyboardType.Email
                        "numeric" -> KeyboardType.Number
                        "decimal" -> KeyboardType.Decimal
                        "tel" -> KeyboardType.Phone
                        "url" -> KeyboardType.Uri
                        else -> KeyboardType.Text
                    },
                ),
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            BasicText(
                                content.placeholder,
                                style = TextStyles.style(content.placeholderFont, content.placeholderAlign, color = (content.placeholderColor as? Fill.Solid)?.color?.color() ?: Color.Gray),
                            )
                        }
                        field()
                    }
                },
            )
        }
        null -> {}
    }
}

/** Same check as the web's input types: email, url and numbers must parse; empty is fine. */
internal fun isValidField(value: String, keyboard: String): Boolean {
    val v = value.trim()
    if (v.isEmpty()) return true
    return when (keyboard) {
        "email" -> Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(v)
        "url" -> runCatching { java.net.URI(v).scheme != null }.getOrDefault(false)
        "numeric", "decimal" -> v.toDoubleOrNull() != null
        else -> true
    }
}

/** A carousel's pages, shifted to the current page and dragged by a horizontal swipe. */
@Composable
private fun Slider(node: RenderNode, info: RenderNode.Carousel, f: Rect, actions: RenderActions) {
    val step = (node.children.firstOrNull()?.layout?.frame?.width ?: f.width) + info.pageSpacing
    var drag by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val target = (-info.currentPage * step).toFloat()
    val animated by animateFloatAsState(target, tween(info.transitionMs.toInt()), label = "page")
    LaunchedEffect(info.currentPage, info.autoAdvanceMs) {
        val ms = info.autoAdvanceMs ?: return@LaunchedEffect
        if (ms <= 0 || info.pageCount < 2) return@LaunchedEffect
        delay(ms)
        val next = if (info.loop) (info.currentPage + 1) % info.pageCount else minOf(info.currentPage + 1, info.pageCount - 1)
        if (next != info.currentPage) actions.controller.setCarouselPage(info.key, next)
    }
    Box(
        Modifier
            .requiredSize(d(f.width), d(f.height))
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> drag += delta },
                onDragStopped = { velocity ->
                    val dragDp = with(density) { drag.toDp().value }
                    val moved = when {
                        dragDp < -step / 4 || velocity < -800 -> 1
                        dragDp > step / 4 || velocity > 800 -> -1
                        else -> 0
                    }
                    var page = info.currentPage + moved
                    page = if (info.loop) (page + info.pageCount) % info.pageCount else page.coerceIn(0, info.pageCount - 1)
                    drag = 0f
                    actions.controller.setCarouselPage(info.key, page)
                },
            ),
    ) {
        Box(Modifier.offset { IntOffset((d(animated.toDouble()).toPx() + drag).roundToInt(), 0) }) {
            node.children.forEach { NodeContent(it, f.x, f.y, actions) }
        }
    }
}

/** A whole screen: background, scrolling root, pinned header and footer, sheet, busy spinner. */
@Composable
internal fun ScreenContent(controller: ScreenController, openUrl: (String) -> Unit, busy: Boolean, modifier: Modifier = Modifier) {
    var version by remember(controller) { mutableIntStateOf(0) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    remember(controller) { controller.onChange = { version++ } }
    if (controller.hasCountdown) {
        LaunchedEffect(controller) {
            while (true) {
                delay(1000)
                now = System.currentTimeMillis()
            }
        }
    }
    // Sizes are CSS px = dp, text included: the screen ignores the system font scale, like the web's px.
    val system = LocalDensity.current
    val density = remember(system.density) { Density(system.density, fontScale = 1f) }
    densityHint = density.density
    // The user's text size setting scales every font size (Dynamic Type); capped so layouts stay sane.
    val fontScale = system.fontScale.toDouble().coerceIn(0.85, maxOf(1.0, Miniwall.options.maxFontScale))
    CompositionLocalProvider(LocalDensity provides density) {
        ScreenBody(controller, openUrl, busy, modifier, version, now, fontScale)
    }
}

@Composable
private fun ScreenBody(controller: ScreenController, openUrl: (String) -> Unit, busy: Boolean, modifier: Modifier, version: Int, now: Long, fontScale: Double) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val measurer = remember(textMeasurer, density) { ComposeMeasurer(textMeasurer, density) }
    BoxWithConstraints(modifier) {
        // Inside a scrolling parent (verticalScroll, LazyColumn) the height is unbounded: the screen is as tall as its
        // content then, not "all the space" (which would be infinity).
        val bounded = constraints.hasBoundedHeight
        // Reading the state version re-renders after every tap that changes the screen.
        @Suppress("UNUSED_VARIABLE") val stamp = version
        controller.fontScale = fontScale
        val width = maxWidth.value.toDouble()
        val height = if (bounded) maxHeight.value.toDouble() else controller.intrinsicHeight(width, measurer).coerceAtLeast(1.0)
        val render = controller.render(width, height, measurer, now) ?: return@BoxWithConstraints
        Box(if (bounded) Modifier.fillMaxSize() else Modifier.size(maxWidth, height.dp)) {
            Screen(render, RenderActions(controller, openUrl))
            if (busy) BusyOverlay()
        }
    }
}

/**
 * Dims the screen while a purchase or restore runs: swallows touches (no double taps underneath), spins unless
 * the user turned animations off, and tells TalkBack that something is loading.
 */
@Composable
private fun BusyOverlay() {
    val context = Miniwall.appContext
    // "Remove animations" / reduced motion sets the animator duration scale to 0.
    val animated = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
    val angle = if (animated) {
        rememberInfiniteTransition(label = "busy").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "busy-angle").value
    } else {
        0f
    }
    Box(
        Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.6f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "Loading"
            },
        contentAlignment = Alignment.Center,
    ) {
        // On its own opaque card: the spinner never sits on top of a button label or other text, and the
        // screen underneath (so the buy button's size) does not change while busy.
        Box(Modifier.shadow(6.dp, RoundedCornerShape(16.dp)).background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp)) {
            Canvas(Modifier.size(32.dp)) {
                val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                val inset = stroke.width / 2
                val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke.width, size.height - stroke.width)
                drawArc(Color.Black.copy(alpha = 0.15f), 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = stroke)
                drawArc(Color.Black.copy(alpha = 0.7f), angle - 90f, 90f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = stroke)
            }
        }
    }
}

@Composable
private fun Screen(render: ScreenRender, actions: RenderActions) {
    Box(Modifier.requiredSize(d(render.width), d(render.height))) {
        render.background?.let { BoxPaint(Paint(background = it), render.width, render.height) }
        val root = render.root
        val rf = render.rootFrame
        Box(Modifier.offset(d(rf.x), d(rf.y)).requiredSize(d(rf.width), d(rf.height))) {
            BoxPaint(root.paint, rf.width, rf.height)
            Box(Modifier.requiredSize(d(rf.width), d(rf.height)).verticalScroll(rememberScrollState(), enabled = render.contentHeight > rf.height + 0.5)) {
                Box(Modifier.requiredSize(d(rf.width), d(render.contentHeight))) {
                    root.children.forEach { NodeContent(it, rf.x, rf.y, actions) }
                }
            }
        }
        render.header?.let { NodeContent(it, 0.0, 0.0, actions) }
        render.footer?.let { NodeContent(it, 0.0, 0.0, actions) }
        render.sheet?.let { sheet ->
            Box(
                Modifier.requiredSize(d(render.width), d(render.height)).background(Color.Black.copy(alpha = 0.25f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { actions.controller.closeSheet() },
            )
            NodeContent(sheet, 0.0, 0.0, actions)
        }
    }
}

/** An SVG icon drawn like CSS `contain`: scaled to fit the frame, centered, in one color. */
@Composable
internal fun SvgIconCanvas(icon: SvgIcon, color: Color, modifier: Modifier) {
    val paths = remember(icon) {
        icon.shapes.map { shape ->
            androidx.compose.ui.graphics.Path().also { path ->
                path.fillType = if (shape.evenOdd) androidx.compose.ui.graphics.PathFillType.EvenOdd else androidx.compose.ui.graphics.PathFillType.NonZero
                for (op in shape.ops) when (op) {
                    is PathOp.Move -> path.moveTo(op.x.toFloat(), op.y.toFloat())
                    is PathOp.Line -> path.lineTo(op.x.toFloat(), op.y.toFloat())
                    is PathOp.Cubic -> path.cubicTo(op.x1.toFloat(), op.y1.toFloat(), op.x2.toFloat(), op.y2.toFloat(), op.x.toFloat(), op.y.toFloat())
                    PathOp.Close -> path.close()
                }
            }
        }
    }
    androidx.compose.foundation.Canvas(modifier) {
        val factor = minOf(size.width / icon.width.toFloat(), size.height / icon.height.toFloat())
        val dx = (size.width - icon.width.toFloat() * factor) / 2 - icon.minX.toFloat() * factor
        val dy = (size.height - icon.height.toFloat() * factor) / 2 - icon.minY.toFloat() * factor
        withTransform({ translate(dx, dy); scale(factor, factor, Offset.Zero) }) {
            icon.shapes.forEachIndexed { i, shape ->
                if (shape.fill) drawPath(paths[i], color)
                if (shape.stroke) {
                    drawPath(
                        paths[i], color,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = shape.strokeWidth.toFloat(),
                            cap = if (shape.roundCap) androidx.compose.ui.graphics.StrokeCap.Round else androidx.compose.ui.graphics.StrokeCap.Butt,
                            join = if (shape.roundJoin) androidx.compose.ui.graphics.StrokeJoin.Round else androidx.compose.ui.graphics.StrokeJoin.Miter,
                        ),
                    )
                }
            }
        }
    }
}
