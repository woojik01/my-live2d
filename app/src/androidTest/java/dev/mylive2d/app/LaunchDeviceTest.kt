package dev.mylive2d.app

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun canvasAndToolbarSurviveActivityRecreation() {
        compose.onNodeWithTag("modelCanvas").assertIsDisplayed()
        compose.onNodeWithTag("save").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitForIdle()
        compose.onNodeWithTag("modelCanvas").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("modelCanvas").assertIsDisplayed()
        compose.onNodeWithTag("save").assertIsDisplayed()
    }
}
