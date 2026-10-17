package ir.melkcrm.client

import ir.melkcrm.client.store.InMemoryLocalStore
import ir.melkcrm.client.store.LocalStore
import ir.melkcrm.client.store.LocalStoreContract

class InMemoryLocalStoreTest : LocalStoreContract() {
    override fun newStore(): LocalStore = InMemoryLocalStore()
}
