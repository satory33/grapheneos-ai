package com.satory.graphenosai

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.satory.graphenosai.service.AssistantState
import com.satory.graphenosai.ui.StateIndicator
import com.satory.graphenosai.ui.theme.AiintegratedintoandroidTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for UI components.
 */
@RunWith(AndroidJUnit4::class)
class AssistantUITest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun stateIndicator_showsIdleState() {
        composeTestRule.setContent {
            AiintegratedintoandroidTheme {
                StateIndicator(state = AssistantState.Idle)
            }
        }

        composeTestRule.onNodeWithText("Ready").assertIsDisplayed()
    }

    @Test
    fun stateIndicator_showsListeningState() {
        composeTestRule.setContent {
            AiintegratedintoandroidTheme {
                StateIndicator(state = AssistantState.Listening)
            }
        }

        composeTestRule.onNodeWithText("Listening...").assertIsDisplayed()
    }

    @Test
    fun stateIndicator_showsProcessingState() {
        composeTestRule.setContent {
            AiintegratedintoandroidTheme {
                StateIndicator(state = AssistantState.Processing)
            }
        }

        composeTestRule.onNodeWithText("Processing...").assertIsDisplayed()
    }

    @Test
    fun stateIndicator_showsErrorState() {
        composeTestRule.setContent {
            AiintegratedintoandroidTheme {
                StateIndicator(state = AssistantState.Error("Test error"))
            }
        }

        composeTestRule.onNodeWithText("Error: Test error").assertIsDisplayed()
    }
}
