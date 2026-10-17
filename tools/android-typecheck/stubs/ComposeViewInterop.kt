package androidx.compose.ui.viewinterop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun <T : android.view.View> AndroidView(factory: (android.content.Context) -> T, modifier: Modifier = Modifier, update: (T) -> Unit = {}) {}
