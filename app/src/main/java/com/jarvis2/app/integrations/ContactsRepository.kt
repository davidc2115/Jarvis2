package com.jarvis2.app.integrations

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract

data class Contact(val id: String, val name: String, val phone: String?)

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

    fun listContacts(limit: Int = 100): List<Contact> {
        val phonesById = loadPhonesById()

        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        )
        val cursor = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI, projection, null, null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC",
        ) ?: return emptyList()

        return cursor.use {
            val result = mutableListOf<Contact>()
            while (it.moveToNext() && result.size < limit) {
                val id = it.getString(0)
                val name = it.getString(1) ?: continue
                result.add(Contact(id = id, name = name, phone = phonesById[id]))
            }
            result
        }
    }

    /**
     * Pre-charge un id -> premier numero de telephone, en une seule requete
     * sur CommonDataKinds.Phone (table separee de Contacts._ID cote
     * ContactsContract). listContacts() se contentait auparavant de mettre
     * phone=null en dur, ce qui empechait toute presentation "detaillee"
     * (voir CommandRouter.formatContacts) d'afficher un vrai numero.
     */
    private fun loadPhonesById(): Map<String, String> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null,
        ) ?: return emptyMap()

        return cursor.use {
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
    }
}
