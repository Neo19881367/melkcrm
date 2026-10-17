package androidx.core.content

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.concurrent.Executor

object ContextCompat {
    @JvmStatic fun checkSelfPermission(context: Context, permission: String): Int = 0
    @JvmStatic fun getMainExecutor(context: Context): Executor = TODO()
}

open class FileProvider {
    companion object {
        @JvmStatic fun getUriForFile(context: Context, authority: String, file: File): Uri = TODO()
    }
}
