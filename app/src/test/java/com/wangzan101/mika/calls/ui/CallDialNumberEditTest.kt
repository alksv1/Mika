package com.wangzan101.mika.calls.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.wangzan101.mika.calls.CallTaskBrief
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallDialNumberEditTest {
    @Test fun keyInsertsAtCursorAndReplacesReversedSelection() {
        assertEquals(TextFieldValue("12934", TextRange(3)), replaceNumberSelection(TextFieldValue("1234", TextRange(2)), "9"))
        assertEquals(TextFieldValue("194", TextRange(2)), replaceNumberSelection(TextFieldValue("1234", TextRange(3, 1)), "9"))
    }

    @Test fun backspaceDeletesTheSelectionOrTheCodePointBeforeTheCursor() {
        assertEquals(TextFieldValue("14", TextRange(1)), deleteNumberSelection(TextFieldValue("1234", TextRange(3, 1))))
        assertEquals(TextFieldValue("13", TextRange(1)), deleteNumberSelection(TextFieldValue("123", TextRange(2))))
        assertEquals(TextFieldValue("12", TextRange(1)), deleteNumberSelection(TextFieldValue("1😀2", TextRange(3))))
        assertEquals(TextFieldValue("12", TextRange(0)), deleteNumberSelection(TextFieldValue("12", TextRange(0))))
    }

    @Test fun dialPadEditsTheDraftAtItsCursor() {
        val draft = CallTaskDraft()
        draft.number = "1388"
        assertEquals(TextRange(4), draft.numberInput.selection)
        draft.numberInput = draft.numberInput.copy(selection = TextRange(2))
        draft.pressDialKey('0')
        assertEquals("13088", draft.number)
        draft.numberInput = draft.numberInput.copy(selection = TextRange(1, 3))
        draft.pressDialBackspace()
        assertEquals("188", draft.number)
        assertEquals(TextRange(1), draft.numberInput.selection)
        // Writing the same text again (state restore, reuse) keeps the user's cursor.
        draft.number = "188"
        assertEquals(TextRange(1), draft.numberInput.selection)
    }

    @Test fun clearAndUndoOnlyTouchTheTextAndNeverTheSimChoice() {
        val draft = CallTaskDraft()
        draft.fill("10086", CallTaskBrief("goal", listOf("q"), "ctx", "rule"))
        draft.simOverride = "sim-2"
        val before = draft.snapshotFields()
        val cleared = draft.clearText()!!
        assertEquals(listOf("", "", "", "", "", "sim-2"), draft.snapshotFields())
        assertFalse(draft.hasContent)
        assertEquals("ClearedCallTaskText(redacted)", cleared.toString())
        assertTrue(draft.undoClear(cleared))
        assertEquals(before, draft.snapshotFields())
    }

    @Test fun undoNeverOverwritesNewTextAndAnEmptyDraftHasNothingToClear() {
        val draft = CallTaskDraft()
        assertNull(draft.clearText())
        draft.goal = "old"
        val cleared = draft.clearText()!!
        draft.number = "123"
        assertFalse(draft.undoClear(cleared))
        assertEquals("123", draft.number)
        assertEquals("", draft.goal)
    }

    @Test fun savedStateKeepsOnlyTheSixDraftFields() {
        val draft = CallTaskDraft()
        draft.goal = "goal"
        draft.clearText()
        // Nothing about the cleared text is part of what the saver writes.
        assertEquals(List(6) { "" }, draft.snapshotFields())
    }

    @Test fun whitespaceCountsAsContentSoItCanBeClearedAndUndoNeverOverwritesIt() {
        val draft = CallTaskDraft()
        draft.goal = "   "
        assertTrue(draft.hasContent)
        val cleared = draft.clearText()!!
        assertEquals("", draft.goal)
        draft.context = " "
        assertFalse(draft.undoClear(cleared))
        assertEquals(" ", draft.context)
        assertEquals("", draft.goal)
    }

    @Test fun numberPadOffersOnlyWhatAnOutgoingNumberMayContain() {
        val symbols = CallDialLayouts.number.flatten().filterIsInstance<CallDialKey.Symbol>().map { it.char }
        assertEquals("123456789+0".toSet(), symbols.toSet())
        assertTrue(CallDialKey.Backspace in CallDialLayouts.number.last())
        assertEquals("123456789*0#".toSet(),
            CallDialLayouts.dtmf.flatten().map { (it as CallDialKey.Symbol).char }.toSet())
        assertEquals("+12345678", com.wangzan101.mika.calls.CallPolicy.outgoingNumber("+12345678"))
        assertNull(com.wangzan101.mika.calls.CallPolicy.outgoingNumber("1234567#"))
    }
}
