package keepersecurity.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Parsed Commander payloads for Keeper's two vault models:
 *
 *  - **Classic** — the existing folder/permission model (`record-*`, `mkdir`, etc.)
 *  - **Nested Shared Folders** — the v3 API model (`nsf-*` commands; see
 *    [Nested Shared Folder command reference](https://docs.keeper.io/keeperpam/commander-cli/command-reference/nested-shared-folder))
 *
 * Both models have folders and records (create, update, delete, share, etc.).
 * Folder rows from `ls --format=json` are tagged with [KeeperFolder.source];
 * record rows from `list --format json` are tagged with [KeeperRecord.recordCategory].
 */

@Serializable
data class KeeperFolder(
    @SerialName("uid") val folderUid: String,
    val name: String,
    val type: String? = null,
    val details: String? = null,
    val flags: String? = null,
    @SerialName("parent_uid") val parentUid: String? = null,
    /**
     * Source discriminator returned by `ls --format=json` in vaults that have
     * Nested Shared Folders enabled. Commander releases differ:
     *  - current: `"classic_folder"` / `"nested_share_folder"`
     *  - older legacy wire values also supported
     * Missing `source` is treated as classic.
     */
    val source: String? = null
) {
    companion object {
        /** Classic vault folder (legacy Commander wire value). */
        const val SOURCE_LEGACY = "Legacy"

        /** Nested Shared Folder (legacy Commander wire value). */
        const val SOURCE_KEEPER_DRIVE = "KeeperDrive"

        /** Classic vault folder (newer Commander wire value). */
        const val SOURCE_CLASSIC_FOLDER = "classic_folder"

        /** Nested Shared Folder (newer Commander wire value). Primary on current builds. */
        const val SOURCE_NESTED_SHARE_FOLDER = "nested_share_folder"

        /**
         * Wire values that mean Nested Shared Folder on `ls --format=json -f -R` rows.
         * Current Commander: [SOURCE_NESTED_SHARE_FOLDER]. Legacy: [SOURCE_KEEPER_DRIVE].
         */
        private val NESTED_SHARE_SOURCES = setOf(
            SOURCE_NESTED_SHARE_FOLDER,
            SOURCE_KEEPER_DRIVE,
        )

        /**
         * Wire values that mean Classic vault folder. Current Commander:
         * [SOURCE_CLASSIC_FOLDER]. Legacy: [SOURCE_LEGACY]. Null/missing → classic.
         */
        private val CLASSIC_SOURCES = setOf(
            SOURCE_CLASSIC_FOLDER,
            SOURCE_LEGACY,
        )

        /** True when [source] identifies a Nested Shared Folder row. */
        fun isNestedShareSource(source: String?): Boolean =
            source != null && NESTED_SHARE_SOURCES.any { it.equals(source, ignoreCase = true) }

        /** True when [source] identifies a Classic vault folder row. */
        fun isClassicSource(source: String?): Boolean =
            source == null || CLASSIC_SOURCES.any { it.equals(source, ignoreCase = true) }
    }

    /** True when this folder is a Nested Shared Folder (not Classic Vault). */
    val isNestedShare: Boolean
        get() = isNestedShareSource(source)

    /** @see isNestedShare */
    val isKeeperDrive: Boolean
        get() = isNestedShare
}

@Serializable
data class KeeperRecord(
    @SerialName("record_uid") val recordUid: String,
    val title: String,
    val fields: List<KeeperField>? = null,
    val custom: List<KeeperCustomField>? = null,
    /**
     * Vault-source discriminator on a record row from `list --format json`.
     * Commander releases differ:
     *  - current: `"Classic"` / `"Nested"`
     *  - older legacy wire values also supported
     * Missing `record_category` is treated as classic.
     */
    @SerialName("record_category") val recordCategory: String? = null
) {
    companion object {
        /** Nested Shared record (legacy Commander wire value). */
        const val CATEGORY_KEEPER_DRIVE = "KeeperDrive"

        /** Nested Shared record (newer Commander wire value). Primary on current builds. */
        const val CATEGORY_NESTED = "Nested"

        /** Classic-vault record. Same on all Commander builds. */
        const val CATEGORY_CLASSIC = "Classic"

        /**
         * Wire values that mean Nested Shared record on `list --format json` rows.
         * Current Commander: [CATEGORY_NESTED]. Legacy: [CATEGORY_KEEPER_DRIVE].
         */
        private val NESTED_SHARE_CATEGORIES = setOf(
            CATEGORY_NESTED,
            CATEGORY_KEEPER_DRIVE,
        )

        /** True when [recordCategory] identifies a Nested Shared record row. */
        fun isNestedShareCategory(recordCategory: String?): Boolean =
            recordCategory != null &&
                NESTED_SHARE_CATEGORIES.any { it.equals(recordCategory, ignoreCase = true) }

        /** True when [recordCategory] identifies a Classic vault record row. */
        fun isClassicCategory(recordCategory: String?): Boolean =
            recordCategory == null ||
                recordCategory.equals(CATEGORY_CLASSIC, ignoreCase = true)
    }

    /** True when this record lives in a Nested Shared Folder (not Classic Vault). */
    val isNestedShare: Boolean
        get() = isNestedShareCategory(recordCategory)

    /** @see isNestedShare */
    val isKeeperDrive: Boolean
        get() = isNestedShare
}

@Serializable
data class KeeperField(
    val type: String,
    val label: String? = null,
    val value: List<JsonElement>? = null  // Changed from List<String> to support complex field types
)

@Serializable
data class KeeperCustomField(
    val type: String? = null,
    val label: String? = null,
    val value: List<JsonElement>? = null  // Changed from List<String> to support complex field types
)

@Serializable
data class KeeperSecret(
    val password: String? = null,
    // Add other fields as needed
)

@Serializable
data class GeneratedPassword(
    val password: String
)

// ============================================================================
// HELPER EXTENSIONS for working with JsonElement values
// ============================================================================

/**
 * Helper to extract string value from JsonElement
 */
fun JsonElement.asStringOrNull(): String? = when (this) {
    is JsonPrimitive -> this.contentOrNull
    is JsonObject -> this.toString() // Fallback: convert object to JSON string
    else -> null
}

/**
 * Helper to extract object from JsonElement
 */
fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

/**
 * Helper to check if JsonElement is an object
 */
fun JsonElement.isObject(): Boolean = this is JsonObject

/**
 * Extension for KeeperField to get displayable value preview
 * Handles all Keeper Commander field types according to:
 * https://docs.keeper.io/en/keeperpam/commander-cli/command-reference/record-commands/record-type-commands
 */
fun KeeperField.getDisplayValue(): String {
    if (value.isNullOrEmpty()) return "[empty]"
    
    return when (type) {
        // Simple text fields - extract as string
        "text", "secret", "title", "login", "password", "url", "email", "phone", 
        "note", "multiline", "accountNumber", "groupNumber", "licenseNumber", 
        "pinCode", "company" -> {
            value.firstOrNull()?.asStringOrNull() ?: "[empty]"
        }
        
        // Name field - structured object with first, middle, last
        "name" -> {
            val nameObj = value.firstOrNull()?.asObjectOrNull()
            if (nameObj != null) {
                val first = nameObj["first"]?.jsonPrimitive?.contentOrNull ?: ""
                val middle = nameObj["middle"]?.jsonPrimitive?.contentOrNull ?: ""
                val last = nameObj["last"]?.jsonPrimitive?.contentOrNull ?: ""
                "$first $middle $last".trim()
            } else "[empty]"
        }
        
        // Address field - structured object with street, city, state, zip, etc.
        "address" -> {
            val addrObj = value.firstOrNull()?.asObjectOrNull()
            if (addrObj != null) {
                val street = addrObj["street1"]?.jsonPrimitive?.contentOrNull ?: ""
                val city = addrObj["city"]?.jsonPrimitive?.contentOrNull ?: ""
                val state = addrObj["state"]?.jsonPrimitive?.contentOrNull ?: ""
                "$street, $city, $state".trim(',', ' ')
            } else "[empty]"
        }
        
        // Host field - structured object with hostName and port
        "host" -> {
            val hostObj = value.firstOrNull()?.asObjectOrNull()
            if (hostObj != null) {
                val hostname = hostObj["hostName"]?.jsonPrimitive?.contentOrNull ?: ""
                val port = hostObj["port"]?.jsonPrimitive?.contentOrNull ?: ""
                if (port.isNotEmpty()) "$hostname:$port" else hostname
            } else "[empty]"
        }
        
        // Payment Card - structured object with card details
        "paymentCard" -> {
            val cardObj = value.firstOrNull()?.asObjectOrNull()
            if (cardObj != null) {
                val lastFour = cardObj["cardNumber"]?.jsonPrimitive?.contentOrNull?.takeLast(4) ?: "****"
                "[Card ending in $lastFour]"
            } else "[empty]"
        }
        
        // Bank Account - structured object with account details
        "bankAccount" -> {
            val acctObj = value.firstOrNull()?.asObjectOrNull()
            if (acctObj != null) {
                val accountType = acctObj["accountType"]?.jsonPrimitive?.contentOrNull ?: "Account"
                val lastFour = acctObj["accountNumber"]?.jsonPrimitive?.contentOrNull?.takeLast(4) ?: "****"
                "[$accountType ending in $lastFour]"
            } else "[empty]"
        }
        
        // Security Question - structured object with question and answer
        "securityQuestion" -> {
            val sqObj = value.firstOrNull()?.asObjectOrNull()
            if (sqObj != null) {
                val question = sqObj["question"]?.jsonPrimitive?.contentOrNull ?: ""
                "[Q: ${question.take(30)}...]"
            } else "[empty]"
        }
        
        // Date fields - stored as Unix milliseconds
        "date", "birthDate", "expirationDate" -> {
            val timestamp = value.firstOrNull()?.jsonPrimitive?.longOrNull
            if (timestamp != null && timestamp > 0) {
                try {
                    java.time.Instant.ofEpochMilli(timestamp).toString().substringBefore('T')
                } catch (e: Exception) {
                    "[date: $timestamp]"
                }
            } else "[no date]"
        }
        
        // One-Time Password - may contain seed
        "oneTimeCode", "otp" -> {
            "[OTP configured]"
        }
        
        // Private Key / Key Pair - sensitive data
        "keyPair", "privateKey" -> {
            "[Private key data]"
        }
        
        // Passkey - passwordless login
        "passkey" -> {
            "[Passkey configured]"
        }
        
        // Reference fields - typically empty or contain UIDs
        "fileRef", "addressRef", "cardRef" -> {
            if (value.isEmpty()) "[no reference]" else "[${value.size} reference(s)]"
        }
        
        // Default: try to extract as string or indicate complex type
        else -> {
            val firstVal = value.firstOrNull()
            when {
                firstVal == null -> "[empty]"
                firstVal.isObject() -> "[${type} data]"
                else -> firstVal.asStringOrNull()?.take(50) ?: "[complex value]"
            }
        }
    }
}

/**
 * Extension for KeeperCustomField to get displayable value preview
 */
fun KeeperCustomField.getDisplayValue(): String {
    if (value.isNullOrEmpty()) return "[empty]"
    
    val firstVal = value.firstOrNull()
    return when {
        firstVal == null -> "[empty]"
        firstVal.isObject() -> "[complex data]"
        else -> firstVal.asStringOrNull()?.take(50) ?: "[value]"
    }
}