package keepersecurity.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.diagnostic.thisLogger
import keepersecurity.service.KeeperShellService
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import keepersecurity.model.KeeperRecord
import keepersecurity.util.KeeperJsonUtils
import kotlinx.serialization.ExperimentalSerializationApi
import keepersecurity.util.KeeperCommandUtils

@OptIn(ExperimentalSerializationApi::class)
class KeeperGetSecretAction : AnAction("Get Keeper Secret") {
    private val logger = thisLogger()

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor: Editor = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR) ?: return

        object : Task.Backgroundable(project, "Fetching Keeper Secrets...", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Using persistent Keeper shell..."

                // Step 1: Get list of records using persistent shell with retry logic
                val startTime = System.currentTimeMillis()
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

                val titles = mutableListOf<String>()
                val uidByTitle = mutableMapOf<String, String>()
                records.forEach { rec ->
                    val title = rec.title.ifBlank { "Untitled" }
                    val uid = rec.recordUid
                    if (uid.isNotBlank()) {
                        titles.add(title)
                        uidByTitle[title] = uid
                    }
                }

                if (titles.isEmpty()) {
                    showInfo(project, "No Keeper records found.")
                    return
                }

                // Step 2: Ask user to select record (UI thread)
                ApplicationManager.getApplication().invokeLater {
                    val selectedTitle = Messages.showEditableChooseDialog(
                        "Select Keeper Record:",
                        "Keeper Records",
                        null,
                        titles.toTypedArray(),
                        titles.first(),
                        null
                    ) ?: return@invokeLater

                    val selectedUid = uidByTitle[selectedTitle] ?: return@invokeLater

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
                                    fieldOptions.add("${field.type} (standard)" to field.type)
                                }
                            }
                        
                            // Process custom fields
                            recordJson.custom?.forEach { customField ->
                                if (customField.label.isNotBlank() && !customField.value.isNullOrEmpty()) {
                                    val key = customField.label.replace("\\s".toRegex(), "_")
                                    fieldOptions.add("${customField.label} (custom)" to "custom.${key}")
                                }
                            }

                            if (fieldOptions.isEmpty()) {
                                showInfo(project, "No fields with values found in selected record.")
                                return
                            }

                            logger.info("Found ${fieldOptions.size} fields in record '$selectedTitle'")

                            // Step 4: Ask user to pick field (UI thread)
                            ApplicationManager.getApplication().invokeLater {
                                val selectedFieldDisplay = Messages.showEditableChooseDialog(
                                    "Select field from record '$selectedTitle':",
                                    "Keeper Record Fields",
                                    null,
                                    fieldOptions.map { it.first }.toTypedArray(),
                                    fieldOptions.first().first,
                                    null
                                ) ?: return@invokeLater

                                val selectedFieldKey = fieldOptions.find { it.first == selectedFieldDisplay }?.second ?: return@invokeLater
                                val keeperNotation = "keeper://$selectedUid/field/$selectedFieldKey"

                                logger.info("Generated keeper notation: $keeperNotation")

                                // Step 5: Insert into editor (UI write action)
                                WriteCommandAction.runWriteCommandAction(project) {
                                    val doc = editor.document
                                    val caretOffset = editor.caretModel.offset
                                    doc.insertString(caretOffset, keeperNotation)
                                }

                                // Show success message
                                Messages.showInfoMessage(
                                    project,
                                    "Keeper reference inserted!\n\n$keeperNotation\n\nExecuted via persistent shell!",
                                    "Keeper Reference Added"
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
}