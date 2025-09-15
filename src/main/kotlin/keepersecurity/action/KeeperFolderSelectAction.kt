package keepersecurity.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.thisLogger
import keepersecurity.service.KeeperShellService
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import keepersecurity.model.KeeperFolder
import keepersecurity.util.KeeperJsonUtils
import keepersecurity.util.KeeperCommandUtils
import kotlinx.serialization.ExperimentalSerializationApi

@OptIn(ExperimentalSerializationApi::class)
class KeeperFolderSelectAction : AnAction("Get Keeper Folder") {
    private val logger = thisLogger()

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return

        object : Task.Backgroundable(project, "Fetching Keeper Folders...", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Using persistent Keeper shell..."
                
                val folderMap: List<Pair<String, String>> = try {
                    // This should be FAST after the first call!
                    val startTime = System.currentTimeMillis()
                    
                    // Add retry logic for the first run
                    val output = KeeperCommandUtils.executeCommandWithRetry(
                        "ls --format=json -f -R",
                        KeeperCommandUtils.Presets.jsonArray(maxRetries = 3, timeoutSeconds = 90),
                        logger
                    )
                    
                    val duration = System.currentTimeMillis() - startTime
                    logger.info("Command executed in ${duration}ms")
                    
                    parseKeeperFolders(output)
                    
                } catch (ex: Exception) {
                    logger.error("Failed to get folders from persistent shell", ex)
                    showError(project, "Failed to retrieve Keeper folders: ${ex.message}")
                    return
                }

                if (folderMap.isEmpty()) {
                    showError(project, "No folders found in Keeper vault.")
                    return
                }

                // UI code remains the same
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    val folderNames = folderMap.map { it.first }.toTypedArray()
                    val selected = Messages.showEditableChooseDialog(
                        "Select a Keeper Folder:",
                        "Keeper Folder Selection",
                        null,
                        folderNames,
                        folderNames.firstOrNull(),
                        null
                    ) ?: return@invokeLater

                    val selectedFolder = folderMap.find { it.first == selected }
                    if (selectedFolder != null) {
                        val props = PropertiesComponent.getInstance(project)
                        props.setValue("keeper.folder.name", selectedFolder.first)
                        props.setValue("keeper.folder.uuid", selectedFolder.second)

                        Messages.showInfoMessage(
                            project,
                            "Folder '${selectedFolder.first}' with UUID '${selectedFolder.second}' has been saved.\nExecuted in persistent shell!",
                            "Folder Saved"
                        )
                    }
                }
            }
        }.queue()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    private fun parseKeeperFolders(output: String): List<Pair<String, String>> {
        try {
            logger.debug("Raw output: ${output.take(200)}...")
            
            // Use the utility to extract JSON array
            val jsonString = KeeperJsonUtils.extractJsonArray(output, logger)
            val folders = json.decodeFromString<List<KeeperFolder>>(jsonString)
            
            val folderPairs = folders.mapNotNull { folder ->
                if (folder.name.isNotBlank() && folder.folderUid.isNotBlank()) {
                    logger.debug("Parsed folder: '${folder.name}' -> '${folder.folderUid}'")
                    Pair(folder.name, folder.folderUid)
                } else {
                    logger.debug("Skipping folder with missing data: $folder")
                    null
                }
            }
            
            logger.info("Successfully parsed ${folderPairs.size} folders")
            return folderPairs
            
        } catch (ex: Exception) {
            logger.error("Failed to parse folders from output", ex)
            logger.error("Raw output was: $output")
            throw RuntimeException("Failed to parse folder data: ${ex.message}")
        }
    }

    private fun showError(project: Project, message: String) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, message, "Error")
        }
    }
}