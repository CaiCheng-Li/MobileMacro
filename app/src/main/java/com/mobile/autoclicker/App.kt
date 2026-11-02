package com.mobile.autoclicker

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Use the wallpaper-based Material You palette on Android 12+.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
