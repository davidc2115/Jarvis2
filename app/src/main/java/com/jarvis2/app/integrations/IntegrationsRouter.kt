package com.jarvis2.app.integrations

/** Bag of all phone-integration singletons, handed to [com.jarvis2.app.ai.CommandRouter] and the UI. */
class IntegrationsRouter(
    val flashlight: FlashlightController,
    val bluetooth: BluetoothController,
    val wifi: WifiController,
    val location: LocationProvider,
    val calendar: CalendarRepository,
    val contacts: ContactsRepository,
    val mail: MailComposer,
    val storage: StorageAccess,
    val alarm: AlarmController,
    val mailReader: MailReader,
    val weather: WeatherController,
    // Fusion Phase 4a ("REPREND COMPLETEMENT NEWJARVIS") -- portage Newjarvis/
    // SmsController + PhoneController, capacite totalement absente jusqu'ici.
    val sms: SmsRepository,
    val phone: PhoneRepository,
)
