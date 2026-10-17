package androidx.work

import android.content.Context
import java.util.concurrent.TimeUnit

class Configuration private constructor() {
    interface Provider { val workManagerConfiguration: Configuration }
    class Builder {
        fun setMinimumLoggingLevel(loggingLevel: Int): Builder = this
        fun build(): Configuration = Configuration()
    }
}

enum class NetworkType { NOT_REQUIRED, CONNECTED, UNMETERED, NOT_ROAMING, METERED, TEMPORARILY_UNMETERED }
enum class ExistingPeriodicWorkPolicy { REPLACE, KEEP, UPDATE, CANCEL_AND_REENQUEUE }
enum class ExistingWorkPolicy { REPLACE, KEEP, APPEND, APPEND_OR_REPLACE }

class Constraints private constructor() {
    class Builder {
        fun setRequiredNetworkType(networkType: NetworkType): Builder = this
        fun build(): Constraints = Constraints()
    }
}

class WorkerParameters private constructor()

abstract class ListenableWorker(appContext: Context, workerParams: WorkerParameters) {
    val applicationContext: Context = appContext
    val runAttemptCount: Int = 0
    abstract class Result internal constructor() {
        companion object {
            @JvmStatic fun success(): Result = TODO()
            @JvmStatic fun retry(): Result = TODO()
            @JvmStatic fun failure(): Result = TODO()
        }
    }
}

abstract class CoroutineWorker(appContext: Context, params: WorkerParameters) : ListenableWorker(appContext, params) {
    abstract suspend fun doWork(): Result
}

abstract class WorkRequest internal constructor()
class OneTimeWorkRequest internal constructor() : WorkRequest() {
    class Builder internal constructor() {
        fun setInitialDelay(duration: Long, timeUnit: TimeUnit): Builder = this
        fun setConstraints(constraints: Constraints): Builder = this
        fun build(): OneTimeWorkRequest = OneTimeWorkRequest()
    }
}
class PeriodicWorkRequest internal constructor() : WorkRequest() {
    class Builder internal constructor() {
        fun setConstraints(constraints: Constraints): Builder = this
        fun setInitialDelay(duration: Long, timeUnit: TimeUnit): Builder = this
        fun build(): PeriodicWorkRequest = PeriodicWorkRequest()
    }
}

inline fun <reified W : ListenableWorker> OneTimeWorkRequestBuilder(): OneTimeWorkRequest.Builder = TODO()
inline fun <reified W : ListenableWorker> PeriodicWorkRequestBuilder(repeatInterval: Long, repeatIntervalTimeUnit: TimeUnit): PeriodicWorkRequest.Builder = TODO()

interface Operation

abstract class WorkManager internal constructor() {
    abstract fun enqueueUniquePeriodicWork(uniqueWorkName: String, existingPeriodicWorkPolicy: ExistingPeriodicWorkPolicy, request: PeriodicWorkRequest): Operation
    abstract fun enqueueUniqueWork(uniqueWorkName: String, existingWorkPolicy: ExistingWorkPolicy, request: OneTimeWorkRequest): Operation
    abstract fun cancelUniqueWork(uniqueWorkName: String): Operation
    companion object {
        @JvmStatic fun getInstance(context: Context): WorkManager = TODO()
    }
}
