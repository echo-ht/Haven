package sh.haven.core.terminal

/**
 * How Haven wants the soft keyboard to behave inside the terminal. Folded
 * into one sealed type so call sites pass a single value instead of a pair
 * of booleans — and so future modes (e.g. "accessibility") have a single
 * place to land.
 *
 * Translates to termlib's existing `allowStandardKeyboard` and
 * `rawKeyboardMode` flags on the public [org.connectbot.terminal.Terminal]
 * composable. Mapping lives in [HavenTerminal]; callers never touch the
 * termlib booleans directly.
 */
sealed interface HavenKeyboardMode {
    /**
     * Default. `TYPE_NULL`-class input with `NO_SUGGESTIONS` and
     * `NO_PERSONALIZED_LEARNING` — Gboard's mic, suggestion strip, and
     * on-device writing assist all stay off. CJK composition still works
     * because the terminal hosts its own composition flow on top.
     */
    data object Secure : HavenKeyboardMode

    /**
     * Standard Android soft keyboard. Voice input, swipe typing, and
     * autocomplete are all enabled. Useful when the user is typing long
     * free-form prose into a shell editor and wants full IME features.
     */
    data object Standard : HavenKeyboardMode

    /**
     * No `InputConnection` at all — the IME has nothing to attach to and
     * Gboard's decorations disappear entirely. Physical keyboards still
     * work via `View.dispatchKeyEvent`. Soft-keyboard input comes through
     * as raw key events only, which means no IME composition, so this is
     * for users who want the hardest possible IME lock-out and don't need
     * CJK. Equivalent to ConnectBot's old `TYPE_NULL` behaviour.
     */
    data object Raw : HavenKeyboardMode
}
