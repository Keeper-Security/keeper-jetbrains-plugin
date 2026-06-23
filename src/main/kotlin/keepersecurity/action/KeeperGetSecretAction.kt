package keepersecurity.action

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.vfs.VirtualFile
import keepersecurity.service.KeeperShellService
import kotlinx.serialization.json.Json
import keepersecurity.model.KeeperRecord
import keepersecurity.model.getDisplayValue
import keepersecurity.util.KeeperJsonUtils
import kotlinx.serialization.ExperimentalSerializationApi
import keepersecurity.util.KeeperCommandUtils
import keepersecurity.ui.KeeperListPickerDialog
import keepersecurity.ui.KeeperListPickerItem
import keepersecurity.ui.KeeperVaultBadge
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Unified secret picker. Commander's `list --format json` returns both
 * Classic and Nested Shared records in a single payload (each row carries
 * a `record_category` discriminator), so a single menu item covers both vault
 * models — no Nested Shared Folder-specific subclass is needed. Each row
 * shows the record title with a Classic or Nested badge.
 *
 * The `keeper://<uid>/field/<field>` notation accepts both Classic and
 * Nested Shared record UIDs, so the editor-insertion path doesn't fork on
 * source.
 */
@OptIn(ExperimentalSerializationApi::class)
class KeeperGetSecretAction : AnAction("Get Keeper Secret") {

    private val taskTitle = "Fetching Keeper Secrets..."
    private val recordPickerTitle = "Keeper Records"
    private val emptyRecordsMessage = "No Keeper records found."

    private val logger = thisLogger()

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor: Editor = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR) ?: return

        object : Task.Backgroundable(project, taskTitle, false) {
            override fun run(indicator: ProgressIndicator) {
                

                // Step 1: Get list of records using persistent shell with retry logic
                val startTime = System.currentTimeMillis()

                indicator.text = "Syncing with Keeper vault..."
                KeeperCommandUtils.syncDownBestEffort(logger)

                indicator.text = "Fetching Keeper records..."
                val listJson = try {
                    // Add retry logic for the first run
                    KeeperCommandUtils.executeCommandWithRetry(
                        "list --format json",
                        KeeperCommandUtils.Presets.jsonArray(maxRetries = 3),
                        logger
                    )
                } catch (ex: Exception) {
                    logger.error("Failed to get record list from persistent shell", ex)
                    showError(project, "Failed to get Keeper record list: ${ex.message}")
                    return
                }

                val listDuration = System.currentTimeMillis() - startTime
                logger.info("List command executed in ${listDuration}ms")

                val records = try {
                    val jsonString = KeeperJsonUtils.extractJsonArray(listJson, logger)
                    json.decodeFromString<List<KeeperRecord>>(jsonString)
                } catch (ex: Exception) {
                    logger.error("Failed to parse list JSON", ex)
                    logger.error("Raw output was: $listJson")
                    showError(project, "Failed to parse Keeper list JSON: ${ex.message}")
                    return
                }

                // Build picker rows with Classic / Nested badges.
                val recordChoices = records.mapNotNull { rec ->
                    val uid = rec.recordUid
                    if (uid.isBlank()) return@mapNotNull null
                    RecordChoice(
                        title = rec.title.ifBlank { "Untitled" },
                        uid = uid,
                        isNested = rec.isKeeperDrive,
                    )
                }

                if (recordChoices.isEmpty()) {
                    showInfo(project, emptyRecordsMessage)
                    return
                }

                // Step 2: Ask user to select record (UI thread)
                ApplicationManager.getApplication().invokeLater {
                    val pickerItems = recordChoices.map { it.toPickerItem() }
                    val selected = KeeperListPickerDialog.pickItem(
                        project = project,
                        title = recordPickerTitle,
                        message = "Select a Keeper record (Classic or Nested Shared Folder):",
                        options = pickerItems,
                        initialSelection = pickerItems.first()
                    ) ?: return@invokeLater

                    val selectedRecord = recordChoices.find {
                        it.title == selected.label && it.isNested == (selected.badge == KeeperVaultBadge.NESTED)
                    } ?: recordChoices.find { it.toPickerItem() == selected }
                    val selectedUid = selectedRecord?.uid ?: return@invokeLater
                    val selectedTitle = selectedRecord.title

                    // Step 3: Fetch selected record details (background again)
                    object : Task.Backgroundable(project, "Fetching Record Details...", false) {
                        override fun run(indicator2: ProgressIndicator) {
                            indicator2.text = "Getting record details from persistent shell..."
                            
                            val recordStartTime = System.currentTimeMillis()
                            val recordJsonText = try {
                                KeeperShellService.executeCommand("get $selectedUid --format json", 30)
                            } catch (ex: Exception) {
                                logger.error("Failed to get record details from persistent shell", ex)
                                showError(project, "Failed to get Keeper record details: ${ex.message}")
                                return
                            }

                            val recordDuration = System.currentTimeMillis() - recordStartTime
                            logger.info("Get record command executed in ${recordDuration}ms")

                            if (recordJsonText.isBlank()) {
                                showError(project, "Failed to get Keeper record details.")
                                return
                            }

                            val recordJson = try {
                                val jsonString = KeeperJsonUtils.extractJsonObject(recordJsonText, logger)

                                json.decodeFromString<KeeperRecord>(jsonString)
                            } catch (ex: Exception) {
                                logger.error("Failed to parse record JSON", ex)
                                logger.error("Raw output was: $recordJsonText")
                                showError(project, "Failed to parse Keeper record JSON: ${ex.message}")
                                return
                            }

                            val fieldOptions = mutableListOf<Pair<String, String>>()

                            // Process standard fields
                            recordJson.fields?.forEach { field ->
                                if (field.type.isNotBlank() && !field.value.isNullOrEmpty()) {
                                    when (val firstValue = field.value.firstOrNull())  {
                                        // Complex object fields (address, name, host, bankAccount, paymentCard, securityQuestion, keyPair)
                                        is JsonObject -> {
                                            // Extract all non-empty sub-fields from the object
                                            firstValue.keys.forEach { subKey ->
                                                val subValue = firstValue[subKey]
                                                if (subValue is JsonPrimitive && !subValue.contentOrNull.isNullOrBlank()) {
                                                    val displayValue = subValue.contentOrNull?.take(30) ?: ""
                                                    // Display: "address.street1: 100 Main Street"
                                                    // Keeper Notation: "address[street1]"
                                                    fieldOptions.add(
                                                        "${field.type}.$subKey: $displayValue (standard)" to "${field.type}[$subKey]"
                                                    )
                                                }
                                            }
                                        }
                                        // Simple string value (login, password, url, text, etc.)
                                        is JsonPrimitive -> {
                                            val content = firstValue.contentOrNull
                                            if (!content.isNullOrBlank()) {
                                                val preview = content.take(50)
                                                // Display: "login: username"
                                                // Keeper Notation: "login"
                                                fieldOptions.add("${field.type}: $preview (standard)" to field.type)
                                            }
                                        }
                                        // Fallback for other types
                                        else -> {
                                            val preview = field.getDisplayValue().take(50)
                                            if (preview != "[empty]" && preview.isNotBlank()) {
                                                fieldOptions.add("${field.type}: $preview (standard)" to field.type)
                                            }
                                        }
                                    }
                                }
                            }

                            // Process custom fields
                            recordJson.custom?.forEach { customField ->
                                // For custom fields with labels (user-created custom fields)
                                if (!customField.label.isNullOrBlank() && !customField.value.isNullOrEmpty()) {
                                    val key = customField.label.replace("\\s".toRegex(), "_")
                                    when (val firstValue = customField.value.firstOrNull()) {
                                        is JsonObject -> {
                                            firstValue.keys.forEach { subKey ->
                                                val subValue = firstValue[subKey]
                                                if (subValue is JsonPrimitive && !subValue.contentOrNull.isNullOrBlank()) {
                                                    val displayValue = subValue.contentOrNull?.take(30) ?: ""
                                                    fieldOptions.add(
                                                        "${customField.label}.$subKey: $displayValue (custom)" to "custom.${key}[$subKey]"
                                                    )
                                                }
                                            }
                                        }
                                        is JsonPrimitive -> {
                                            val content = firstValue.contentOrNull
                                            if (!content.isNullOrBlank()) {
                                                val preview = content.take(50)
                                                fieldOptions.add("${customField.label}: $preview (custom)" to "custom.${key}")
                                            }
                                        }
                                        else -> {
                                            val preview = customField.getDisplayValue().take(50)
                                            if (preview != "[empty]" && preview.isNotBlank()) {
                                                fieldOptions.add("${customField.label}: $preview (custom)" to "custom.${key}")
                                            }
                                        }
                                    }
                                }
                                // For custom fields without labels (built-in types like name, phone, etc.)
                                else if (customField.label == null && !customField.type.isNullOrBlank() && !customField.value.isNullOrEmpty()) {
                                    when (val firstValue = customField.value.firstOrNull()) {
                                        is JsonObject -> {
                                            firstValue.keys.forEach { subKey ->
                                                val subValue = firstValue[subKey]
                                                if (subValue is JsonPrimitive && !subValue.contentOrNull.isNullOrBlank()) {
                                                    val displayValue = subValue.contentOrNull?.take(30) ?: ""
                                                    fieldOptions.add(
                                                        "${customField.type}.$subKey: $displayValue (custom)" to "${customField.type}[$subKey]"
                                                    )
                                                }
                                            }
                                        }
                                        is JsonPrimitive -> {
                                            val content = firstValue.contentOrNull
                                            if (!content.isNullOrBlank()) {
                                                val preview = content.take(50)
                                                fieldOptions.add("${customField.type}: $preview (custom)" to customField.type)
                                            }
                                        }
                                        else -> {
                                            // Handle other JsonElement types (JsonArray, null, etc.)
                                            val preview = customField.getDisplayValue().take(50)
                                            if (preview != "[empty]" && preview.isNotBlank()) {
                                                fieldOptions.add("${customField.type}: $preview (custom)" to customField.type)
                                            }
                                        }
                                    }
                                }
                            }

                            if (fieldOptions.isEmpty()) {
                                showInfo(project, "No fields with values found in selected record.")
                                return
                            }

                            logger.info("Found ${fieldOptions.size} fields in record '$selectedTitle'")

                            // Step 4: Ask user to pick field (UI thread)
                            ApplicationManager.getApplication().invokeLater {
                                val fieldLabels = fieldOptions.map { it.first }
                                val selectedFieldDisplay = KeeperListPickerDialog.pick(
                                    project = project,
                                    title = "Keeper Record Fields",
                                    message = "Select field from record '$selectedTitle':",
                                    options = fieldLabels,
                                    initialSelection = fieldLabels.first()
                                ) ?: return@invokeLater

                                val selectedFieldKey = fieldOptions.find { it.first == selectedFieldDisplay }?.second ?: return@invokeLater
                                val keeperNotation = "keeper://$selectedUid/field/$selectedFieldKey"
                                val isHttpFile = isHttpClientRequestFile(editor)
                                val insertText = insertionTextFor(isHttpFile, selectedUid, selectedFieldKey, keeperNotation)

                                logger.info("Insert text for editor: ${insertText.take(120)}…")

                                // Step 5: Insert into editor (UI write action)
                                WriteCommandAction.runWriteCommandAction(project) {
                                    val doc = editor.document
                                    val caretOffset = editor.caretModel.offset
                                    doc.insertString(caretOffset, insertText)
                                }

                                // Show success message
                                Messages.showInfoMessage(
                                    project,
                                    buildInsertionSuccessMessage(isHttpFile, insertText, keeperNotation),
                                    "Keeper Reference Added",
                                )
                            }
                        }
                    }.queue()
                }
            }
        }.queue()
    }


    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    /**
     * In JetBrains HTTP Client files (`.http` / `.rest`), insert the `$keeper` dynamic variable
     * so users do not need to paste the record UID manually. Elsewhere, keep `keeper://…` for .env and scripts.
     */
    private fun insertionTextFor(
        isHttpFile: Boolean,
        recordUid: String,
        fieldPath: String,
        keeperUriNotation: String,
    ): String = if (isHttpFile) {
        httpKeeperDynamicVariableSnippet(recordUid, fieldPath)
    } else {
        keeperUriNotation
    }

    /**
     * Detects HTTP Client request files via the FileType API. The `com.jetbrains.restClient`
     * plugin is an optional dependency, so its classes are only on the classpath when it is
     * loaded; the plugin check plus the try/catch keep the call safe on IDEs that do not bundle it.
     */
    private fun isHttpClientRequestFile(editor: Editor): Boolean {
        val file: VirtualFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return false
        if (!isHttpClientPluginEnabled()) return false
        return try {
            file.fileType == com.intellij.httpClient.http.request.HttpRequestFileType.INSTANCE
        } catch (_: Throwable) {
            false
        }
    }

    private fun isHttpClientPluginEnabled(): Boolean {
        val descriptor = PluginManagerCore.getPlugin(PluginId.getId("com.jetbrains.restClient"))
        return descriptor != null && descriptor.isEnabled
    }

    /**
     * Snippet for HTTP Client: no space after `{{` (see JetBrains HTTP Client variable docs).
     * Escapes backslashes and double quotes inside UID / field path.
     */
    private fun httpKeeperDynamicVariableSnippet(recordUid: String, fieldPath: String): String {
        val uidEsc = recordUid.replace("\\", "\\\\").replace("\"", "\\\"")
        val fieldEsc = fieldPath.replace("\\", "\\\\").replace("\"", "\\\"")
        return "{{\$keeper(\"$uidEsc\",\"$fieldEsc\")}}"
    }

    private fun buildInsertionSuccessMessage(
        isHttpSnippet: Boolean,
        insertText: String,
        keeperNotation: String,
    ): String = if (isHttpSnippet) {
        "HTTP Client variable inserted (record and field chosen from the list — no uid to type).\n\n$insertText"
    } else {
        "Keeper reference inserted!\n\n$keeperNotation"
    }

    private fun showError(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, message, "Error")
        }
    }

    private fun showInfo(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(project, message, "Info")
        }
    }

    private data class RecordChoice(
        val title: String,
        val uid: String,
        val isNested: Boolean,
    ) {
        fun toPickerItem() = KeeperListPickerItem(
            label = title,
            badge = if (isNested) KeeperVaultBadge.NESTED else KeeperVaultBadge.CLASSIC,
        )
    }
}