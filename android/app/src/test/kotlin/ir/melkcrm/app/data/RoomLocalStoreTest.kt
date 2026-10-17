package ir.melkcrm.app.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ir.melkcrm.app.data.db.CrmDatabase
import ir.melkcrm.client.store.LocalStore
import ir.melkcrm.client.store.LocalStoreContract
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs the shared [LocalStoreContract] against Room (including the FTS index) on Robolectric's SQLite.
 * A plain Application is used so the test does not start WorkManager or the app container.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RoomLocalStoreTest : LocalStoreContract() {
    private var db: CrmDatabase? = null

    override fun newStore(): LocalStore {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CrmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db = database
        return RoomLocalStore(database)
    }

    override fun closeStore() {
        db?.close()
    }
}
