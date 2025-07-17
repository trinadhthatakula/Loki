package com.valhalla.loki.di

import android.app.Application
import android.content.Context
import com.valhalla.loki.model.AppInfoGrabber
import com.valhalla.loki.ui.appList.AppListViewModel
import com.valhalla.loki.ui.home.HomeViewModel
import com.valhalla.loki.ui.onboarding.OnboardingScreen
import com.valhalla.loki.ui.onboarding.OnboardingViewModel
import com.valhalla.loki.ui.saved.SavedLogsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import java.io.File

var appModules = module{
    single<File> {
        get<Context>().filesDir
    }
    singleOf(::AppInfoGrabber)
    viewModelOf(::AppListViewModel)
    viewModelOf(::HomeViewModel)
    viewModelOf(::SavedLogsViewModel)
    viewModelOf(::OnboardingViewModel)
}

fun Application.initKoin() = startKoin {
    androidContext(this@initKoin)
    androidLogger()
    modules(appModules)
}

