package sh.haven.feature.terminal

import androidx.compose.ui.text.AnnotatedString
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.SelectionRange
import org.connectbot.terminal.TerminalDimensions
import org.connectbot.terminal.TerminalEmulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the smart-copy pipeline in [SelectionToolbar].
 *
 * Covers:
 * - [smartCopy] extraction across the three snapshot shapes it has to handle
 *   (single-row, multi-row hard-break, multi-row soft-wrap, TUI-bordered).
 * - [SmartTerminalClipboard.setText] fallback behaviour when smartCopy can't
 *   contribute non-blank content — this is the path that caused the v5.19.x
 *   clipboard-overwrite regression.
 */
class SmartCopyTest {

    private fun emulator(lines: List<String>, columns: Int? = null): TerminalEmulator {
        val width = columns ?: lines.maxOfOrNull { it.length } ?: 80
        return mockk(relaxed = true) {
            every { dimensions } returns TerminalDimensions(rows = lines.size, columns = width)
            every { getSnapshotLineTexts() } returns lines
        }
    }

    private fun controller(range: SelectionRange?): SelectionController =
        mockk(relaxed = true) {
            every { getSelectionRange() } returns range
        }

    // ---------- smartCopy ----------

    @Test
    fun `single-row selection returns the selected substring`() {
        val out = smartCopy(
            controller(SelectionRange(startRow = 0, startCol = 6, endRow = 0, endCol = 10)),
            emulator(listOf("hello world")),
        )
        assertEquals("world", out)
    }

    @Test
    fun `multi-row selection on hard line breaks keeps newlines`() {
        // Neither row fills the terminal width (40 columns), so rows are
        // treated as separate logical lines joined with "\n".
        val out = smartCopy(
            controller(SelectionRange(startRow = 0, startCol = 0, endRow = 1, endCol = 2)),
            emulator(listOf("foo", "bar"), columns = 40),
        )
        assertEquals("foo\nbar", out)
    }

    @Test
    fun `soft-wrapped lines (at terminal width) are joined without newlines`() {
        // Both rows fill the terminal width exactly → treated as a single
        // logical line that wrapped. Selection spans both rows and the
        // copied text reads as if typed into a wider terminal.
        val cols = 10
        val out = smartCopy(
            controller(SelectionRange(startRow = 0, startCol = 0, endRow = 1, endCol = 9)),
            emulator(listOf("abcdefghij", "klmnopqrst"), columns = cols),
        )
        assertEquals("abcdefghijklmnopqrst", out)
    }

    @Test
    fun `TUI border columns restrict copied text to the selected panel`() {
        // Three rows, each with a vertical-bar border at column 6, and the
        // selection anchored on the left panel ("left  "). The right panel
        // ("right", "data", "more") must NOT appear in the copied text.
        val out = smartCopy(
            controller(SelectionRange(startRow = 0, startCol = 0, endRow = 2, endCol = 5)),
            emulator(
                listOf(
                    "left  | right",
                    "line2 | data ",
                    "line3 | more ",
                ),
            ),
        )
        assertEquals("left\nline2\nline3", out)
    }

    @Test
    fun `selection starting past end of short line yields empty string`() {
        // This is the shape that silently wrote `""` to the clipboard in
        // v5.19.x — selection columns point past where the line's real
        // content ends, so substring(start, end) is empty.
        val out = smartCopy(
            controller(SelectionRange(startRow = 0, startCol = 50, endRow = 0, endCol = 55)),
            emulator(listOf("short"), columns = 80),
        )
        assertEquals("", out)
    }

    @Test
    fun `null selection returns null`() {
        val out = smartCopy(
            controller(range = null),
            emulator(listOf("whatever")),
        )
        assertNull(out)
    }

    // ---------- SmartTerminalClipboard.setText ----------

    private fun clipboard(
        controllerRange: SelectionRange?,
        lines: List<String>,
        columns: Int = 80,
    ): Pair<SmartTerminalClipboard, androidx.compose.ui.platform.ClipboardManager> {
        val delegate = mockk<androidx.compose.ui.platform.ClipboardManager>(relaxed = true)
        val smart = SmartTerminalClipboard(
            delegate = delegate,
            getEmulator = { emulator(lines, columns = columns) },
            getController = { controller(controllerRange) },
        )
        return smart to delegate
    }

    @Test
    fun `setText uses smartCopy result when it contributes content`() {
        val (smart, delegate) = clipboard(
            controllerRange = SelectionRange(0, 6, 0, 10),
            lines = listOf("hello world"),
        )
        // Caller passes an unprocessed AnnotatedString; smartCopy trims/
        // fixes it to "world".
        smart.setText(AnnotatedString("hello world"))

        val captured = slot<AnnotatedString>()
        verify { delegate.setText(capture(captured)) }
        assertEquals("world", captured.captured.text)
    }

    @Test
    fun `setText falls back to caller text when smartCopy returns blank`() {
        // Selection is past the end of a short line → smartCopy returns "".
        // Without the fallback, the clipboard would silently be cleared —
        // the v5.19.x regression. With the fallback, the caller's text
        // (what SelectionManager extracted) is used instead.
        val (smart, delegate) = clipboard(
            controllerRange = SelectionRange(0, 50, 0, 55),
            lines = listOf("short"),
        )
        smart.setText(AnnotatedString("short-from-selection"))

        val captured = slot<AnnotatedString>()
        verify { delegate.setText(capture(captured)) }
        assertEquals("short-from-selection", captured.captured.text)
    }

    @Test
    fun `setText falls back when controller is null`() {
        val delegate = mockk<androidx.compose.ui.platform.ClipboardManager>(relaxed = true)
        val smart = SmartTerminalClipboard(
            delegate = delegate,
            getEmulator = { emulator(listOf("text")) },
            getController = { null },
        )
        smart.setText(AnnotatedString("raw text"))

        val captured = slot<AnnotatedString>()
        verify { delegate.setText(capture(captured)) }
        assertEquals("raw text", captured.captured.text)
    }

    @Test
    fun `setText falls back when smartCopy returns only whitespace`() {
        // A selection covering only trailing padding/whitespace on a row —
        // smartCopy would return " " (just whitespace). isNullOrBlank()
        // treats that as no contribution, so we keep the caller's text.
        val (smart, delegate) = clipboard(
            controllerRange = SelectionRange(0, 5, 0, 8),
            lines = listOf("text    "),
        )
        smart.setText(AnnotatedString("meaningful"))

        val captured = slot<AnnotatedString>()
        verify { delegate.setText(capture(captured)) }
        assertTrue(
            "expected caller text or non-blank smartCopy output, got \"${captured.captured.text}\"",
            captured.captured.text.isNotBlank(),
        )
    }
}
