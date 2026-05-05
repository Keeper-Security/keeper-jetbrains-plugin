package keepersecurity.util

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts field values from Keeper JSON record payloads (same shape as [keepersecurity.action.KeeperSecretAction]).
 */
object KeeperRecordFieldExtractor {

    /**
     * Extracts a field value from a Keeper JSON record.
     * Handles legacy and new formats, simple fields (`password`), and bracket notation (`address[zip]`).
     */
    fun extractFieldValue(jsonObject: JsonObject, fieldPath: String, logger: Logger): String? {
        logger.info("=== EXTRACTING FIELD: $fieldPath ===")
        logger.info("JSON keys available: ${jsonObject.keys.joinToString(", ")}")

        val bracketIndex = fieldPath.indexOf('[')

        if (bracketIndex > 0 && fieldPath.endsWith(']')) {
            val parentFieldName = fieldPath.substring(0, bracketIndex).removePrefix("custom.")
            val subFieldName = fieldPath.substring(bracketIndex + 1, fieldPath.length - 1)

            logger.info("Extracting complex field: parent='$parentFieldName', subField='$subFieldName'")

            val parentObject = findFieldObjectAnywhere(jsonObject, parentFieldName, logger)

            if (parentObject != null) {
                val result = parentObject[subFieldName]?.jsonPrimitive?.contentOrNull
                if (result != null) {
                    logger.info("Found sub-field '$subFieldName' in parent '$parentFieldName': ${result.take(10)}...")
                    return result
                } else {
                    logger.warn("Found parent '$parentFieldName' but sub-field '$subFieldName' not found in it")
                }
            } else {
                logger.warn("Parent field '$parentFieldName' not found anywhere in JSON")
            }

            return null
        } else {
            val fieldName = fieldPath.removePrefix("custom.")
            logger.info("Extracting simple field: '$fieldName'")

            val result = findFieldValueAnywhere(jsonObject, fieldName, logger)

            if (result != null) {
                logger.info("Found field '$fieldName': ${result.take(10)}...")
            } else {
                logger.warn("Field '$fieldName' not found anywhere in JSON")
            }

            return result
        }
    }

    private fun findFieldObjectAnywhere(jsonObject: JsonObject, fieldName: String, logger: Logger): JsonObject? {
        logger.info("Searching for field object '$fieldName' in all locations...")

        jsonObject[fieldName]?.let { value ->
            if (value is JsonObject) {
                logger.info("Found '$fieldName' as top-level object")
                return value
            }
        }

        jsonObject["fields"]?.jsonArray?.forEach { fieldElement ->
            val fieldObj = fieldElement.jsonObject
            val type = fieldObj["type"]?.jsonPrimitive?.contentOrNull

            if (type == fieldName) {
                logger.info("Found '$fieldName' in 'fields' array")
                val valueArray = fieldObj["value"]?.jsonArray
                if (!valueArray.isNullOrEmpty()) {
                    val firstValue = valueArray[0]
                    if (firstValue is JsonObject) {
                        logger.info("Extracted object from 'value' array")
                        return firstValue
                    }
                }
            }
        }

        jsonObject["custom"]?.jsonArray?.forEach { customFieldElement ->
            val customFieldObj = customFieldElement.jsonObject
            val type = customFieldObj["type"]?.jsonPrimitive?.contentOrNull
            val label = customFieldObj["label"]?.jsonPrimitive?.contentOrNull

            if (type == fieldName ||
                label == fieldName ||
                label?.replace("\\s".toRegex(), "_") == fieldName) {
                logger.info("Found '$fieldName' in 'custom' array (type='$type', label='$label')")
                val valueArray = customFieldObj["value"]?.jsonArray
                if (!valueArray.isNullOrEmpty()) {
                    val firstValue = valueArray[0]
                    if (firstValue is JsonObject) {
                        logger.info("Extracted object from 'value' array in 'custom'")
                        return firstValue
                    }
                }
            }
        }

        jsonObject["custom_fields"]?.jsonArray?.forEach { customFieldElement ->
            val customFieldObj = customFieldElement.jsonObject
            val name = customFieldObj["name"]?.jsonPrimitive?.contentOrNull?.removeSuffix(":")

            if (name == fieldName || name?.replace("\\s".toRegex(), "_") == fieldName) {
                logger.info("Found '$fieldName' in 'custom_fields' array (name='$name')")
                val value = customFieldObj["value"]
                if (value is JsonObject) {
                    logger.info("Value is a JsonObject in 'custom_fields'")
                    return value
                }
            }
        }

        logger.info("Field object '$fieldName' not found in any location")
        return null
    }

    private fun findFieldValueAnywhere(jsonObject: JsonObject, fieldName: String, logger: Logger): String? {
        logger.info("Searching for field value '$fieldName' in all locations...")

        jsonObject[fieldName]?.jsonPrimitive?.contentOrNull?.let { value ->
            logger.info("Found '$fieldName' as top-level primitive")
            return value
        }

        jsonObject["fields"]?.jsonArray?.forEach { fieldElement ->
            val fieldObj = fieldElement.jsonObject
            val type = fieldObj["type"]?.jsonPrimitive?.contentOrNull

            if (type == fieldName) {
                val valueArray = fieldObj["value"]?.jsonArray
                if (!valueArray.isNullOrEmpty()) {
                    val firstValue = valueArray[0]
                    val content = firstValue.jsonPrimitive.contentOrNull
                    if (content != null) {
                        logger.info("Extracted string value from 'value' array in 'fields'")
                        return content
                    }
                }
            }
        }

        jsonObject["custom"]?.jsonArray?.forEach { customFieldElement ->
            val customFieldObj = customFieldElement.jsonObject
            val type = customFieldObj["type"]?.jsonPrimitive?.contentOrNull
            val label = customFieldObj["label"]?.jsonPrimitive?.contentOrNull

            if (type == fieldName ||
                label == fieldName ||
                label?.replace("\\s".toRegex(), "_") == fieldName) {
                val valueArray = customFieldObj["value"]?.jsonArray
                if (!valueArray.isNullOrEmpty()) {
                    val firstValue = valueArray[0]
                    val content = firstValue.jsonPrimitive.contentOrNull
                    if (content != null) {
                        logger.info("Extracted string value from 'value' array in 'custom'")
                        return content
                    }
                }
            }
        }

        jsonObject["custom_fields"]?.jsonArray?.forEach { customFieldElement ->
            val customFieldObj = customFieldElement.jsonObject
            val name = customFieldObj["name"]?.jsonPrimitive?.contentOrNull?.removeSuffix(":")

            if (name == fieldName || name?.replace("\\s".toRegex(), "_") == fieldName) {
                val value = customFieldObj["value"]
                val content = value?.jsonPrimitive?.contentOrNull
                if (content != null) {
                    logger.info("Extracted string value from 'custom_fields'")
                    return content
                }
            }
        }

        logger.info("Field value '$fieldName' not found in any location")
        return null
    }
}
