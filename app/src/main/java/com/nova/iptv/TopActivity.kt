package com.nova.iptv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/** The Nova screen in front now (for popups that come from background work: USB missing, recordings…). */
object TopActivity {
    private var top: WeakReference<Activity>? = null

    fun get(): Activity? = top?.get()?.takeIf { !it.isFinishing }

    fun watch(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { top = WeakReference(a) }
            override fun onActivityPaused(a: Activity) { if (top?.get() === a) top = null }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
