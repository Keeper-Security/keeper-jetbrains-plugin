package keepersecurity.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.configurations.RuntimeConfigurationWarning;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * Run configuration: .env path, working directory, and shell command — secrets resolved via Keeper at run time.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class KeeperSecureRunConfiguration extends RunConfigurationBase {

    protected KeeperSecureRunConfiguration(@NotNull Project project,
                                           @NotNull ConfigurationFactory factory,
                                           @NotNull String name) {
        super(project, factory, name);
    }

    @NotNull
    @Override
    protected KeeperSecureRunConfigurationOptions getOptions() {
        return (KeeperSecureRunConfigurationOptions) super.getOptions();
    }

    @NotNull
    @Override
    public SettingsEditor<KeeperSecureRunConfiguration> getConfigurationEditor() {
        return new KeeperSecureRunConfigurationEditor(getProject());
    }

    @Override
    public void onNewConfigurationCreated() {
        super.onNewConfigurationCreated();
        KeeperSecureRunDefaults.INSTANCE.applyDefaults(getProject(), getOptions());
    }

    @Override
    public void checkConfiguration() throws RuntimeConfigurationException {
        super.checkConfiguration();
        String envPath = StringUtil.notNullize(getOptions().getEnvFilePath()).trim();
        if (envPath.isEmpty()) {
            throw new RuntimeConfigurationError("Environment file path is required");
        }
        String command = StringUtil.notNullize(getOptions().getCommand()).trim();
        if (command.isEmpty()) {
            throw new RuntimeConfigurationError("Command is required");
        }
        String basePath = getProject().getBasePath();
        if (basePath == null) {
            return;
        }
        File env = KeeperSecurePathUtil.resolveToFile(envPath, basePath);
        if (!env.isFile()) {
            throw new RuntimeConfigurationWarning(".env file not found: " + env.getAbsolutePath());
        }
        String wd = StringUtil.notNullize(getOptions().getWorkingDirectoryPath()).trim();
        File work = wd.isEmpty() ? new File(basePath) : KeeperSecurePathUtil.resolveToFile(wd, basePath);
        if (!work.isDirectory()) {
            throw new RuntimeConfigurationWarning("Working directory not found: " + work.getAbsolutePath());
        }
    }

    @Nullable
    @Override
    public RunProfileState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment environment) throws ExecutionException {
        return new KeeperSecureRunProfileState(environment, this);
    }
}
