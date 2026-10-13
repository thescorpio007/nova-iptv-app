package com.nova.iptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A text box that works well with a TV remote:
 *  - the arrows only pass over it (it lights up, "OK = type");
 *  - OK starts typing (keyboard opens, "✎ Typing · Back = done");
 *  - Back / Done ends typing and stays on the box; ▲ ▼ end typing and move on;
 *  - ▶ reaches the small buttons inside (📋 Paste …), ◀ comes back.
 * With a touch screen (phone) a tap edits directly, as before.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
) {
    val remote = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val outer = remember { FocusRequester() }
    val inner = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var lit by remember { mutableStateOf(false) }
    // The box had the keyboard focus during this typing session (losing it then ends typing).
    var innerHad by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    val green = Color(0xFF4CD787)

    fun stop() {
        keyboard?.hide()
        stopping = true
        runCatching { outer.requestFocus() }
        editing = false
        innerHad = false
    }

    LaunchedEffect(editing) {
        if (editing) {
            // The box becomes typeable on this frame: give it the focus (retry a few frames).
            repeat(5) {
                if (runCatching { inner.requestFocus() }.isSuccess) { keyboard?.show(); return@LaunchedEffect }
                kotlinx.coroutines.delay(30)
            }
        }
    }
    if (editing) BackHandler { stop() }

    val actions = KeyboardActions(
        onDone = { stop() },
        onGo = { stop() },
        onSearch = { stop() },
        onSend = { stop() },
        // Remote: to the next item (OK types there). Touch: the next box starts typing by itself.
        onNext = { stop(); focus.moveFocus(FocusDirection.Down) },
    )

    Box(
        Modifier
            .focusRequester(outer)
            // A tap / mouse click on the box starts typing straight away (also right after using the arrows).
            .pointerInput(enabled) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (enabled && !editing) editing = true
                }
            }
            .onFocusChanged {
                lit = it.isFocused
                // Touch: reaching the box with "Next" on the keyboard starts typing in it.
                if (it.isFocused && !remote && !stopping && enabled) editing = true
                if (it.isFocused) stopping = false
            }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when {
                    // OK on the lit box: start typing.
                    lit && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) && enabled -> {
                        editing = true; true
                    }
                    // ▶ from the lit box: the small buttons inside (Paste…).
                    lit && e.key == Key.DirectionRight && trailingIcon != null -> focus.moveFocus(FocusDirection.Enter)
                    // ◀ from a small button inside: back to the box.
                    !lit && !editing && e.key == Key.DirectionLeft -> runCatching { outer.requestFocus() }.isSuccess
                    else -> false
                }
            }
            .focusable(enabled = enabled),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier
                .focusRequester(inner)
                // Typeable only while typing: the keys of a remote or air-mouse keyboard can't switch it off midway.
                .focusProperties { canFocus = editing }
                .onFocusChanged {
                    if (it.isFocused) innerHad = true
                    else if (innerHad && editing && !it.hasFocus) { innerHad = false; editing = false; keyboard?.hide() }
                }
                .onPreviewKeyEvent { e ->
                    if (!editing || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Back, Key.Escape -> { stop(); true }
                        Key.DirectionUp -> { stop(); focus.moveFocus(FocusDirection.Up); true }
                        Key.DirectionDown -> { stop(); focus.moveFocus(FocusDirection.Down); true }
                        else -> false
                    }
                }
                .then(
                    when {
                        editing -> Modifier.border(2.dp, green, RoundedCornerShape(6.dp))
                        lit -> Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                        else -> Modifier
                    },
                ),
            label = label,
            placeholder = placeholder,
            trailingIcon = trailingIcon,
            singleLine = singleLine,
            maxLines = maxLines,
            enabled = enabled,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = actions,
        )
        if (remote && (lit || editing)) {
            Text(
                if (editing) "✎ Typing · Back = done" else "OK = type",
                color = if (editing) Color(0xFF0B2A18) else MaterialTheme.colorScheme.onPrimary,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-10).dp, y = (-4).dp)
                    .background(if (editing) green else MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
    }
}
