// Signature stubs for test libraries whose dependencies are only on Google Maven (androidx.test).
package org.robolectric

import org.junit.runner.Runner
import org.junit.runner.Description
import org.junit.runner.notification.RunNotifier

class RobolectricTestRunner(klass: Class<*>) : Runner() {
    override fun getDescription(): Description = TODO()
    override fun run(notifier: RunNotifier?): Unit = TODO()
}
