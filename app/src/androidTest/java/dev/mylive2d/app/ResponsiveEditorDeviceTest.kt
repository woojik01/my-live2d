package dev.mylive2d.app

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.fetchSemanticsNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResponsiveEditorDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun compactLayoutKeepsCanvasWideAndShowsPanelsOnDemand() {
        assumeTrue("Compact layout check is for phone and narrow-window widths",compose.activity.resources.configuration.screenWidthDp<700)

        compose.onNodeWithTag("compactEditorSheetContent").assertDoesNotExist()
        val screenWidth=compose.activity.window.decorView.width.toFloat()
        val canvasWidth=compose.onNodeWithTag("modelCanvas").fetchSemanticsNode().boundsInRoot.width
        assertTrue("Canvas should use nearly the full width when no panel is open",canvasWidth>=screenWidth*.88f)

        compose.onNodeWithTag("panelProperties").performClick()
        compose.onNodeWithTag("compactEditorSheetContent").assertIsDisplayed()
        compose.onNodeWithTag("closeCompactPanel").performClick()
        compose.onNodeWithTag("compactEditorSheetContent").assertDoesNotExist()

        compose.onNodeWithTag("panelParts").performClick()
        compose.onNodeWithTag("compactEditorSheetContent").assertIsDisplayed()
    }
}
