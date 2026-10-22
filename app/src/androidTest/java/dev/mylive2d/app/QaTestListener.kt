package dev.mylive2d.app

import android.util.Log
import org.junit.runner.Description
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener

/** Keep the last running test identifiable even if the instrumentation process stops responding. */
class QaTestListener : RunListener() {
    override fun testStarted(description: Description) { Log.i("MyLive2D-QA", "START $description") }
    override fun testFinished(description: Description) { Log.i("MyLive2D-QA", "FINISH $description") }
    override fun testFailure(failure: Failure) { Log.e("MyLive2D-QA", "FAIL ${failure.description}", failure.exception) }
}
