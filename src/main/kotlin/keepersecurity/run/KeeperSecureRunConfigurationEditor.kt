package keepersecurity.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

class KeeperSecureRunConfigurationEditor(@Suppress("UNUSED_PARAMETER") project: Project) :
    SettingsEditor<KeeperSecureRunConfiguration>() {

    private val envFileField = TextFieldWithBrowseButton()
    private val workingDirField = TextFieldWithBrowseButton()
    private val commandField = JTextField()

    init {
        envFileField.addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFileDescriptor().apply {
                    title = "Select .env File"
                },
            ),
        )
        workingDirField.addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
                    title = "Select Working Directory"
                },
            ),
        )
    }

    override fun createEditor(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Environment file (.env):", envFileField)
        .addLabeledComponent("Working directory (empty = project root):", workingDirField)
        .addLabeledComponent("Command:", commandField)
        .addComponentFillVertically(JPanel(), 0)
        .panel

    override fun applyEditorTo(s: KeeperSecureRunConfiguration) {
        val o = s.options
        o.envFilePath = envFileField.text.trim()
        o.workingDirectoryPath = workingDirField.text.trim()
        o.command = commandField.text.trim()
    }

    override fun resetEditorFrom(s: KeeperSecureRunConfiguration) {
        val o = s.options
        envFileField.text = o.envFilePath.orEmpty()
        workingDirField.text = o.workingDirectoryPath.orEmpty()
        commandField.text = o.command.orEmpty()
    }
}
