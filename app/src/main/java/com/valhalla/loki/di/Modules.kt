package com.valhalla.loki.di

import android.app.Application
import com.valhalla.loki.model.AppInfoGrabber
import com.valhalla.loki.ui.appList.AppListViewModel
import com.valhalla.loki.ui.home.HomeViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

var appModules = module{
    singleOf(::AppInfoGrabber)
    viewModelOf(::AppListViewModel)
    viewModelOf(::HomeViewModel)
}

fun Application.initKoin() = startKoin {
    androidContext(this@initKoin)
    androidLogger()
    modules(appModules)
}

