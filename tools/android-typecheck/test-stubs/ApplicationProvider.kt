package androidx.test.core.app

import android.content.Context

object ApplicationProvider {
    @JvmStatic fun <T : Context> getApplicationContext(): T = TODO()
}
