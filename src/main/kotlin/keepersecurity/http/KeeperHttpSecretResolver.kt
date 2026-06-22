package keepersecurity.http

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import keepersecurity.util.KeeperCommandUtils
import keepersecurity.util.KeeperJsonUtils
import keepersecurity.util.KeeperRecordFieldExtractor

@OptIn(ExperimentalSerializationApi::class)
object KeeperHttpSecretResolver {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    /**
     * Fetches a Keeper record via the persistent shell and returns the requested field value.
     *
     * Throws on any failure (invalid input, missing field, shell error). The HTTP Client surfaces
     * the throw as a variable-resolution error and skips the request, rather than substituting the
     * error message into the URL, headers, or body.
     */
    private val UID_PATTERN = Regex("""^[A-Za-z0-9_-]+$""")

    fun resolveRecordField(recordUid: String, fieldPath: String, logger: Logger): String {
        val trimmedUid = recordUid.trim()
        val trimmedField = fieldPath.trim()
        if (trimmedUid.isEmpty() || trimmedField.isEmpty()) {
            throw IllegalArgumentException("Keeper record UID and field path must be non-empty")
        }
        if (!UID_PATTERN.matches(trimmedUid)) {
            throw IllegalArgumentException("Invalid Keeper record UID format: '$trimmedUid'")
        }

        return try {
            val secretJson = getKeeperJsonFromShell(trimmedUid, logger)
            val jsonElement = json.parseToJsonElement(secretJson)
            val extracted = KeeperRecordFieldExtractor.extractFieldValue(
                jsonElement.jsonObject,
                trimmedField,
                logger,
            )
            extracted ?: throw IllegalStateException(
                "Keeper field '$trimmedField' not found in record $trimmedUid",
            )
        } catch (e: Exception) {
            logger.warn("Keeper HTTP variable resolution failed: ${e.message}", e)
            throw e
        }
    }

    private fun getKeeperJsonFromShell(uid: String, logger: Logger): String {
        val output = KeeperCommandUtils.executeCommandWithRetry(
            "get $uid --format json",
            KeeperCommandUtils.Presets.jsonObject(maxRetries = 3),
            logger,
        )
        return KeeperJsonUtils.extractJsonObject(output, logger)
    }
}
