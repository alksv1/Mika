package com.wangzan101.mika.calls.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal fun replaceNumberSelection(input: TextFieldValue, replacement: String): TextFieldValue {
    val range = input.selection
    return TextFieldValue(input.text.replaceRange(range.min, range.max, replacement),
        TextRange(range.min + replacement.length))
}

internal fun deleteNumberSelection(input: TextFieldValue): TextFieldValue {
    val range = input.selection
    if (!range.collapsed) return replaceNumberSelection(input, "")
    if (range.end == 0) return input
    val previous = Character.offsetByCodePoints(input.text, range.end, -1)
    return replaceNumberSelection(input.copy(selection = TextRange(previous, range.end)), "")
}
