package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 换页时界面状态:返回到还在栈里的页原样恢复,出栈后重新进来是新的一页。 */
@RunWith(AndroidJUnit4::class)
class NavSlideStateTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun backRestoresAndReentryStartsFresh() {
        var stack by mutableStateOf(listOf("root", "list"))
        compose.setContent {
            MikaTheme {
                val states = rememberPageStates()
                NavSlide(stack, depth = { it.size }, states = states, pageKey = { it.last() }) { s ->
                    var taps by rememberSaveable { mutableIntStateOf(0) }
                    Column {
                        Text("${s.last()}:$taps")
                        Button(onClick = { taps++ }) { Text("tap-${s.last()}") }
                    }
                }
            }
        }
        repeat(2) { compose.onNodeWithText("tap-list").performClick() }
        compose.onNodeWithText("list:2").assertIsDisplayed()

        // 进下一层再返回:上一层原样
        compose.runOnIdle { stack = listOf("root", "list", "detail") }
        compose.onNodeWithText("tap-detail").performClick()
        compose.onNodeWithText("detail:1").assertIsDisplayed()
        compose.runOnIdle { stack = listOf("root", "list") }
        compose.onNodeWithText("list:2").assertIsDisplayed()

        // 出栈的那一层再进来:从头开始
        compose.runOnIdle { stack = listOf("root", "list", "detail") }
        compose.onNodeWithText("detail:0").assertIsDisplayed()

        // 返回到根,再从入口进 list:也从头开始
        compose.runOnIdle { stack = listOf("root") }
        compose.onNodeWithText("root:0").assertIsDisplayed()
        compose.runOnIdle { stack = listOf("root", "list") }
        compose.onNodeWithText("list:0").assertIsDisplayed()
    }

    @Test
    fun sameTopWithDifferentStackKeepsThePage() {
        // 下面的页被抽掉、最上面不变:不重演换页、不出现两份同名页
        var stack by mutableStateOf(listOf("root", "call", "settings"))
        compose.setContent {
            MikaTheme {
                val states = rememberPageStates()
                NavSlide(stack, depth = { it.size }, states = states, pageKey = { it.last() }) { s ->
                    var taps by rememberSaveable { mutableIntStateOf(0) }
                    Column {
                        Text("${s.last()}:$taps")
                        Button(onClick = { taps++ }) { Text("tap-${s.last()}") }
                    }
                }
            }
        }
        compose.onNodeWithText("tap-settings").performClick()
        compose.runOnIdle { stack = listOf("root", "settings") }
        compose.onNodeWithText("settings:1").assertIsDisplayed()
    }
}
