package com.antigravity.monarch

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.antigravity.monarch.di.AppEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end through the real app graph: onboarding as a guest, plan generation, logging a
 * workout, the level-up moment, achievements, class lock and the reset flow.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OnboardingToProgressUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val entry: AppEntryPoint by lazy {
        EntryPointAccessors.fromApplication(ApplicationProvider.getApplicationContext<Context>(), AppEntryPoint::class.java)
    }

    @Before
    fun cleanSlate() = runBlocking { entry.resetter().resetEverything() }

    @Test
    fun fullPlayerJourney() {
        ActivityScenario.launch(MainActivity::class.java).use {
            // Awakening -> guest
            compose.waitUntilExactlyOneExists(hasTestTag("onboarding_guest"), 10_000)
            compose.onNodeWithTag("onboarding_guest").performClick()

            // Validation blocks an empty identity step.
            compose.onNodeWithTag("onboarding_next").performClick()
            compose.onNodeWithText("Enter a player name").assertExists()

            compose.onNodeWithTag("field_name").performTextInput("Jin")
            compose.onNodeWithTag("field_age").performTextInput("27")
            compose.onNodeWithText("Male").performClick()
            compose.onNodeWithTag("onboarding_next").performClick()

            compose.onNodeWithTag("field_height").performTextInput("178")
            compose.onNodeWithTag("field_weight").performTextInput("76.5")
            compose.onNodeWithTag("field_target").performTextInput("72")
            compose.onNodeWithTag("onboarding_next").performClick()

            compose.onNodeWithTag("goal_MUSCLE_GAIN").performClick()
            compose.onNodeWithTag("onboarding_next").performClick()

            compose.onNodeWithText("Moderate").performClick()
            compose.onNodeWithTag("onboarding_next").performClick()

            // Links are optional.
            compose.onNodeWithTag("onboarding_next").performClick()

            // Must acknowledge the health notice.
            compose.onNodeWithTag("onboarding_next").performClick()
            compose.onNodeWithTag("onboarding_message").assertExists()
            compose.onNodeWithTag("accept_disclaimer").performClick()
            compose.onNodeWithTag("onboarding_next").performClick()

            // Plan reveal (on-device planner in tests: no API key).
            compose.waitUntilExactlyOneExists(hasText("Enter the System".uppercase()), 20_000)
            compose.onNodeWithText("Muscle gain Protocol").assertExists()
            compose.onNodeWithTag("onboarding_next").performClick()

            // Status window
            compose.waitUntilExactlyOneExists(hasTestTag("player_name"), 10_000)
            compose.onNodeWithText("JIN").assertExists()
            compose.onNodeWithTag("player_level").assertExists()
            compose.onNodeWithText("CLASS: UNAWAKENED (LV 10) ›").assertExists()

            val profile = runBlocking { entry.profiles().getProfile() }
            assertNotNull(profile)
            assertEquals("Jin", profile!!.name)

            // Log a workout from the Log tab.
            compose.onNodeWithTag("tab_log").performClick()
            compose.waitUntilExactlyOneExists(hasTestTag("log_workout_button"), 5_000)
            compose.onNodeWithTag("log_workout_button").performClick()

            // First workout: achievement + quest bonus push the player to level 2.
            compose.waitUntilExactlyOneExists(hasTestTag("level_up_dialog"), 10_000)
            compose.onNodeWithText("CONTINUE").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTagCount("level_up_dialog") == 0 }

            val status = runBlocking { entry.progression().currentStatus() }!!
            assertTrue("level was ${status.level}", status.level >= 2)
            assertTrue(status.totalXp > 0)

            // Achievements reflect the unlock.
            compose.onNodeWithTag("tab_records").performClick()
            compose.waitUntilExactlyOneExists(hasTestTag("achievement_count"), 5_000)
            compose.onNodeWithText("Awakening").assertExists()

            // Classes are locked below level 10.
            compose.onNodeWithTag("tab_status").performClick()
            compose.waitUntilExactlyOneExists(hasTestTag("player_name"), 5_000)
            compose.onNodeWithText("CLASS: UNAWAKENED (LV 10) ›").performClick()
            compose.waitUntilExactlyOneExists(hasTestTag("class_WARRIOR"), 5_000)
            compose.onNodeWithTag("class_WARRIOR").performClick()
            compose.waitUntilExactlyOneExists(hasText("Unlocks at level 10."), 5_000)

            // Settings -> reset returns to onboarding with no profile.
            compose.onNodeWithContentDescription("Back").performClick()
            compose.waitUntilExactlyOneExists(hasTestTag("open_settings"), 5_000)
            compose.onNodeWithTag("open_settings").performClick()
            compose.onNodeWithTag("settings_list").performScrollToNode(hasTestTag("reset_button"))
            compose.onNodeWithTag("reset_button").performClick()
            compose.onNodeWithTag("confirm_reset").performClick()
            compose.waitUntilExactlyOneExists(hasTestTag("onboarding_guest"), 10_000)
            assertNull(runBlocking { entry.profiles().getProfile() })
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size
}
