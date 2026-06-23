package keepersecurity.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.thisLogger
import keepersecurity.ui.KeeperListPickerDialog
import keepersecurity.ui.KeeperListPickerItem
import keepersecurity.ui.KeeperVaultBadge
import kotlinx.serialization.json.Json
import keepersecurity.model.KeeperFolder
import keepersecurity.util.KeeperJsonUtils
import keepersecurity.util.KeeperCommandUtils
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Unified folder picker. Shows every folder returned by Commander's
 * `ls --format=json -f -R` — Classic folders and Nested Shared Folders
 * — in a single dialog. Each row shows the folder name with a Classic or
 * Nested badge so users can distinguish same-named folders across vault models.
 *
 * Keeper's two vault models (Classic and Nested Shared Folders) each
 * support folders and records with their own CLI command families (`record-*`
 * vs `nsf-*`). The picked folder is persisted into the **target-specific**
 * preference pair so downstream record actions (`Add`, `Update`, `Generate`)
 * route to the right CLI command:
 *
 *  - Nested Shared Folder → [KeeperRecordTargetPrompt.PREF_DRIVE_FOLDER_UUID]
 *    / `_NAME`. The Classic pair is cleared on the way through so a stale
 *    Classic UUID from a previous pick can't be reused by the Classic
 *    target.
 *  - Classic folder → [KeeperRecordTargetPrompt.PREF_CLASSIC_FOLDER_UUID]
 *    / `_NAME`. The Nested Shared Folder pair is cleared symmetrically.
 *
 * Commander's `ls --format=json -f -R` includes both vault types in one
 * payload, so a single picker covers Classic and Nested Shared Folder without a
 * second menu item.
 */
@OptIn(ExperimentalSerializationApi::class)
class KeeperFolderSelectAction : AnAction("Get Keeper Folder") {
    private val logger = thisLogger()

    /**
     * Internal tuple used while building the picker. Carries the raw folder
     * name, the folder UID, and whether the folder lives in a Nested Shared
     * Subfolder.
     */
    private data class FolderChoice(
        val name: String,
        val uid: String,
        val isDrive: Boolean,
    ) {
        fun toPickerItem() = KeeperListPickerItem(
            label = name,
            badge = if (isDrive) KeeperVaultBadge.NESTED else KeeperVaultBadge.CLASSIC,
            id = uid,
        )
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return

        object : Task.Backgroundable(project, "Fetching Keeper Folders...", false) {
            override fun run(indicator: ProgressIndicator) {

                val folderChoices: List<FolderChoice> = try {
                    val startTime = System.currentTimeMillis()

                    indicator.text = "Syncing with Keeper vault..."
                    KeeperCommandUtils.syncDownBestEffort(logger)

                    indicator.text = "Fetching Keeper folders..."
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

                if (folderChoices.isEmpty()) {
                    showError(project, "No folders found in Keeper vault.")
                    return
                }

                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    val pickerItems = folderChoices.map { it.toPickerItem() }
                    val selected = KeeperListPickerDialog.pickItem(
                        project = project,
                        title = "Keeper Folder Selection",
                        message = "Select a Keeper folder (Classic or Nested Shared Folder):",
                        options = pickerItems,
                        initialSelection = pickerItems.firstOrNull()
                    ) ?: return@invokeLater

                    val selectedFolder = selected.id?.let { uid ->
                        folderChoices.find { it.uid == uid }
                    }
                    if (selectedFolder != null) {
                        persistFolderSelection(project, selectedFolder)

                        val locationLabel = if (selectedFolder.isDrive) "Nested Shared Folder" else "Classic Vault"
                        Messages.showInfoMessage(
                            project,
                            "$locationLabel folder '${selectedFolder.name}' " +
                                "(Uuid '${selectedFolder.uid}') has been saved for this project.",
                            "Keeper Folder Saved"
                        )
                    }
                }
            }
        }.queue()
    }

    /**
     * Persist the selection into the slot the chosen folder's vault uses,
     * and clear the *other* slot so a stale UUID from an earlier pick (or
     * a different account) doesn't silently leak into the wrong CLI
     * command later. Per-project storage to match the saved-folder reads
     * in [KeeperRecordTargetPrompt].
     */
    private fun persistFolderSelection(project: Project, folder: FolderChoice) {
        val props = PropertiesComponent.getInstance(project)

        if (folder.isDrive) {
            props.setValue(KeeperRecordTargetPrompt.PREF_DRIVE_FOLDER_NAME, folder.name)
            props.setValue(KeeperRecordTargetPrompt.PREF_DRIVE_FOLDER_UUID, folder.uid)
            props.unsetValue(KeeperRecordTargetPrompt.PREF_CLASSIC_FOLDER_NAME)
            props.unsetValue(KeeperRecordTargetPrompt.PREF_CLASSIC_FOLDER_UUID)
            logger.info("Saved Nested Shared Folder '${folder.name}' (${folder.uid}); cleared classic slot")
        } else {
            props.setValue(KeeperRecordTargetPrompt.PREF_CLASSIC_FOLDER_NAME, folder.name)
            props.setValue(KeeperRecordTargetPrompt.PREF_CLASSIC_FOLDER_UUID, folder.uid)
            props.unsetValue(KeeperRecordTargetPrompt.PREF_DRIVE_FOLDER_NAME)
            props.unsetValue(KeeperRecordTargetPrompt.PREF_DRIVE_FOLDER_UUID)
            logger.info("Saved classic folder '${folder.name}' (${folder.uid}); cleared Nested Shared Folder slot")
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    /**
     * Parse the unified `ls --format=json -f -R` output and build a picker
     * entry per folder. Each row carries a Classic / Nested badge derived
     * from the row's `source` discriminator. Folders without a populated
     * `source` field default to Classic — older Commander releases predate
     * the v3 discriminator, and the legacy `record-add --folder=<uuid>`
     * call is the right fallback in that case.
     */
    private fun parseKeeperFolders(output: String): List<FolderChoice> {
        try {
            logger.debug("Raw output: ${output.take(200)}...")

            val jsonString = KeeperJsonUtils.extractJsonArray(output, logger)
            val folders = json.decodeFromString<List<KeeperFolder>>(jsonString)

            val choices = folders.mapNotNull { folder ->
                if (folder.name.isBlank() || folder.folderUid.isBlank()) {
                    logger.debug("Skipping folder with missing data: $folder")
                    return@mapNotNull null
                }
                val isDrive = folder.isKeeperDrive
                logger.debug("Parsed folder: '${folder.name}' -> '${folder.folderUid}' (drive=$isDrive)")
                FolderChoice(
                    name = folder.name,
                    uid = folder.folderUid,
                    isDrive = isDrive,
                )
            }

            logger.info("Successfully parsed ${choices.size} folders (Nested Shared Folder: ${choices.count { it.isDrive }}, Classic: ${choices.count { !it.isDrive }})")
            return choices

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
