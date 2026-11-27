package com.wangzan101.mika.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ui.components.ActionSheet
import com.wangzan101.mika.ui.components.SHEET_SEARCH_MIN
import com.wangzan101.mika.ui.components.SheetAction
import com.wangzan101.mika.ui.components.SheetTrailing
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 操作面板:项多时出搜索框、筛选时常驻项留着;行尾按钮只做自己的事,不选行不关面板。 */
@RunWith(AndroidJUnit4::class)
class ActionSheetSearchTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun searchFiltersAndTrailingButtonDoesNotPick() {
        val played = mutableListOf<String>()
        var picked: String? = null
        var dismissed = false
        val voices = (1..SHEET_SEARCH_MIN).map { "voice-$it" } + "alpha"
        compose.setContent {
            MikaTheme {
                if (!dismissed) ActionSheet(
                    "Voice",
                    listOf(SheetAction("Default", searchable = false) { picked = "" }) + voices.map { v ->
                        SheetAction(v, trailing = SheetTrailing(MikaIcons.Sound, "Listen to $v") { played += v }) { picked = v }
                    },
                    searchHint = "Search",
                ) { dismissed = true }
            }
        }
        compose.onNodeWithTag("sheet-search").performTextInput("ALP")
        compose.onNodeWithText("voice-1").assertDoesNotExist()
        compose.onNodeWithText("Default").assertExists()
        compose.onNodeWithContentDescription("Listen to alpha").assertHasClickAction().performClick()
        compose.runOnIdle {
            assertEquals(listOf("alpha"), played)
            assertEquals(null, picked)
            assertTrue(!dismissed)
        }
        compose.onNodeWithText("alpha").performClick()
        compose.runOnIdle {
            assertEquals("alpha", picked)
            assertTrue(dismissed)
        }
    }

    @Test
    fun shortListsHaveNoSearch() {
        compose.setContent {
            MikaTheme { ActionSheet("Engine", listOf(SheetAction("A") {}, SheetAction("B") {}), searchHint = "Search") {} }
        }
        compose.onNodeWithText("A").assertExists()
        compose.onNodeWithTag("sheet-search").assertDoesNotExist()
    }
}
