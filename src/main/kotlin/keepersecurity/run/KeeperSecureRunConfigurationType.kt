package keepersecurity.run

import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.openapi.project.Project
import com.intellij.icons.AllIcons

class KeeperSecureRunConfigurationType : ConfigurationTypeBase(
    "KeeperSecureRunConfiguration",
    "Run Keeper Securely",
    "Run a command with secrets injected from a .env file that references Keeper (keeper://…).",
    AllIcons.Actions.Execute,
) {

    override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(factory)

    private val factory = object : ConfigurationFactory(this) {
        override fun getId(): String = "KeeperSecureRunConfigurationFactory"

        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            KeeperSecureRunConfiguration(project, this, "Run Keeper Securely")

        override fun getOptionsClass(): Class<out RunConfigurationOptions> =
            KeeperSecureRunConfigurationOptions::class.java

        override fun getName(): String = "Run Keeper Securely"

        override fun isConfigurationSingletonByDefault(): Boolean = false

        override fun configureDefaultSettings(settings: RunnerAndConfigurationSettings) {
            super.configureDefaultSettings(settings)
            val cfg = settings.configuration as? KeeperSecureRunConfiguration ?: return
            KeeperSecureRunDefaults.applyDefaults(cfg.project, cfg.getOptions())
        }
    }
}
