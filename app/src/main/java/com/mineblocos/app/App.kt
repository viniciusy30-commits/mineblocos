package com.mineblocos.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Atlas.init(this)
        Reg.init(this)
    }
}
