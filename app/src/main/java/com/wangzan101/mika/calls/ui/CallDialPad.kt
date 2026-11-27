package com.wangzan101.mika.calls.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** One key of a phone keypad: a character to enter, or delete. */
internal sealed interface CallDialKey {
    data class Symbol(val char: Char) : CallDialKey
    data object Backspace : CallDialKey
}

internal object CallDialLayouts {
    private val digitRows = listOf("123", "456", "789").map { row -> row.map(CallDialKey::Symbol) }

    /** Outgoing numbers are digits with an optional leading '+' (CallPolicy.outgoingNumber), so no '*' or '#'. */
    val number: List<List<CallDialKey>> =
        digitRows + listOf(listOf(CallDialKey.Symbol('+'), CallDialKey.Symbol('0'), CallDialKey.Backspace))

    /** Tones sent during a call. */
    val dtmf: List<List<CallDialKey>> = digitRows + listOf("*0#".map(CallDialKey::Symbol))
}

/**
 * The keypad grid shared by the number dial pad and the in-call DTMF keypad. Phone keypads keep
 * 1-2-3 left to right in every language, so the rows are laid out LTR; each key's own content
 * keeps the surrounding direction.
 */
@Composable
internal fun CallDialPadGrid(
    rows: List<List<CallDialKey>>,
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    key: @Composable RowScope.(CallDialKey) -> Unit,
) {
    val outer = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
            for (row in rows) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    for (k in row) CompositionLocalProvider(LocalLayoutDirection provides outer) { key(k) }
                }
            }
        }
    }
}

/** A round keypad key filling its share of the row. */
@Composable
internal fun RowScope.CallDialKeyButton(
    onClick: () -> Unit, enabled: Boolean, tag: String, minHeight: Dp, verticalPadding: Dp,
    content: @Composable RowScope.() -> Unit,
) {
    FilledTonalButton(
        onClick = onClick, enabled = enabled, shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = verticalPadding),
        modifier = Modifier.weight(1f).heightIn(min = minHeight).testTag(tag),
        content = content,
    )
}
