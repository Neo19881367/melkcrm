package androidx.compose.ui.res

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable

@Composable
fun stringResource(@StringRes id: Int): String = ""

@Composable
fun stringResource(@StringRes id: Int, vararg formatArgs: Any): String = ""
