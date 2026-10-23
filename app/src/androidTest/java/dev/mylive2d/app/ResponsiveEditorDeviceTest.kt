package dev.mylive2d.app

import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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

        assertEquals(0,compose.onAllNodesWithTag("compactEditorSheetContent").fetchSemanticsNodes().size)
        val screenWidth=compose.activity.window.decorView.width.toFloat()
        val canvasWidth=compose.onNodeWithTag("modelCanvas").fetchSemanticsNode().boundsInRoot.width
        assertTrue("Canvas should use nearly the full width when no panel is open",canvasWidth>=screenWidth*.88f)

        compose.onNodeWithTag("panelProperties").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        val sheet=compose.onNodeWithTag("compactEditorSheet").fetchSemanticsNode().boundsInRoot
        val content=compose.onNodeWithTag("compactEditorSheetContent").fetchSemanticsNode().boundsInRoot
        val root=compose.onRoot().fetchSemanticsNode().boundsInRoot
        Log.i("MyLive2D-QA","sheet=$sheet content=$content root=$root window=${compose.activity.window.decorView.width}x${compose.activity.window.decorView.height}")
        compose.onNodeWithTag("compactEditorSheetContent").assertIsDisplayed()
        compose.onNodeWithTag("closeCompactPanel").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(0,compose.onAllNodesWithTag("compactEditorSheetContent").fetchSemanticsNodes().size)

        compose.onNodeWithTag("panelParts").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("compactEditorSheetContent").assertIsDisplayed()
    }
}
