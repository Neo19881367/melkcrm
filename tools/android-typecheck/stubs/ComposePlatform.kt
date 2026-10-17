package androidx.compose.ui.platform

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

val LocalContext = staticCompositionLocalOf<android.content.Context> { error("stub") }
val LocalConfiguration = compositionLocalOf<android.content.res.Configuration> { error("stub") }
