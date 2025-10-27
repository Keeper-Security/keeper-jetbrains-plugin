package keepersecurity.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
data class KeeperFolder(
    @SerialName("uid") val folderUid: String,
    val name: String,
    val type: String? = null,
    val details: String? = null,
    val flags: String? = null,
    @SerialName("parent_uid") val parentUid: String? = null
)

@Serializable
data class KeeperRecord(
    @SerialName("record_uid") val recordUid: String,
    val title: String,
    val fields: List<KeeperField>? = null,
    val custom: List<KeeperCustomField>? = null
)

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