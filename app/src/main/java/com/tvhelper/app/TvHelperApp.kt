package com.tvhelper.app

import android.app.Application
import com.tvhelper.app.di.AppContainer

class TvHelperApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(applicationContext)
    }

    override fun onTerminate() {
        container.repository.shutdown()
        super.onTerminate()
    }

    companion object {
        lateinit var instance: TvHelperApp
            private set
    }
}
