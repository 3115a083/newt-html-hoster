package dev.newthoster.app

import android.app.Application

class HosterApp : Application() {
    lateinit var buckets: BucketStore
        private set
    lateinit var vault: SecurityVault
        private set

    override fun onCreate() {
        super.onCreate()
        buckets = BucketStore(this)
        vault = SecurityVault(this)
    }
}
