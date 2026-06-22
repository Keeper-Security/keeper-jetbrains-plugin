package keepersecurity.run

import com.intellij.execution.configurations.RunConfigurationOptions

/**
 * Persisted fields for [KeeperSecureRunConfiguration] (Kotlin delegate API).
 */
class KeeperSecureRunConfigurationOptions : RunConfigurationOptions() {
    var envFilePath by string("")
    var workingDirectoryPath by string("")
    var command by string("")
}
