package com.mineblocos.app

import android.app.Application
import android.util.Log

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Atlas.init(this)
        try {
            Reg.init(this)
        } catch (e: Throwable) {
            Log.e("Mineblocos", "Falha ao carregar registry.tsv (arquivo ausente em assets?)", e)
        }
    }
}
