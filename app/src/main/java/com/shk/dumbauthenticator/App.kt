package com.shk.dumbauthenticator

import android.app.Activity
import android.app.Application
import android.os.Bundle

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                val prev = startedCount
                startedCount++
                if (prev == 0 && backgrounded) {
                    isUnlocked = false
                    backgrounded = false
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedCount > 0) startedCount--
                if (startedCount == 0 && subIntents == 0) backgrounded = true
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    companion object {
        @Volatile var isUnlocked: Boolean = false
        @Volatile var pendingOtpauth: String? = null

        private var startedCount = 0
        private var subIntents = 0
        private var backgrounded = false

        fun beginSubIntent() { subIntents++ }
        fun endSubIntent()   { if (subIntents > 0) subIntents-- }
    }
}
