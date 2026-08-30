package com.jarvis2.app

import android.app.Application
import com.jarvis2.app.di.aiModule
import com.jarvis2.app.di.dataModule
import com.jarvis2.app.di.integrationsModule
import com.jarvis2.app.di.viewModelModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

/**
 * Application entry point. Wires up Koin DI. Everything the assistant can do
 * (AI engines, phone integrations, file generation, the Obsidian vault) is
 * registered here as a Koin module so screens just inject what they need.
 */
class Jarvis2Application : Application() {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@Jarvis2Application)
            modules(dataModule, aiModule, integrationsModule, viewModelModule)
        }
    }
}
