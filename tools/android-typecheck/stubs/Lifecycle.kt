package androidx.lifecycle

import kotlinx.coroutines.CoroutineScope

abstract class Lifecycle {
    abstract fun addObserver(observer: LifecycleObserver)
    abstract fun removeObserver(observer: LifecycleObserver)
    abstract val currentState: State

    enum class Event { ON_CREATE, ON_START, ON_RESUME, ON_PAUSE, ON_STOP, ON_DESTROY, ON_ANY }
    enum class State {
        DESTROYED, INITIALIZED, CREATED, STARTED, RESUMED;
        fun isAtLeast(state: State): Boolean = compareTo(state) >= 0
    }
}

interface LifecycleOwner { val lifecycle: Lifecycle }
interface LifecycleObserver
fun interface LifecycleEventObserver : LifecycleObserver { fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) }

interface ViewModelStoreOwner { val viewModelStore: ViewModelStore }
open class ViewModelStore

abstract class ViewModel {
    protected open fun onCleared() {}
}

val ViewModel.viewModelScope: CoroutineScope get() = TODO()
