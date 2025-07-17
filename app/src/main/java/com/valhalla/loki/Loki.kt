package com.valhalla.loki

import android.app.Application
import com.valhalla.loki.di.initKoin

class Loki: Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin()
    }
}