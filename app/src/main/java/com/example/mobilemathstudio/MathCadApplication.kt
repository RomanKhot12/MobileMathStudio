package com.example.mobilemathstudio

import android.app.Application

class MathCadApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
    }
}
