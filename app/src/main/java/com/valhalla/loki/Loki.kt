package com.valhalla.loki

import android.app.Application
import com.valhalla.loki.di.initKoin
import com.valhalla.loki.model.SelfPermissionGrabber
import org.koin.android.ext.android.inject

class Loki : Application() {

    /** `by inject()` is lazy, so it resolves on first use — which is after [initKoin] below. */
    private val selfPermissionGrabber: SelfPermissionGrabber by inject()

    override fun onCreate() {
        super.onCreate()
        initKoin()
        // Here rather than in MainActivity, for two reasons. A Koin binding nobody resolves is
        // never constructed, so a grabber that only wired itself up in its own initialiser would
        // never run at all. And ShizukuProvider is a ContentProvider, installed *before* this
        // method, so this is the earliest point at which the sticky binder listener can be
        // registered without missing the delivery that has usually already happened.
        //
        // What this does NOT do is grant anything. onCreate runs on every process start, including
        // the headless ones a file picker causes by reading LokiDocumentsProvider, and a fatal
        // grant there would kill the appId and relaunch Loki over an app the user was using. The
        // sweep therefore waits for MainActivity to call onUiPresent(); registering the listener is
        // all that has to happen this early.
        //
        // start() does not block: the plan is built from binder calls on an IO thread, and root is
        // probed only if there is actually something left to grant.
        selfPermissionGrabber.start()
    }
}
