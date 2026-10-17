package androidx.lifecycle.viewmodel.compose

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.CreationExtras

@Composable
inline fun <reified VM : ViewModel> viewModel(key: String? = null, noinline initializer: CreationExtras.() -> VM): VM = TODO()
