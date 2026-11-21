package com.krankmili.buttonbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlin.math.abs
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krankmili.buttonbox.data.BoxButton
import com.krankmili.buttonbox.data.BoxGroup
import com.krankmili.buttonbox.data.BoxPrompt
import com.krankmili.buttonbox.data.TruckStatus

object Palette {
    val Background = Color(0xFF0E1013)
    val Panel = Color(0xFF151920)
    val PanelBorder = Color(0xFF262B33)
    val Key = Color(0xFF1E232B)
    val KeyBorder = Color(0xFF2D343E)
    val Text = Color(0xFFE8EAED)
    val Muted = Color(0xFFA3ADB8)
    val Accent = Color(0xFFFFB020)
    val OnAccent = Color(0xFF1A1200)
    val Good = Color(0xFF3DD6A0)
    val Bad = Color(0xFFFF6B5E)
}

private const val SCREEN_COLUMNS = 4

@Composable
fun BoxScreen(
    allGroups: List<BoxGroup>,
    truck: TruckStatus?,
    connected: Boolean,
    statusText: String,
    onDisconnect: () -> Unit,
    onPress: (String) -> Unit,
    onRelease: (String) -> Unit,
    voiceOn: Boolean = false,
    voiceStatus: String = "",
    onVoice: () -> Unit = {},
    speechLabel: String = "",
    onSpeech: () -> Unit = {},
    profiles: List<String> = emptyList(),
    profile: String = "",
    onProfile: (String) -> Unit = {},
) {
    val pages = allGroups.map { it.page }.distinct().sorted()
    var page by remember { mutableStateOf(1) }
    val activePage = if (page in pages) page else pages.firstOrNull() ?: 1
    val layout = allGroups.filter { it.page == activePage }
    // A button may carry a question; after it is pressed the tablet asks, and Yes/No press other buttons.
    var prompt by remember { mutableStateOf<BoxPrompt?>(null) }
    val press: (String) -> Unit = { id ->
        onPress(id)
        allGroups.asSequence().flatMap { it.buttons.asSequence() }.firstOrNull { it.id == id }?.prompt?.let { prompt = it }
    }
    prompt?.let { p ->
        AlertDialog(
            onDismissRequest = { prompt = null },
            containerColor = Palette.Panel,
            text = { Text(p.text, color = Palette.Text) },
            confirmButton = {
                TextButton(onClick = { prompt = null; onPress(p.yesId) }) { Text("Evet", color = Palette.Accent) }
            },
            dismissButton = {
                TextButton(onClick = { prompt = null; if (p.noId.isNotEmpty()) onPress(p.noId) }) { Text("Hayır", color = Palette.Muted) }
            },
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Tablets keep the full 4-column wall; phones (short or narrow screens) get tabs.
        val compact = maxHeight < 480.dp || maxWidth < 640.dp
        val pad = if (compact) 8.dp else 16.dp
        val gap = if (compact) 8.dp else 12.dp
        var tab by remember { mutableStateOf(1) }
        // A numpad-style page (several one-column panels of keys) stays one screen even on phones.
        val padPage = compact && layout.count { it.columns == 1 && it.buttons.size >= 4 } >= 3 && layout.all { it.columns == 1 }
        val slop = LocalViewConfiguration.current.touchSlop
        val offset = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val widthPx = constraints.maxWidth.toFloat()
        val pageContent: @Composable (Int) -> Unit = { shownPage ->
            val layout = allGroups.filter { it.page == shownPage }
            val padPage = compact && layout.count { it.columns == 1 && it.buttons.size >= 4 } >= 3 && layout.all { it.columns == 1 }
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    if (compact && !padPage) {
                        val columns = (1..SCREEN_COLUMNS).filter { c -> layout.any { it.column == c } }
                        val active = if (tab in columns) tab else columns.firstOrNull() ?: 1
                        TabRow(columns, active, layout) { tab = it }
                        Row(
                            Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(gap),
                        ) {
                            val tabGroups = layout.filter { it.column == active }
                            tabGroups.forEach { group ->
                                Panel(
                                    group = group,
                                    truck = truck,
                                    connected = connected,
                                    onPress = press,
                                    onRelease = onRelease,
                                    compact = true,
                                    wide = tabGroups.size == 1,
                                    modifier = Modifier.weight(group.weight.coerceAtLeast(0.1f)).fillMaxHeight(),
                                )
                            }
                        }
                    } else {
                        Row(
                            Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(gap),
                        ) {
                            for (col in 1..SCREEN_COLUMNS) {
                                Column(
                                    Modifier.weight(1f).fillMaxHeight(),
                                    verticalArrangement = Arrangement.spacedBy(gap),
                                ) {
                                    layout.filter { it.column == col }.forEach { group ->
                                        Panel(
                                            group = group,
                                            truck = truck,
                                            connected = connected,
                                            onPress = press,
                                            onRelease = onRelease,
                                            compact = compact,
                                            modifier = Modifier.weight(group.weight.coerceAtLeast(0.1f)).fillMaxWidth(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
        }
        Column(
            Modifier.fillMaxSize()
                // Swipe right = next page, swipe left = previous page. Watched in the Initial pass so it also
                // works when the finger starts on a button; once it is clearly horizontal the touch is consumed,
                // which cancels the button under the finger (tap buttons only fire on release).
                .pointerInput(pages, activePage) {
                    if (pages.size < 2) return@pointerInput
                    val threshold = minOf(size.width * 0.22f, 160.dp.toPx())
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var dx = 0f
                        var dy = 0f
                        var dragging = false
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            val d = ch.positionChange()
                            dx += d.x
                            dy += d.y
                            if (!dragging && abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) dragging = true
                            if (dragging) {
                                // Consuming cancels the button under the finger (tap buttons only fire on release).
                                ev.changes.forEach { it.consume() }
                                val i = pages.indexOf(activePage)
                                val n = if (dx > 0) i + 1 else i - 1
                                val shown = if (n in pages.indices) dx else dx * 0.25f   // rubber band at the ends
                                scope.launch { offset.snapTo(shown) }
                            }
                        }
                        if (dragging) {
                            val i = pages.indexOf(activePage)
                            val dir = if (dx > 0) 1 else -1
                            val n = i + dir
                            val w = size.width.toFloat()
                            if (abs(dx) > threshold && n in pages.indices) {
                                scope.launch {
                                    offset.animateTo(dir * w, tween(200))
                                    page = pages[n]; tab = 1
                                    offset.snapTo(0f)
                                }
                            } else {
                                scope.launch { offset.animateTo(0f, spring()) }
                            }
                        }
                    }
                }
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            Header(connected, statusText, onDisconnect, compact, pages, activePage, voiceOn, voiceStatus, onVoice, speechLabel, onSpeech, profiles, profile, onProfile) { target ->
                // Chips slide the same way: a higher page enters from the left.
                val dir = if (target > activePage) 1 else -1
                scope.launch {
                    val w = widthPx
                    offset.animateTo(dir * w, tween(220))
                    page = target; tab = 1
                    offset.snapTo(0f)
                }
            }
            // The page follows the finger; the neighbouring page sits right next to it, so there is no gap while sliding.
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                Box(Modifier.fillMaxSize().graphicsLayer { translationX = offset.value }) { pageContent(activePage) }
                val shift = offset.value
                if (shift != 0f) {
                    val dir = if (shift > 0f) 1 else -1
                    val n = pages.indexOf(activePage) + dir
                    if (n in pages.indices) {
                        Box(Modifier.fillMaxSize().graphicsLayer { translationX = shift - dir * size.width }) { pageContent(pages[n]) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabRow(columns: List<Int>, active: Int, layout: List<BoxGroup>, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        columns.forEach { c ->
            val selected = c == active
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(if (selected) Palette.Accent else Palette.Panel)
                    .border(1.dp, if (selected) Palette.Accent else Palette.PanelBorder, shape)
                    .clickable { onSelect(c) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    layout.filter { it.column == c }.joinToString(" · ") { it.title }.uppercase(),
                    color = if (selected) Palette.OnAccent else Palette.Text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun Header(
    connected: Boolean,
    statusText: String,
    onDisconnect: () -> Unit,
    compact: Boolean,
    pages: List<Int>,
    activePage: Int,
    voiceOn: Boolean,
    voiceStatus: String,
    onVoice: () -> Unit,
    speechLabel: String,
    onSpeech: () -> Unit,
    profiles: List<String>,
    profile: String,
    onProfile: (String) -> Unit,
    onPage: (Int) -> Unit,
) {
    // Eye button hides the PC address (e.g. while streaming); the part after " · " is masked.
    var showIp by rememberSaveable { mutableStateOf(true) }
    val shownText = if (showIp) statusText else statusText.substringBefore(" · ", statusText).let {
        if (it == statusText) statusText else "$it · •••"
    }
    Row(
        Modifier.fillMaxWidth().height(if (compact) 36.dp else 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            buildAnnotatedString {
                append("KRANKMILI & O.K ")
                withStyle(SpanStyle(color = Palette.Accent)) { append("BUTTON BOX") }
            },
            color = Palette.Text,
            fontSize = if (compact) 15.sp else 26.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = if (compact) 1.sp else 3.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (pages.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pages.forEach { p ->
                        val selected = p == activePage
                        Box(
                            Modifier
                                .size(if (compact) 30.dp else 40.dp)
                                .clip(CircleShape)
                                .background(if (selected) Palette.Accent else Palette.Panel)
                                .border(1.dp, if (selected) Palette.Accent else Palette.PanelBorder, CircleShape)
                                .clickable { onPage(p) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$p",
                                color = if (selected) Palette.OnAccent else Palette.Text,
                                fontSize = if (compact) 13.sp else 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Palette.Panel)
                    .border(1.dp, Palette.PanelBorder, RoundedCornerShape(50))
                    .padding(horizontal = if (compact) 10.dp else 16.dp, vertical = if (compact) 4.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(10.dp).clip(CircleShape)
                        .background(if (connected) Palette.Good else Palette.Bad),
                )
                Text(shownText, color = Palette.Text, fontSize = if (compact) 12.sp else 15.sp)
                SvgIcon(
                    if (showIp) "eye" else "eye_off",
                    if (compact) 18.dp else 22.dp,
                    Palette.Muted,
                    Modifier.clickable { showIp = !showIp },
                )
            }
            // Voice control: "hey krank <command>". Off by default; the last heard phrase is shown next to the mic.
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (voiceOn) Palette.Accent else Palette.Panel)
                    .border(1.dp, if (voiceOn) Palette.Accent else Palette.PanelBorder, RoundedCornerShape(50))
                    .clickable { onVoice() }
                    .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 4.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SvgIcon(if (voiceOn) "mic" else "mic_off", if (compact) 18.dp else 22.dp, if (voiceOn) Palette.OnAccent else Palette.Muted)
                if (voiceOn && voiceStatus.isNotEmpty() && !compact) {
                    Text(voiceStatus, color = Palette.OnAccent, fontSize = 13.sp, maxLines = 1)
                }
            }
            // Spoken feedback: tap cycles Kadın ses / Erkek ses / Ses kapalı.
            if (speechLabel.isNotEmpty()) {
                Text(
                    if (compact) speechLabel.substringBefore(' ') else speechLabel,
                    color = if (speechLabel.endsWith("kapalı")) Palette.Muted else Palette.Text,
                    fontSize = if (compact) 11.sp else 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Palette.Panel)
                        .border(1.dp, Palette.PanelBorder, RoundedCornerShape(50))
                        .clickable { onSpeech() }
                        .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 4.dp else 8.dp),
                )
            }
            // Key-set profile (e.g. ETS2 / ATS), only when the PC config defines two or more.
            if (profiles.size > 1) {
                Text(
                    profile.ifEmpty { profiles.first() },
                    color = Palette.OnAccent,
                    fontSize = if (compact) 11.sp else 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Palette.Accent)
                        .clickable { onProfile(profiles[(profiles.indexOf(profile).coerceAtLeast(0) + 1) % profiles.size]) }
                        .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 4.dp else 8.dp),
                )
            }
            OutlinedButton(
                onClick = onDisconnect,
                contentPadding = if (compact) PaddingValues(horizontal = 12.dp, vertical = 0.dp) else ButtonDefaults.ContentPadding,
                modifier = if (compact) Modifier.height(30.dp) else Modifier,
            ) { Text("Kes", color = Palette.Text, fontSize = if (compact) 12.sp else 14.sp) }
        }
    }
}

@Composable
private fun Panel(
    group: BoxGroup,
    truck: TruckStatus?,
    connected: Boolean,
    onPress: (String) -> Unit,
    onRelease: (String) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    val shape = RoundedCornerShape(if (compact) 16.dp else 22.dp)
    Column(
        modifier
            .clip(shape)
            .background(Palette.Panel)
            .border(1.dp, Palette.PanelBorder, shape)
            .padding(if (compact) 8.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp),
    ) {
        if (group.title.isNotBlank() && (!compact || group.kind == "status" || group.buttons.size > 6)) {
            Text(
                group.title.uppercase(),
                color = Palette.Muted,
                fontSize = if (compact) 12.sp else 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp,
            )
        }
        if (group.kind == "status") {
            StatusPanel(truck, compact, Modifier.weight(1f).fillMaxWidth())
        } else {
            ButtonGrid(group, connected, compact, wide, onPress, onRelease, Modifier.weight(1f).fillMaxWidth(), truck)
        }
    }
}

/** Splits buttons into rows so that each row's spans add up to at most [columns]. */
private fun buildRows(buttons: List<BoxButton>, columns: Int): List<List<BoxButton>> {
    val rows = mutableListOf<List<BoxButton>>()
    var current = mutableListOf<BoxButton>()
    var used = 0
    for (button in buttons) {
        val span = button.span.coerceAtMost(columns)
        if (used + span > columns) {
            rows += current
            current = mutableListOf()
            used = 0
        }
        current += button
        used += span
        if (used == columns) {
            rows += current
            current = mutableListOf()
            used = 0
        }
    }
    if (current.isNotEmpty()) rows += current
    return rows
}

@Composable
private fun ButtonGrid(
    group: BoxGroup,
    connected: Boolean,
    compact: Boolean,
    wide: Boolean,
    onPress: (String) -> Unit,
    onRelease: (String) -> Unit,
    modifier: Modifier = Modifier,
    truck: TruckStatus? = null,
) {
    // A phone tab with a single panel is wide: ignore spans and use up to 4 tall columns.
    val reflow = compact && wide && group.columns > 1
    val cols = if (reflow) minOf(4, group.buttons.size) else group.columns
    val rows = remember(group, reflow) {
        buildRows(if (reflow) group.buttons.map { it.copy(span = 1) } else group.buttons, cols)
    }
    val roomy = group.columns == 1 && group.buttons.size <= 2 && !(compact && wide)
    val gap = if (compact) 6.dp else 10.dp
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        rows.forEach { row ->
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                row.forEach { button ->
                    KeyButton(
                        button = button,
                        enabled = connected,
                        roomy = roomy,
                        compact = compact,
                        active = button.state.isNotEmpty() && truck?.states?.get(button.state) == 1,
                        onPress = onPress,
                        onRelease = onRelease,
                        modifier = Modifier
                            .weight(button.span.coerceAtMost(cols).toFloat())
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyButton(
    button: BoxButton,
    enabled: Boolean,
    roomy: Boolean,
    compact: Boolean,
    active: Boolean = false,
    onPress: (String) -> Unit,
    onRelease: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(if (compact) 12.dp else 16.dp)
    val background = if (pressed) Palette.Accent else Palette.Key
    val foreground = if (pressed) Palette.OnAccent else Palette.Text
    val iconColor = when {
        pressed -> Palette.OnAccent
        active -> Palette.Accent
        button.accent -> Palette.Accent
        else -> Palette.Text
    }

    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(background)
            .border(if (active) 2.dp else 1.dp, if (pressed || active) Palette.Accent else Palette.KeyBorder, shape)
            // Re-keying on `enabled` cancels an in-flight press when the link drops.
            .pointerInput(button.id, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    pressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (button.mode == "hold") {
                        onPress(button.id)
                        // false = gesture cancelled (finger slid off); release is sent either way
                        // so a "hold" key (windows, selektör) can never stay stuck down.
                        tryAwaitRelease()
                        pressed = false
                        onRelease(button.id)
                    } else {
                        // Tap keys fire when the finger lifts, so a page swipe that starts on a button does not press it.
                        val completed = tryAwaitRelease()
                        pressed = false
                        if (completed) {
                            onPress(button.id)
                            onRelease(button.id)
                        }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        val gapPx = with(LocalDensity.current) { (if (compact) 1.dp else 4.dp).roundToPx() }
        // Icon, label and sub-label are stacked and centred; whatever does not fit is dropped
        // (sub-label first, then icon) instead of being cut in half at the button edge.
        Layout(
            modifier = Modifier.fillMaxSize().padding(if (compact) 3.dp else 6.dp),
            content = {
                if (button.icon.isNotEmpty()) {
                    Box(Modifier.layoutId("icon")) {
                        SvgIcon(button.icon, if (compact) (if (roomy) 40.dp else 28.dp) else if (roomy) 64.dp else 32.dp, iconColor)
                    }
                }
                // A single long word (SELEKTÖR) must never break mid-word: keep it on one line and
                // shrink the font until it fits the button width.
                val singleWord = !button.label.trim().contains(' ')
                var shrink by remember(button.label, compact, roomy) { mutableStateOf(1f) }
                val baseSize = if (compact) (if (roomy) 18f else 15f) else if (roomy) 24f else 20f
                Text(
                    button.label.uppercase(),
                    modifier = Modifier.layoutId("label"),
                    color = foreground,
                    fontSize = (baseSize * shrink).sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    lineHeight = ((if (compact) (if (roomy) 20f else 17f) else if (roomy) 26f else 22f) * shrink).sp,
                    softWrap = !singleWord,
                    maxLines = if (singleWord) 1 else Int.MAX_VALUE,
                    onTextLayout = { if (singleWord && it.didOverflowWidth && shrink > 0.5f) shrink *= 0.92f },
                )
                if (button.sub.isNotEmpty() && !compact) {
                    Text(
                        button.sub,
                        modifier = Modifier.layoutId("sub"),
                        color = if (pressed) Palette.OnAccent else Palette.Muted,
                        fontSize = 13.sp,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
            },
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val icon = measurables.firstOrNull { it.layoutId == "icon" }?.measure(loose)
            val label = measurables.first { it.layoutId == "label" }.measure(loose)
            val sub = measurables.firstOrNull { it.layoutId == "sub" }?.measure(loose)
            fun total(vararg parts: androidx.compose.ui.layout.Placeable?): Int {
                val used = parts.filterNotNull()
                return used.sumOf { it.height } + gapPx * (used.size - 1).coerceAtLeast(0)
            }
            var showSub = sub != null
            var showIcon = icon != null
            if (total(icon, label, sub) > constraints.maxHeight) showSub = false
            if (total(icon, label, if (showSub) sub else null) > constraints.maxHeight) showIcon = false
            val parts = listOfNotNull(if (showIcon) icon else null, label, if (showSub) sub else null)
            layout(constraints.maxWidth, constraints.maxHeight) {
                var y = (constraints.maxHeight - total(*parts.toTypedArray())) / 2
                parts.forEach { part ->
                    part.placeRelative((constraints.maxWidth - part.width) / 2, y)
                    y += part.height + gapPx
                }
            }
        }
    }
}

@Composable
private fun StatusPanel(t: TruckStatus?, compact: Boolean, modifier: Modifier = Modifier) {
    val live = t != null && t.gameRunning
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 10.dp)) {
        if (live && t != null) {
            val fuelFrac = if (t.fuelCapacity > 0f) (t.fuelLiters / t.fuelCapacity).coerceIn(0f, 1f) else 0f
            StatusRow("Yakıt", "${t.fuelLiters.toInt()} L · ${t.rangeKm.toInt()} km", fuelFrac, goodWhenHigh = true)
            StatusRow("Motor", "%${(t.wearEngine * 100).toInt()} hasar", t.wearEngine, goodWhenHigh = false)
            StatusRow("Şanzıman", "%${(t.wearTransmission * 100).toInt()} hasar", t.wearTransmission, goodWhenHigh = false)
            StatusRow("Şasi", "%${(t.wearChassis * 100).toInt()} hasar", t.wearChassis, goodWhenHigh = false)
            StatusRow("Tekerlek", "%${(t.wearWheels * 100).toInt()} hasar", t.wearWheels, goodWhenHigh = false)
        } else {
            StatusRow("Yakıt", "—", 0f, true)
            StatusRow("Motor", "—", 0f, false)
            StatusRow("Şanzıman", "—", 0f, false)
            StatusRow("Şasi", "—", 0f, false)
            StatusRow("Tekerlek", "—", 0f, false)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Background)
                .border(1.dp, Palette.PanelBorder, RoundedCornerShape(14.dp))
                .padding(if (compact) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val cargoText = when {
                !live || t == null -> "—"
                t.cargoLoaded && t.cargo.isNotBlank() -> "${t.cargo} · ${(t.cargoKg / 1000f).let { "%.1f".format(it) }} t"
                else -> "Yük yok"
            }
            val hasRoute = live && t != null && t.cargoLoaded && t.destination.isNotBlank()
            // alternate cargo / destination every 3 s; only this small block recomposes
            var showRoute by remember { mutableStateOf(false) }
            LaunchedEffect(hasRoute) {
                showRoute = false
                while (hasRoute) {
                    delay(3000)
                    showRoute = !showRoute
                }
            }
            val routeShown = hasRoute && showRoute && t != null
            Text(if (routeShown) "VARIŞ" else "YÜK", color = Palette.Muted, fontSize = 13.sp, letterSpacing = 2.sp)
            Text(
                if (routeShown && t != null) "${t.destination} · ${(t.routeKm / 1000f).toInt()} km kaldı" else cargoText,
                color = Palette.Text,
                fontSize = if (compact) 15.sp else 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        if (!live) {
            Text(
                if (t == null) "PC'den veri bekleniyor…" else "ETS2 açık değil ya da telemetri eklentisi yüklü değil.",
                color = Palette.Muted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, fraction: Float, goodWhenHigh: Boolean) {
    val f = fraction.coerceIn(0f, 1f)
    val color = if (goodWhenHigh) {
        if (f > 0.25f) Palette.Good else Palette.Bad
    } else {
        if (f < 0.5f) Palette.Good else Palette.Bad
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Palette.Muted, fontSize = 14.sp)
            Text(value, color = Palette.Text, fontSize = 14.sp)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Palette.Background),
        ) {
            Box(Modifier.fillMaxWidth(f).height(6.dp).background(color))
        }
    }
}
