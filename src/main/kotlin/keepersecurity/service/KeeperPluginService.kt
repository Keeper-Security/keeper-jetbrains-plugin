package keepersecurity.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Application-level service that manages the Keeper shell lifecycle
 */
@Service
class KeeperPluginService {
    private val logger = thisLogger()
    
    init {
        logger.info("KeeperPluginService initialized (shell will start on first use)")
        // Removed automatic shell startup - now starts only when needed
    }
    
    fun ensureShellReady(): Boolean {
        return if (KeeperShellService.isReady()) {
            logger.debug("Shell already ready")
            true
        } else {
            logger.info("Shell not ready, starting on-demand...")
            KeeperShellService.startShell()
        }
    }
}

/**
 * Startup activity - no longer auto-starts shell
 */
class KeeperStartupActivity : ProjectActivity {
    private val logger = thisLogger()
    
    override suspend fun execute(project: Project) {
        logger.info("Keeper plugin ready for project: ${project.name} (shell will start when needed)")
        // Just ensure service is registered, don't start shell
        com.intellij.openapi.components.service<KeeperPluginService>()
    }
}