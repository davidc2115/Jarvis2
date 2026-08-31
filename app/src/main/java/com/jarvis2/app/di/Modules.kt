package com.jarvis2.app.di

import androidx.room.Room
import com.jarvis2.app.ai.AiEngineManager
import com.jarvis2.app.ai.CommandRouter
import com.jarvis2.app.ai.MemoryStore
import com.jarvis2.app.ai.WebSearchTool
import com.jarvis2.app.data.SettingsDataStore
import com.jarvis2.app.data.db.AppDatabase
import com.jarvis2.app.filegen.DocxGenerator
import com.jarvis2.app.filegen.FileGenRouter
import com.jarvis2.app.filegen.KmlGenerator
import com.jarvis2.app.filegen.PdfGenerator
import com.jarvis2.app.filegen.XlsxGenerator
import com.jarvis2.app.filegen.ZipGenerator
import com.jarvis2.app.integrations.AlarmController
import com.jarvis2.app.integrations.BluetoothController
import com.jarvis2.app.integrations.CalendarRepository
import com.jarvis2.app.integrations.ContactsRepository
import com.jarvis2.app.integrations.FlashlightController
import com.jarvis2.app.integrations.GoogleAuthController
import com.jarvis2.app.integrations.IntegrationsRouter
import com.jarvis2.app.integrations.LocationProvider
import com.jarvis2.app.integrations.MailComposer
import com.jarvis2.app.integrations.MailReader
import com.jarvis2.app.integrations.StorageAccess
import com.jarvis2.app.integrations.WifiController
import com.jarvis2.app.obsidian.VaultRepository
import com.jarvis2.app.ui.chat.ChatViewModel
import com.jarvis2.app.ui.filetools.FileToolsViewModel
import com.jarvis2.app.ui.graph.GraphViewModel
import com.jarvis2.app.ui.integrations.IntegrationsViewModel
import com.jarvis2.app.ui.settings.SettingsViewModel
import com.jarvis2.app.ui.vault.VaultViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val dataModule = module {
    single {
        Room.databaseBuilder(androidContext(), AppDatabase::class.java, "jarvis2.db")
            .fallbackToDestructiveMigration()
            .build()
    }
    single { get<AppDatabase>().chatDao() }
    single { get<AppDatabase>().memoryDao() }
    single { SettingsDataStore(androidContext()) }
}

val aiModule = module {
    single { AiEngineManager(androidContext(), get()) }
    single { MemoryStore(get()) }
    single { WebSearchTool(androidContext(), get()) }
    single { CommandRouter(get(), get(), get(), get(), get(), get()) }
}

val integrationsModule = module {
    single { FlashlightController(androidContext()) }
    single { BluetoothController(androidContext()) }
    single { WifiController(androidContext()) }
    single { LocationProvider(androidContext()) }
    single { CalendarRepository(androidContext()) }
    single { ContactsRepository(androidContext()) }
    single { MailComposer(androidContext()) }
    single { StorageAccess(androidContext()) }
    single { AlarmController(androidContext()) }
    single { GoogleAuthController(androidContext()) }
    single { MailReader(get()) }
    single { IntegrationsRouter(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }

    single { PdfGenerator(androidContext()) }
    single { ZipGenerator(androidContext()) }
    single { KmlGenerator(androidContext()) }
    single { DocxGenerator(androidContext()) }
    single { XlsxGenerator(androidContext()) }
    single { FileGenRouter(get(), get(), get(), get(), get()) }

    single { VaultRepository(androidContext(), get(), get()) }
}

val viewModelModule = module {
    viewModel { ChatViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { VaultViewModel(get()) }
    viewModel { GraphViewModel(get()) }
    viewModel { IntegrationsViewModel(get()) }
    viewModel { FileToolsViewModel(get()) }
    viewModel { SettingsViewModel(get(), get(), get(), androidContext()) }
}

/** Small helper so module bodies above read as `androidContext()` like the rest of the Koin ecosystem. */
private fun org.koin.core.scope.Scope.androidContext(): android.content.Context = get()
