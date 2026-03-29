package sh.haven.core.wayland

import android.annotation.SuppressLint
import android.view.KeyEvent as AndroidKeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Composable that displays the native Wayland compositor output
 * and forwards touch + keyboard input to the compositor.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun WaylandDesktopView(modifier: Modifier = Modifier) {
    DisposableEffect(Unit) {
        onDispose {
            WaylandBridge.nativeSetSurface(null)
        }
    }

    android.util.Log.d("WaylandDesktopView", "Composable rendered, isRunning=${WaylandBridge.nativeIsRunning()}")

    AndroidView(
        factory = { context ->
            object : SurfaceView(context) {
                init {
                    isFocusable = true
                    isFocusableInTouchMode = true

                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            WaylandBridge.nativeSetSurface(holder.surface)
                        }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, h2: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            WaylandBridge.nativeSetSurface(null)
                        }
                    })

                    setOnTouchListener { view, event ->
                        // Request focus on first touch to bring up keyboard
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            if (!hasFocus()) {
                                requestFocus()
                            }
                            val imm = context.getSystemService(
                                android.content.Context.INPUT_METHOD_SERVICE
                            ) as android.view.inputmethod.InputMethodManager
                            imm.restartInput(this)
                            imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
                            android.util.Log.d("WaylandInput", "Touch DOWN, hasFocus=${hasFocus()}, isTextEditor=${onCheckIsTextEditor()}")
                        }
                        val nx = event.x / view.width
                        val ny = event.y / view.height
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN ->
                                WaylandBridge.nativeSendTouch(0, nx, ny)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                WaylandBridge.nativeSendTouch(1, nx, ny)
                            MotionEvent.ACTION_MOVE ->
                                WaylandBridge.nativeSendTouch(2, nx, ny)
                        }
                        true
                    }
                }

                override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
                    outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT
                    outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                        EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    val view = this
                    return object : BaseInputConnection(view, false) {
                        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                            android.util.Log.d("WaylandInput", "commitText: '$text'")
                            text?.forEach { ch ->
                                val evdev = charToEvdev(ch)
                                android.util.Log.d("WaylandInput", "char='$ch' evdev=$evdev")
                                if (evdev >= 0) {
                                    WaylandBridge.nativeSendKey(evdev, 1)
                                    WaylandBridge.nativeSendKey(evdev, 0)
                                }
                            }
                            return true
                        }

                        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                            repeat(beforeLength) {
                                WaylandBridge.nativeSendKey(14, 1) // KEY_BACKSPACE
                                WaylandBridge.nativeSendKey(14, 0)
                            }
                            return true
                        }

                        override fun sendKeyEvent(event: AndroidKeyEvent): Boolean {
                            val evdev = androidToEvdev(event.keyCode)
                            if (evdev >= 0) {
                                val pressed = if (event.action == AndroidKeyEvent.ACTION_DOWN) 1 else 0
                                WaylandBridge.nativeSendKey(evdev, pressed)
                                return true
                            }
                            return super.sendKeyEvent(event)
                        }
                    }
                }

                override fun onCheckIsTextEditor(): Boolean = true

                override fun onKeyDown(keyCode: Int, event: AndroidKeyEvent?): Boolean {
                    val evdev = androidToEvdev(keyCode)
                    if (evdev >= 0) {
                        WaylandBridge.nativeSendKey(evdev, 1)
                        return true
                    }
                    return super.onKeyDown(keyCode, event)
                }

                override fun onKeyUp(keyCode: Int, event: AndroidKeyEvent?): Boolean {
                    val evdev = androidToEvdev(keyCode)
                    if (evdev >= 0) {
                        WaylandBridge.nativeSendKey(evdev, 0)
                        return true
                    }
                    return super.onKeyUp(keyCode, event)
                }

                override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: AndroidKeyEvent?): Boolean {
                    // Soft keyboard sends characters via KEY_MULTIPLE
                    val chars = event?.characters ?: return super.onKeyMultiple(keyCode, repeatCount, event)
                    for (ch in chars) {
                        val evdev = charToEvdev(ch)
                        if (evdev >= 0) {
                            WaylandBridge.nativeSendKey(evdev, 1)
                            WaylandBridge.nativeSendKey(evdev, 0)
                        }
                    }
                    return true
                }
            }
        },
        modifier = modifier,
    )
}

/** Map Android KeyEvent keyCode to Linux evdev scancode (input-event-codes.h). */
private fun androidToEvdev(keyCode: Int): Int = when (keyCode) {
    AndroidKeyEvent.KEYCODE_A -> 30
    AndroidKeyEvent.KEYCODE_B -> 48
    AndroidKeyEvent.KEYCODE_C -> 46
    AndroidKeyEvent.KEYCODE_D -> 32
    AndroidKeyEvent.KEYCODE_E -> 18
    AndroidKeyEvent.KEYCODE_F -> 33
    AndroidKeyEvent.KEYCODE_G -> 34
    AndroidKeyEvent.KEYCODE_H -> 35
    AndroidKeyEvent.KEYCODE_I -> 23
    AndroidKeyEvent.KEYCODE_J -> 36
    AndroidKeyEvent.KEYCODE_K -> 37
    AndroidKeyEvent.KEYCODE_L -> 38
    AndroidKeyEvent.KEYCODE_M -> 50
    AndroidKeyEvent.KEYCODE_N -> 49
    AndroidKeyEvent.KEYCODE_O -> 24
    AndroidKeyEvent.KEYCODE_P -> 25
    AndroidKeyEvent.KEYCODE_Q -> 16
    AndroidKeyEvent.KEYCODE_R -> 19
    AndroidKeyEvent.KEYCODE_S -> 31
    AndroidKeyEvent.KEYCODE_T -> 20
    AndroidKeyEvent.KEYCODE_U -> 22
    AndroidKeyEvent.KEYCODE_V -> 47
    AndroidKeyEvent.KEYCODE_W -> 17
    AndroidKeyEvent.KEYCODE_X -> 45
    AndroidKeyEvent.KEYCODE_Y -> 21
    AndroidKeyEvent.KEYCODE_Z -> 44
    AndroidKeyEvent.KEYCODE_0 -> 11
    AndroidKeyEvent.KEYCODE_1 -> 2
    AndroidKeyEvent.KEYCODE_2 -> 3
    AndroidKeyEvent.KEYCODE_3 -> 4
    AndroidKeyEvent.KEYCODE_4 -> 5
    AndroidKeyEvent.KEYCODE_5 -> 6
    AndroidKeyEvent.KEYCODE_6 -> 7
    AndroidKeyEvent.KEYCODE_7 -> 8
    AndroidKeyEvent.KEYCODE_8 -> 9
    AndroidKeyEvent.KEYCODE_9 -> 10
    AndroidKeyEvent.KEYCODE_SPACE -> 57
    AndroidKeyEvent.KEYCODE_ENTER -> 28
    AndroidKeyEvent.KEYCODE_DEL -> 14 // backspace
    AndroidKeyEvent.KEYCODE_TAB -> 15
    AndroidKeyEvent.KEYCODE_ESCAPE -> 1
    AndroidKeyEvent.KEYCODE_DPAD_UP -> 103
    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> 108
    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> 105
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> 106
    AndroidKeyEvent.KEYCODE_SHIFT_LEFT -> 42
    AndroidKeyEvent.KEYCODE_SHIFT_RIGHT -> 54
    AndroidKeyEvent.KEYCODE_CTRL_LEFT -> 29
    AndroidKeyEvent.KEYCODE_CTRL_RIGHT -> 97
    AndroidKeyEvent.KEYCODE_ALT_LEFT -> 56
    AndroidKeyEvent.KEYCODE_ALT_RIGHT -> 100
    AndroidKeyEvent.KEYCODE_MINUS -> 12
    AndroidKeyEvent.KEYCODE_EQUALS -> 13
    AndroidKeyEvent.KEYCODE_LEFT_BRACKET -> 26
    AndroidKeyEvent.KEYCODE_RIGHT_BRACKET -> 27
    AndroidKeyEvent.KEYCODE_BACKSLASH -> 43
    AndroidKeyEvent.KEYCODE_SEMICOLON -> 39
    AndroidKeyEvent.KEYCODE_APOSTROPHE -> 40
    AndroidKeyEvent.KEYCODE_GRAVE -> 41
    AndroidKeyEvent.KEYCODE_COMMA -> 51
    AndroidKeyEvent.KEYCODE_PERIOD -> 52
    AndroidKeyEvent.KEYCODE_SLASH -> 53
    AndroidKeyEvent.KEYCODE_AT -> 3 // mapped to '2' key (shift+2 = @)
    AndroidKeyEvent.KEYCODE_FORWARD_DEL -> 111
    AndroidKeyEvent.KEYCODE_PAGE_UP -> 104
    AndroidKeyEvent.KEYCODE_PAGE_DOWN -> 109
    AndroidKeyEvent.KEYCODE_MOVE_HOME -> 102
    AndroidKeyEvent.KEYCODE_MOVE_END -> 107
    AndroidKeyEvent.KEYCODE_INSERT -> 110
    AndroidKeyEvent.KEYCODE_F1 -> 59
    AndroidKeyEvent.KEYCODE_F2 -> 60
    AndroidKeyEvent.KEYCODE_F3 -> 61
    AndroidKeyEvent.KEYCODE_F4 -> 62
    AndroidKeyEvent.KEYCODE_F5 -> 63
    AndroidKeyEvent.KEYCODE_F6 -> 64
    AndroidKeyEvent.KEYCODE_F7 -> 65
    AndroidKeyEvent.KEYCODE_F8 -> 66
    AndroidKeyEvent.KEYCODE_F9 -> 67
    AndroidKeyEvent.KEYCODE_F10 -> 68
    AndroidKeyEvent.KEYCODE_F11 -> 87
    AndroidKeyEvent.KEYCODE_F12 -> 88
    else -> -1
}

/** Map a typed character to its evdev keycode. */
private fun charToEvdev(ch: Char): Int {
    val lower = ch.lowercaseChar()
    return when (lower) {
        'a' -> 30; 'b' -> 48; 'c' -> 46; 'd' -> 32; 'e' -> 18
        'f' -> 33; 'g' -> 34; 'h' -> 35; 'i' -> 23; 'j' -> 36
        'k' -> 37; 'l' -> 38; 'm' -> 50; 'n' -> 49; 'o' -> 24
        'p' -> 25; 'q' -> 16; 'r' -> 19; 's' -> 31; 't' -> 20
        'u' -> 22; 'v' -> 47; 'w' -> 17; 'x' -> 45; 'y' -> 21
        'z' -> 44
        '0' -> 11; '1' -> 2; '2' -> 3; '3' -> 4; '4' -> 5
        '5' -> 6; '6' -> 7; '7' -> 8; '8' -> 9; '9' -> 10
        ' ' -> 57; '\n' -> 28
        '.' -> 52; ',' -> 51; '/' -> 53; '-' -> 12; '=' -> 13
        ';' -> 39; '\'' -> 40; '`' -> 41; '[' -> 26; ']' -> 27
        '\\' -> 43
        else -> -1
    }
}
