package com.trainingapp.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import pl.trainerapp.core.designsystem.AppTheme
import pl.trainerapp.feature.chat.ChatAction
import pl.trainerapp.feature.chat.ChatLoadStatus
import pl.trainerapp.feature.chat.ChatScreen
import pl.trainerapp.feature.chat.ChatUiState
import kotlin.test.Test
import kotlin.test.assertEquals

/** The composer on a desktop keyboard: the button and Enter send, Shift+Enter does not. */
@OptIn(ExperimentalTestApi::class)
class ChatComposerTest {

    private fun run(block: androidx.compose.ui.test.DesktopComposeUiTest.(List<ChatAction>) -> Unit) = runDesktopComposeUiTest(width = 400, height = 800) {
        val actions = mutableListOf<ChatAction>()
        setContent {
            var state by mutableStateOf(ChatUiState(status = ChatLoadStatus.Content, name = "Test"))
            AppTheme {
                ChatScreen(
                    state = state,
                    onAction = { action ->
                        actions += action
                        if (action is ChatAction.DraftChanged) state = state.copy(draft = action.text)
                    },
                    onBack = {},
                )
            }
        }
        block(actions)
    }

    @Test
    fun theSendButtonSendsAfterTyping() = run { actions ->
        onNodeWithTag("chat-input").performTextInput("Cześć")
        waitForIdle()
        onNodeWithTag("chat-send").performClick()
        assertEquals(1, actions.count { it is ChatAction.Send })
    }

    @Test
    fun enterSendsAndShiftEnterDoesNot() = run { actions ->
        onNodeWithTag("chat-input").performTextInput("Cześć")
        waitForIdle()
        onNodeWithTag("chat-input").performKeyInput {
            keyDown(Key.ShiftLeft); pressKey(Key.Enter); keyUp(Key.ShiftLeft)
        }
        assertEquals(0, actions.count { it is ChatAction.Send })
        onNodeWithTag("chat-input").performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, actions.count { it is ChatAction.Send })
    }

    @Test
    fun aThreadWithReceiptsRendersAndAcceptsANewMessage() = runDesktopComposeUiTest(width = 400, height = 800) {
        val fx = pl.trainerapp.feature.chat.data.TrainerChatsFixtures
        var state by mutableStateOf(
            ChatUiState(
                status = ChatLoadStatus.Content,
                name = "Kasia Nowak",
                messages = fx.threads.getValue("c1"),
                now = fx.now,
                zone = fx.zone,
                peerActiveAt = fx.now,
                seenMessageId = "c1-2",
                seenAt = fx.now,
            ),
        )
        setContent {
            AppTheme {
                ChatScreen(
                    state = state,
                    onAction = { action ->
                        if (action is ChatAction.DraftChanged) state = state.copy(draft = action.text)
                        if (action is ChatAction.Send) {
                            state = state.copy(
                                messages = state.messages + pl.trainerapp.feature.chat.model.ChatMessage("new", pl.trainerapp.feature.chat.model.ChatRole.Trainer, state.draft, fx.now),
                                draft = "",
                            )
                        }
                    },
                    onBack = {},
                )
            }
        }
        onNodeWithTag("chat-input").performTextInput("Nowa")
        waitForIdle()
        onNodeWithTag("chat-input").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(4, state.messages.size)
    }

    @Test
    fun anEmptyChatSpeaksToTheTrainerForAClientAndToTheClientForATrainer() {
        fun emptyChat(viewer: pl.trainerapp.feature.chat.model.ChatRole, expected: String) = runDesktopComposeUiTest(width = 400, height = 800) {
            setContent {
                AppTheme { ChatScreen(ChatUiState(viewer = viewer, status = ChatLoadStatus.Content, name = "Test"), onAction = {}, onBack = {}) }
            }
            onNodeWithText(expected).assertExists()
        }
        emptyChat(pl.trainerapp.feature.chat.model.ChatRole.Client, "Napisz pierwszą wiadomość do trenera.")
        emptyChat(pl.trainerapp.feature.chat.model.ChatRole.Trainer, "Napisz pierwszą wiadomość do klienta.")
    }
}
