package com.jarvis2.app.integrations

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract

data class Contact(
    val id: String,
    val name: String,
    val phone: String?,
    val email: String? = null,
    // Libellés/groupes natifs (feature "Libellés" de l'appli Contacts/Google
    // Contacts) -- portage Newjarvis/ContactsController fusion task #5 REDO,
    // absent du premier portage : JARVIS ne connaissait jusqu'ici jamais les
    // libellés crees manuellement par l'utilisateur dans son carnet natif.
    val labels: List<String> = emptyList(),
)

/** Reads/writes device contacts via ContactsContract — requires READ/WRITE_CONTACTS. */
class ContactsRepository(private val context: Context) {

    fun createContact(name: String, phone: String? = null, email: String? = null): Boolean {
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build()
        )
        if (!phone.isNullOrBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )
        }
        if (!email.isNullOrBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                    .build()
            )
        }
        return try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            true
        } catch (e: Exception) {
            false
        }
    }

    // Meme raison que CalendarRepository (voir sa doc) : listContacts() et
    // les deux fonctions loadXById() ci-dessous n'etaient protegees par
    // AUCUN try/catch alors qu'elles tournent a chaque commande "contact"
    // -- une permission READ_CONTACTS revoquee ou un provider tiers en
    // erreur faisait planter toute l'appli en pleine "reflexion" (task
    // #326/#328). Degrade desormais vers une liste vide plutot que de
    // crasher.
    fun listContacts(limit: Int = 100): List<Contact> = runCatching {
        val phonesById = loadPhonesById()
        val emailsById = loadEmailsById()
        val labelsById = loadLabelsById()

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        )
        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI, projection, null, null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC",
        ) ?: return@runCatching emptyList()

        cursor.use {
            val result = mutableListOf<Contact>()
            while (it.moveToNext() && result.size < limit) {
                val id = it.getString(0)
                val name = it.getString(1) ?: continue
                result.add(Contact(id = id, name = name, phone = phonesById[id], email = emailsById[id], labels = labelsById[id].orEmpty()))
            }
            result
        }
    }.getOrDefault(emptyList())

    /**
     * Pre-charge un id -> premier numero de telephone, en une seule requete
     * sur CommonDataKinds.Phone (table separee de Contacts._ID cote
     * ContactsContract). listContacts() se contentait auparavant de mettre
     * phone=null en dur, ce qui empechait toute presentation "detaillee"
     * (voir CommandRouter.formatContacts) d'afficher un vrai numero.
     */
    private fun loadPhonesById(): Map<String, String> = runCatching {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null,
        ) ?: return@runCatching emptyMap()

        cursor.use {
            val result = mutableMapOf<String, String>()
            while (it.moveToNext()) {
                val contactId = it.getString(0) ?: continue
                val number = it.getString(1) ?: continue
                // Garde uniquement le premier numero rencontre par contact --
                // suffisant pour l'affichage, evite de compliquer Contact
                // avec une liste de numeros pour un besoin d'UI simple.
                if (!result.containsKey(contactId)) result[contactId] = number
            }
            result
        }
    }.getOrDefault(emptyMap())

    /**
     * Meme principe que [loadPhonesById] mais pour l'email -- ajoute apres
     * coup car listContacts() ne remontait aucun email, ce qui forcait
     * l'IA locale a en inventer un ("...@exemple.com") quand on lui
     * demandait l'email d'un contact au lieu de dire "pas d'email
     * enregistre" (voir le nouveau matcher dedie dans CommandRouter.kt).
     */
    private fun loadEmailsById(): Map<String, String> = runCatching {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Email.CONTACT_ID,
            ContactsContract.CommonDataKinds.Email.ADDRESS,
        )
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI, projection, null, null, null,
        ) ?: return@runCatching emptyMap()

        cursor.use {
            val result = mutableMapOf<String, String>()
            while (it.moveToNext()) {
                val contactId = it.getString(0) ?: continue
                val address = it.getString(1) ?: continue
                if (!result.containsKey(contactId)) result[contactId] = address
            }
            result
        }
    }.getOrDefault(emptyMap())
    /**
     * Precharge id -> libelles/groupes (feature "Libellés" native, voir
     * [Contact.labels]) en 2 requetes (memberships puis titres de groupe),
     * plutot qu'une paire de requetes PAR CONTACT comme Newjarvis/
     * ContactsController.getContactLabels -- meme principe de batching que
     * [loadPhonesById]/[loadEmailsById] ci-dessus, pour ne pas degrader les
     * performances avec un gros carnet d'adresses.
     */
    private fun loadLabelsById(): Map<String, List<String>> = runCatching {
        val groupTitles = mutableMapOf<Long, String>()
        context.contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE, ContactsContract.Groups.DELETED),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(2) != 0) continue // groupe supprime (fantome), voir listAllLabels
                val title = c.getString(1) ?: continue
                groupTitles[c.getLong(0)] = title
            }
        }
        if (groupTitles.isEmpty()) return@runCatching emptyMap()

        val result = mutableMapOf<String, MutableList<String>>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID),
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val contactId = c.getString(0) ?: continue
                val groupId = c.getLong(1)
                val title = groupTitles[groupId] ?: continue
                result.getOrPut(contactId) { mutableListOf() }.add(title)
            }
        }
        result
    }.getOrDefault(emptyMap())

    /** Normalise casse/accents (portage Newjarvis/ContactsController.normalizeLabel) pour que "École"/"ecole"/"ÉCOLE" désignent le même libellé, insensible aux limites du LIKE SQLite sur les accents. */
    private fun normalizeLabel(s: String): String =
        java.text.Normalizer.normalize(s.lowercase().trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\s+"), " ")

    /**
     * Tous les libellés/groupes de contacts existants dans le carnet natif
     * (portage Newjarvis/ContactsController.listAllLabels, fusion task #5
     * REDO -- absent du premier portage : JARVIS ne connaissait aucun
     * libellé créé par l'utilisateur dans son appli Contacts).
     */
    fun listAllLabels(): List<String> = runCatching {
        val labels = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(ContactsContract.Groups.TITLE, ContactsContract.Groups.DELETED),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(1) != 0) continue
                val title = c.getString(0)
                if (!title.isNullOrBlank()) labels.add(title)
            }
        }
        labels.distinct().sortedBy { it.lowercase() }
    }.getOrDefault(emptyList())

    /**
     * Contacts portant un libellé précis (recherche partielle, insensible
     * casse/accents) -- portage Newjarvis/ContactsController.
     * listContactsByLabel. Reutilise [listContacts] pour beneficier des
     * memes numero/email deja charges, plutot que de dupliquer la lecture.
     */
    fun listContactsByLabel(label: String): List<Contact> {
        if (label.isBlank()) return emptyList()
        val target = normalizeLabel(label)
        return listContacts(limit = 500).filter { c -> c.labels.any { normalizeLabel(it).contains(target) } }
    }
}
