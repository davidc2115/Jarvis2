package com.jarvis2.app

import android.app.Application
import com.jarvis2.app.di.aiModule
import com.jarvis2.app.di.dataModule
import com.jarvis2.app.di.integrationsModule
import com.jarvis2.app.di.viewModelModule
import com.jarvis2.app.proactive.ProactiveScheduler
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
        // Axe Proactivite JARVIS (task #242) : programme les rappels d'evenements
        // + le briefing du matin des le lancement de l'app -- Koin doit deja etre
        // demarre (les Worker injectent CalendarRepository/SettingsDataStore via
        // KoinComponent), d'ou l'appel juste apres startKoin() ci-dessus.
        ProactiveScheduler.schedule(this)
    }
}
