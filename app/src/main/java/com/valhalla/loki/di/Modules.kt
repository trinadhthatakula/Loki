package com.valhalla.loki.di

import android.app.Application
import android.content.Context
import com.valhalla.loki.model.AppInfoGrabber
import com.valhalla.loki.model.LogcatCapture
import com.valhalla.loki.model.Packages
import com.valhalla.loki.model.PermissionManager
import com.valhalla.loki.ui.appList.AppListViewModel
import com.valhalla.loki.ui.home.HomeViewModel
import com.valhalla.loki.ui.onboarding.OnboardingViewModel
import com.valhalla.loki.ui.saved.SavedLogsViewModel
import com.valhalla.superuser.ktx.RealShellRepository
import com.valhalla.superuser.ktx.ShellRepository
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module
import java.io.File

var appModules = module {
    single<File> {
        get<Context>().filesDir
    }
    // Odin's root shell. Bound as the interface so tests and previews can swap it.
    singleOf(::RealShellRepository) bind ShellRepository::class
    singleOf(::Packages)
    singleOf(::AppInfoGrabber)
    singleOf(::PermissionManager)
    singleOf(::LogcatCapture)
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
