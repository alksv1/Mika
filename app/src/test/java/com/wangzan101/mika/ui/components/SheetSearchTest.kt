package com.wangzan101.mika.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SheetSearchTest {
    private fun a(label: String, searchable: Boolean = true) = SheetAction(label, searchable = searchable) {}

    private val actions = listOf(a("Default", searchable = false), a("en-us-x-sfg-local"), a("ja-JP-x-jab"), a("Alloy"), a("Enter voice ID", searchable = false))

    @Test fun blankQueryKeepsEverything() {
        assertEquals(actions, filterSheetActions(actions, "  "))
    }

    @Test fun filtersByLabelIgnoringCaseAndKeepsPinnedRows() {
        assertEquals(listOf("Default", "ja-JP-x-jab", "Enter voice ID"), filterSheetActions(actions, " JA-jp ").map { it.label })
    }

    @Test fun noMatchLeavesOnlyPinnedRows() {
        assertEquals(listOf("Default", "Enter voice ID"), filterSheetActions(actions, "zzz").map { it.label })
    }
}
