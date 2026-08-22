package com.valhalla.loki.di

import android.app.Application
import android.content.Context
import com.valhalla.loki.model.AppInfoGrabber
import com.valhalla.loki.model.LogcatCapture
import com.valhalla.loki.model.Packages
import com.valhalla.loki.model.PermissionManager
import com.valhalla.loki.model.SelfGrantStore
import com.valhalla.loki.model.SelfPermissionGrabber
import com.valhalla.loki.model.ThemeManager
import com.valhalla.loki.model.logsDir
import com.valhalla.loki.model.shareCacheDir
import com.valhalla.loki.ui.appList.AppListViewModel
import com.valhalla.loki.ui.explorer.LogsExplorerViewModel
import com.valhalla.loki.ui.home.HomeViewModel
import com.valhalla.loki.ui.onboarding.OnboardingViewModel
import com.valhalla.loki.ui.saved.LogViewerViewModel
import com.valhalla.loki.ui.saved.SavedLogsViewModel
import com.valhalla.loki.ui.settings.SettingsViewModel
import com.valhalla.superuser.ktx.RealShellRepository
import com.valhalla.superuser.ktx.ShellRepository
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

var appModules = module {
    // Odin's root shell. Bound as the interface so tests and previews can swap it.
    singleOf(::RealShellRepository) bind ShellRepository::class
    single { androidContext().contentResolver }
    singleOf(::Packages)
    singleOf(::AppInfoGrabber)
    singleOf(::PermissionManager)
    // Single, because a second instance would be a second DataStore over the same file, which
    // throws. Resolves `Context` from androidContext(), like Packages does.
    singleOf(::ThemeManager)
    // Shares that one DataStore rather than opening its own — see model/Preferences.kt.
    singleOf(::SelfGrantStore)
    singleOf(::LogcatCapture)
    // Spelled out rather than singleOf(::X): the constructor takes a String and a PackageManager,
    // and an unqualified `String` binding would be the same mistake the removed
    // `single<File> { filesDir }` was — every future String dependency silently resolving to the
    // package name. There is no component scan here, so this line *is* the binding; annotating the
    // class does nothing. Nothing resolves it lazily either, which is why `Loki.onCreate` asks for
    // it by hand.
    single {
        SelfPermissionGrabber(
            packageName = androidContext().packageName,
            packageManager = androidContext().packageManager,
            permissionManager = get(),
            logcatCapture = get(),
            store = get(),
        )
    }
    viewModelOf(::AppListViewModel)
    viewModelOf(::HomeViewModel)
    viewModelOf(::OnboardingViewModel)
    // Spelled out rather than viewModelOf(::X), because both of these take a File and there is no
    // unqualified File binding to resolve it from. There used to be one — `single<File> { filesDir }`
    // — and it meant any future File dependency silently got filesDir whatever it actually wanted.
    viewModel {
        SavedLogsViewModel(
            logsDir = get<Context>().logsDir,
            appInfoGrabber = get(),
        )
    }
    // One instance per file, so the file is an injected parameter rather than a binding. The
    // ContentResolver is bound below instead of a Context, which keeps the export path testable
    // without an Android framework mock.
    viewModel { parameters ->
        LogViewerViewModel(
            file = parameters.get(),
            contentResolver = get(),
        )
    }
    viewModel {
        LogsExplorerViewModel(
            logsDir = get<Context>().logsDir,
            shareCacheDir = get<Context>().shareCacheDir,
            appInfoGrabber = get(),
        )
    }
    viewModel {
        SettingsViewModel(
            permissionManager = get(),
            logcatCapture = get(),
            themeManager = get(),
            selfPermissionGrabber = get(),
            logsDir = get<Context>().logsDir,
        )
    }
}

fun Application.initKoin() = startKoin {
    androidContext(this@initKoin)
    androidLogger()
    modules(appModules)
}
