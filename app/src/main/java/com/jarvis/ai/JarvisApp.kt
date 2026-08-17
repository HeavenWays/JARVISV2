package com.jarvis.ai

import android.app.Application
import com.jarvis.ai.core.Jarvis

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Jarvis.init(this)
    }
}
