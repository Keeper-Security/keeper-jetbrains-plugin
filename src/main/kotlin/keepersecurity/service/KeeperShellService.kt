package keepersecurity.service

import com.intellij.openapi.diagnostic.Logger
import java.io.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Persistent Keeper shell service with robust prompt detection, lazy loading, and OS-specific optimizations
 */
object KeeperShellService {
    private val logger = Logger.getInstance(KeeperShellService::class.java)
    
    // Process management
    @Volatile private var process: Process? = null
    @Volatile private var writer: OutputStreamWriter? = null
    @Volatile private var reader: BufferedReader? = null
    
    // State management
    private val shellReady = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    private val commandLock = ReentrantLock()
    
    // Track if this is the first time starting (for better UX messaging)
    private val firstStart = AtomicBoolean(true)
    
    // Command execution
    private val currentCommand = AtomicReference<CommandExecution?>(null)
    
    // Output buffer for continuous reading
    private val outputBuffer = StringBuilder()
    private val bufferLock = ReentrantLock()
    
    // Reader thread
    @Volatile private var readerThread: Thread? = null
    
    // OS detection
    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    private val isMacOS = System.getProperty("os.name").lowercase().contains("mac")
    private val isLinux = System.getProperty("os.name").lowercase().contains("linux")
    
    // Store the working keeper CLI path or command
    @Volatile private var keeperCliPath: String = "keeper"
    @Volatile private var keeperIsModule: Boolean = false
    
    data class CommandExecution(
        val future: CompletableFuture<String>,
        val commandText: String,
        val startTime: Long = System.currentTimeMillis()
    )
    
    /**
     * Check if running in test mode to avoid biometric prompts
     */
    private fun isTestMode(): Boolean {
        return System.getProperty("keeper.test.mode") == "true" ||
               System.getProperty("keeper.skip.auth") == "true"
    }
    
    /**
     * Check if Keeper CLI is available on the system with comprehensive path checking
     */
    private fun isKeeperCLIAvailable(): Boolean {
        logger.info("=== COMPREHENSIVE KEEPER CLI DETECTION ===")
        
        val startTime = System.currentTimeMillis()
        val maxDetectionTime = 30_000L // 30 seconds max to prevent hanging
        
        try {
            // Log environment info first
            val username = System.getProperty("user.name") 
            val userHome = System.getProperty("user.home")
            val currentPath = System.getenv("PATH")
            
            logger.info("Username: $username")
            logger.info("User home: $userHome") 
            logger.info("Current PATH: ${currentPath?.take(300)}...")
            
            // Comprehensive list of paths where keeper might be installed
            val directPaths = when {
                isWindows -> listOf(
                    "keeper",
                    "keeper.exe",
                    "C:\\Python39\\Scripts\\keeper.exe",
                    "C:\\Python310\\Scripts\\keeper.exe", 
                    "C:\\Python311\\Scripts\\keeper.exe",
                    "C:\\Python312\\Scripts\\keeper.exe",
                    "C:\\Users\\$username\\AppData\\Local\\Programs\\Python\\Python39\\Scripts\\keeper.exe",
                    "C:\\Users\\$username\\AppData\\Local\\Programs\\Python\\Python310\\Scripts\\keeper.exe",
                    "C:\\Users\\$username\\AppData\\Local\\Programs\\Python\\Python311\\Scripts\\keeper.exe",
                    "C:\\Users\\$username\\AppData\\Roaming\\Python\\Python39\\Scripts\\keeper.exe",
                    "C:\\Users\\$username\\AppData\\Roaming\\Python\\Python310\\Scripts\\keeper.exe"
                )
                isMacOS -> listOf(
                    "keeper",
                    "/usr/local/bin/keeper",
                    "/opt/homebrew/bin/keeper",
                    "/usr/bin/keeper",
                    // Python user install paths - THIS IS WHERE YOUR KEEPER IS LIKELY LOCATED
                    "/Users/$username/Library/Python/3.9/bin/keeper",
                    "/Users/$username/Library/Python/3.10/bin/keeper", 
                    "/Users/$username/Library/Python/3.11/bin/keeper",
                    "/Users/$username/Library/Python/3.12/bin/keeper",
                    "/Users/$username/.local/bin/keeper",
                    // System Python framework paths
                    "/Library/Frameworks/Python.framework/Versions/3.9/bin/keeper",
                    "/Library/Frameworks/Python.framework/Versions/3.10/bin/keeper",
                    "/Library/Frameworks/Python.framework/Versions/3.11/bin/keeper",
                    "/Library/Frameworks/Python.framework/Versions/3.12/bin/keeper",
                    // Homebrew Python paths
                    "/opt/homebrew/opt/python@3.9/bin/keeper",
                    "/opt/homebrew/opt/python@3.10/bin/keeper", 
                    "/opt/homebrew/opt/python@3.11/bin/keeper",
                    "/opt/homebrew/opt/python@3.12/bin/keeper"
                )
                isLinux -> listOf(
                    "keeper",
                    "/usr/local/bin/keeper",
                    "/usr/bin/keeper",
                    "/home/$username/.local/bin/keeper",
                    "/opt/keeper/bin/keeper"
                )
                else -> listOf("keeper")
            }
            
            logger.info("Testing ${directPaths.size} direct paths...")
            
            // Test direct executable paths with timeout protection
            for (path in directPaths) {
                if (System.currentTimeMillis() - startTime > maxDetectionTime) {
                    logger.warn("CLI detection timeout after 30 seconds - aborting direct path tests")
                    break
                }
                
                if (testKeeperPath(path)) {
                    keeperCliPath = path
                    keeperIsModule = false
                    logger.info("SUCCESS: Found Keeper CLI at direct path: $path")
                    return true
                }
            }
            
            logger.info("Direct paths failed. Testing Python module execution...")
            
            // Test Python module execution as fallback
            val pythonCommands = when {
                isMacOS -> listOf(
                    "python3 -m keepercommander",
                    "python -m keepercommander",
                    "/usr/bin/python3 -m keepercommander",
                    "/opt/homebrew/bin/python3 -m keepercommander",
                    "/Library/Frameworks/Python.framework/Versions/3.9/bin/python3 -m keepercommander",
                    "/Library/Frameworks/Python.framework/Versions/3.11/bin/python3 -m keepercommander"
                )
                isWindows -> listOf(
                    "python -m keepercommander",
                    "python3 -m keepercommander", 
                    "py -m keepercommander",
                    "C:\\Python39\\python.exe -m keepercommander",
                    "C:\\Python310\\python.exe -m keepercommander"
                )
                else -> listOf(
                    "python3 -m keepercommander",
                    "python -m keepercommander"
                )
            }
            
            for (cmd in pythonCommands) {
                if (System.currentTimeMillis() - startTime > maxDetectionTime) {
                    logger.warn("CLI detection timeout - aborting Python module tests")
                    break
                }
                
                if (testKeeperPythonModule(cmd)) {
                    keeperCliPath = cmd
                    keeperIsModule = true
                    logger.info("SUCCESS: Found Keeper via Python module: $cmd")
                    return true
                }
            }
            
            // Final attempt: use system commands to locate keeper
            logger.info("Python modules failed. Trying system locate commands...")
            if (trySystemLocate()) {
                return true
            }
            
            logger.error("=== ALL KEEPER CLI DETECTION METHODS FAILED ===")
            logDiagnosticInfo()
            return false
            
        } catch (e: Exception) {
            logger.error("CLI detection failed with exception", e)
            return false
        }
    }
    
    /**
     * Test a direct keeper executable path
     */
    private fun testKeeperPath(path: String): Boolean {
        return try {
            logger.info("Testing direct path: '$path'")
            
            val process = ProcessBuilder(path, "--version")
                .redirectErrorStream(true)
                .start()
            
            val completed = process.waitFor(5, TimeUnit.SECONDS)
            val output = if (completed) {
                process.inputStream.bufferedReader().readText()
            } else {
                logger.warn("Path '$path' timed out after 5 seconds")
                process.destroyForcibly()
                return false
            }
            
            val exitCode = process.exitValue()
            logger.info("Path '$path' - Exit code: $exitCode, Output: ${output.take(100)}")
            
            exitCode == 0 && output.contains("Keeper Commander", ignoreCase = true)
            
        } catch (e: Exception) {
            logger.debug("Path '$path' failed: ${e.message}")
            false
        }
    }
    
    /**
     * Test Python module execution
     */
    private fun testKeeperPythonModule(command: String): Boolean {
        return try {
            logger.info("Testing Python module: '$command'")
            
            val parts = "$command --version".split(" ")
            val process = ProcessBuilder(parts).start()
            
            val completed = process.waitFor(5, TimeUnit.SECONDS)
            val output = if (completed) {
                process.inputStream.bufferedReader().readText()
            } else {
                logger.warn("Python command '$command' timed out after 5 seconds")
                process.destroyForcibly() 
                return false
            }
            
            val exitCode = process.exitValue()
            logger.info("Python module '$command' - Exit code: $exitCode, Output: ${output.take(100)}")
            
            exitCode == 0 && output.contains("Keeper Commander", ignoreCase = true)
            
        } catch (e: Exception) {
            logger.debug("Python command '$command' failed: ${e.message}")
            false
        }
    }
    
    /**
     * Try using system commands to locate keeper
     */
    private fun trySystemLocate(): Boolean {
        val locateCommand = if (isWindows) "where" else "which"
        return try {
            logger.info("Trying system locate: '$locateCommand keeper'")
            
            val process = ProcessBuilder(locateCommand, "keeper").start()
            val completed = process.waitFor(5, TimeUnit.SECONDS)
            
            if (!completed) {
                logger.warn("System locate command timed out")
                process.destroyForcibly()
                return false
            }
            
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.exitValue()
            
            if (exitCode == 0 && output.isNotBlank()) {
                val foundPath = output.trim().lines().first()
                logger.info("System locate found: $foundPath")
                
                if (testKeeperPath(foundPath)) {
                    keeperCliPath = foundPath
                    keeperIsModule = false
                    logger.info("SUCCESS: Verified keeper at system located path: $foundPath")
                    return true
                }
            }
            
            false
        } catch (e: Exception) {
            logger.debug("System locate failed: ${e.message}")
            false
        }
    }
    
    /**
     * Log comprehensive diagnostic information
     */
    private fun logDiagnosticInfo() {
        logger.error("=== DIAGNOSTIC INFORMATION ===")
        logger.error("OS: ${System.getProperty("os.name")}")
        logger.error("User: ${System.getProperty("user.name")}")
        logger.error("Home: ${System.getProperty("user.home")}")
        logger.error("Working Dir: ${System.getProperty("user.dir")}")
        logger.error("Java Version: ${System.getProperty("java.version")}")
        
        val path = System.getenv("PATH")
        logger.error("PATH: $path")
        
        // Check if the expected keeper path exists as a file
        val expectedPath = "/Users/${System.getProperty("user.name")}/Library/Python/3.9/bin/keeper"
        val keeperFile = File(expectedPath)
        logger.error("Expected keeper file exists: ${keeperFile.exists()}")
        if (keeperFile.exists()) {
            logger.error("Expected keeper file executable: ${keeperFile.canExecute()}")
            logger.error("Expected keeper file readable: ${keeperFile.canRead()}")
        }
    }
    
    /**
     * Get detailed error message for missing Keeper CLI
     */
    private fun getKeeperCLIMissingMessage(): String {
        val username = System.getProperty("user.name")
        return when {
            isWindows -> """
                Keeper Commander CLI not found on Windows
                
                Please install it using one of these methods:
                
                1. Using pip (recommended):
                   pip install keepercommander
                
                2. Using pip3:
                   pip3 install keepercommander
                
                3. Using Python directly:
                   python -m pip install keepercommander
                
                After installation:
                - Restart your IDE completely
                - Run 'keeper login' in Command Prompt
                - Authenticate with your Keeper account
                
                Make sure Python and pip are in your Windows PATH.
            """.trimIndent()
            
            isMacOS -> """
                Keeper Commander CLI not found on macOS
                
                Based on your system, the issue is likely that your IDE cannot find the Keeper CLI
                installed in your Python user directory.
                
                SOLUTION 1 - Run IDE from terminal (Quick fix):
                1. Close your IDE completely
                2. Open Terminal and run:
                   export PATH="/Users/$username/Library/Python/3.9/bin:${"$"}PATH"
                   open -a "IntelliJ IDEA"  # Replace with your IDE name
                
                SOLUTION 2 - Create a system-wide symlink:
                   sudo ln -sf /Users/$username/Library/Python/3.9/bin/keeper /usr/local/bin/keeper
                
                SOLUTION 3 - Reinstall globally:
                   sudo pip3 install keepercommander
                
                After applying any solution, restart your IDE and try again.
            """.trimIndent()
            
            else -> """
                Keeper Commander CLI not found on Linux
                
                Please install it using one of these methods:
                
                1. Using pip3 (recommended):
                   pip3 install keepercommander
                
                2. Using system package manager (if available):
                   sudo apt install keepercommander  # Ubuntu/Debian
                   sudo yum install keepercommander   # CentOS/RHEL
                
                3. Using pip with user install:
                   pip3 install --user keepercommander
                
                After installation, restart your IDE and try again.
                
                If Keeper CLI is already installed, try running IDE from terminal
            """.trimIndent()
        } + "\n\nThen run 'keeper login' to authenticate."
    }
    
    /**
     * Get OS-specific timeout for shell initialization
     */
    private fun getShellInitTimeout(): Long {
        return when {
            isWindows -> 120_000L  // 2 minutes for Windows (slow auth + sync)
            isMacOS -> 120_000L    // 2 minutes for macOS (handles throttling + auth)
            isLinux -> 90_000L     // 1.5 minutes for Linux (handles throttling)
            else -> 90_000L        // 1.5 minutes default for unknown OS
        }
    }
    
    /**
     * Get OS-specific timeout description
     */
    private fun getTimeoutDescription(): String {
        return when {
            isWindows -> "up to 2 minutes on Windows (authentication + sync)"
            isMacOS -> "up to 2 minutes on macOS"
            isLinux -> "up to 1.5 minutes on Linux" 
            else -> "up to 1.5 minutes"
        }
    }
    
    /**
     * Start the persistent keeper shell
     */
    fun startShell(): Boolean {
        // BYPASS AUTHENTICATION IN TESTS
        if (isTestMode()) {
            logger.info("TEST MODE: Bypassing shell startup - returning success immediately")
            shellReady.set(true)
            return true
        }
        
        if (shellReady.get()) {
            logger.debug("Shell already running")
            return true
        }
        
        if (!starting.compareAndSet(false, true)) {
            return waitForStartupComplete()
        }
        
        try {
            // Check if Keeper CLI is available first
            if (!isKeeperCLIAvailable()) {
                val errorMessage = getKeeperCLIMissingMessage()
                logger.error(errorMessage)
                throw RuntimeException(errorMessage)
            }
            
            // Better logging for first-time vs restart
            if (firstStart.get()) {
                logger.info("Starting Keeper shell on first use (user action triggered)")
                firstStart.set(false)
            } else {
                logger.info("Restarting Keeper shell")
            }
            
            // Create process based on detection results
            val processBuilder = if (keeperIsModule) {
                // Python module command like "python3 -m keepercommander shell"
                val parts = keeperCliPath.split(" ").toMutableList()
                parts.add("shell")
                ProcessBuilder(parts)
            } else {
                // Direct executable like "/usr/local/bin/keeper shell"
                ProcessBuilder(keeperCliPath, "shell")
            }
            
            logger.info("Starting process: ${processBuilder.command().joinToString(" ")}")

            // Disable colorized output to avoid ANSI artifacts in parsed JSON
            processBuilder.environment().apply {
                put("NO_COLOR", "1")
                put("CLICOLOR", "0")
            }

            process = processBuilder
                .redirectErrorStream(true)
                .start()

            writer = OutputStreamWriter(process!!.outputStream)
            reader = BufferedReader(InputStreamReader(process!!.inputStream))
            
            // Verify process started successfully
            Thread.sleep(1000)
            if (process?.isAlive != true) {
                logger.error("Keeper process died immediately after start")
                cleanup()
                throw RuntimeException("Keeper process failed to start")
            }
            
            // Start the output reader thread
            startReaderThread()

            // Wait for shell to be ready using OS-specific strategies
            val success = waitForShellInitialization()

            if (success) {
                shellReady.set(true)
                logger.info("Keeper shell ready! Can now execute commands.")
            } else {
                cleanup()
                logger.error("Failed to initialize Keeper shell")
            }
            
            return success
            
        } catch (e: IOException) {
            val errorMessage = when {
                e.message?.contains("CreateProcess error=2") == true -> getKeeperCLIMissingMessage()
                e.message?.contains("No such file or directory") == true -> getKeeperCLIMissingMessage()
                else -> "Failed to start Keeper shell: ${e.message}"
            }
            
            logger.error(errorMessage, e)
            cleanup()
            throw RuntimeException(errorMessage, e)
            
        } catch (e: Exception) {
            logger.error("Failed to start Keeper shell", e)
            cleanup()
            throw RuntimeException("Failed to start Keeper shell: ${e.message}", e)
        } finally {
            starting.set(false)
        }
    }
    
    private fun startReaderThread() {
        readerThread = Thread({
            logger.debug("Reader thread started")
            
            try {
                val buffer = CharArray(256)
                val lineBuffer = StringBuilder()
                
                while (process?.isAlive == true && !Thread.currentThread().isInterrupted) {
                    if (reader?.ready() == true) {
                        val charsRead = reader?.read(buffer) ?: -1
                        if (charsRead > 0) {
                            val chunk = String(buffer, 0, charsRead)
                            processOutputChunk(chunk, lineBuffer)
                        }
                    } else {
                        Thread.sleep(100)
                    }
                }
            } catch (e: InterruptedException) {
                logger.debug("Reader thread interrupted")
            } catch (e: Exception) {
                logger.warn("Reader thread error", e)
            } finally {
                logger.debug("Reader thread stopped")
            }
        }, "KeeperShell-Reader")
        
        readerThread?.isDaemon = true
        readerThread?.start()
    }
    
    private fun processOutputChunk(chunk: String, lineBuffer: StringBuilder) {
        bufferLock.withLock {
            outputBuffer.append(chunk)
            lineBuffer.append(chunk)
            
            // Process complete lines
            val lines = lineBuffer.toString().split('\n')
            if (lines.size > 1) {
                // Process all complete lines except the last (which might be partial)
                for (i in 0 until lines.size - 1) {
                    val line = lines[i].replace('\r', ' ').trim()
                    if (line.isNotEmpty()) {
                        processCompleteLine(line)
                    }
                }
                
                // Keep the last partial line in the buffer
                lineBuffer.clear()
                lineBuffer.append(lines.last())
            }
            
            // Check for prompt in the current buffer (including partial lines)
            val currentText = outputBuffer.toString()
            if (isShellReady(currentText)) {
                handleShellReady()
            }
            
            // Check for command completion
            currentCommand.get()?.let { execution ->
                if (isCommandComplete(currentText)) {
                    handleCommandComplete(execution, currentText)
                }
            }
        }
    }
    
    private fun processCompleteLine(line: String) {
        when {
            line.contains("urllib3") -> logger.debug("Warning: $line")
            line.contains("#") && line.length > 50 -> logger.debug("Banner: ASCII art")
            line.contains("Keeper") && line.contains("Commander") -> logger.info("Banner: $line")
            line.contains("version") -> logger.info("Version: $line")
            line.contains("Logging in") -> logger.info("Auth: Starting authentication...")
            line.contains("Successfully authenticated") -> {
                logger.info("Auth: $line")
                if (isWindows) logger.info("Windows: Authentication successful, preparing vault...")
            }
            line.contains("Syncing") -> {
                logger.info("Sync: $line")
                if (isWindows) logger.info("Windows: Syncing vault data...")
            }
            line.contains("Decrypted") -> {
                logger.info("Sync: $line")
                if (isWindows) logger.info("Windows: Vault sync complete!")
            }
            line.contains("My Vault>") -> logger.info("Shell ready: $line")
            line.contains("Not logged in>") -> logger.warn("Shell ready but not authenticated: $line")
            line.contains("breachwatch") -> logger.debug("Info: $line")
            line.trim().isNotEmpty() -> logger.debug("Output: $line")
            line.contains("Not logged in>") -> logger.warn("Shell ready but not authenticated")
        }
    }
    
    private fun isShellReady(text: String): Boolean {
        return text.contains("My Vault>") || 
               text.contains("Keeper>") ||
               text.contains("Not logged in>") ||
               text.contains("Successfully authenticated with Biometric Login", ignoreCase = true) ||
               text.contains("Successfully authenticated") ||
               (text.contains("Decrypted") && text.contains("record(s)")) ||
               (text.contains("Successfully authenticated") && 
                text.contains("Syncing") && 
                text.contains("Decrypted"))
    }
    
    private fun isCommandComplete(text: String): Boolean {
        return text.contains("My Vault>") || 
               text.contains("Keeper>") ||
               text.contains("Not logged in>")
    }
    
    private fun handleShellReady() {
        if (!shellReady.get()) {
            logger.info("Shell ready detected!")
            
            try {
                writer?.apply {
                    write("\n")
                    flush()
                }
                logger.debug("Sent newline to trigger prompt")
            } catch (e: Exception) {
                logger.debug("Error sending newline: ${e.message}")
            }
        }
    }
    
    private fun handleCommandComplete(execution: CommandExecution, fullOutput: String) {
        val result = extractCommandOutput(fullOutput, execution.commandText)
        
        logger.info("Command '${execution.commandText}' completed in ${System.currentTimeMillis() - execution.startTime}ms")
        logger.debug("Command result (${result.length} chars): ${result.take(200)}${if (result.length > 200) "..." else ""}")
        
        execution.future.complete(result)
        outputBuffer.setLength(0)
    }
    
    private fun extractCommandOutput(fullOutput: String, commandText: String): String {
        val lines = fullOutput.lines()
        
        var commandLineIndex = -1
        for (i in lines.indices) {
            if (lines[i].contains(commandText)) {
                commandLineIndex = i
                break
            }
        }
        
        if (commandLineIndex == -1) {
            return lines.filter { line ->
                !line.contains("My Vault>") && !line.contains("Keeper>") && !line.contains("Not logged in>")
            }.joinToString("\n").trim()
        }
        
        return lines.drop(commandLineIndex + 1)
            .filter { line ->
                !line.contains("My Vault>") && !line.contains("Keeper>") && !line.contains("Not logged in>")
            }
            .joinToString("\n").trim()
    }
    
    private fun waitForShellInitialization(): Boolean {
        val startTime = System.currentTimeMillis()
        val timeoutMs = getShellInitTimeout()
        
        logger.info("Waiting for Keeper shell to be ready (${getTimeoutDescription()})...")
        logger.info("Process created successfully, PID: ${process?.pid()}")
        
        var lastProgressTime = startTime
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            bufferLock.withLock {
                val currentOutput = outputBuffer.toString()
                
                if (isShellReady(currentOutput)) {
                    logger.info("Shell appears ready based on output content")
                    
                    try {
                        writer?.apply {
                            write("\n")
                            flush()
                        }
                        Thread.sleep(1000)
                        
                        val updatedOutput = outputBuffer.toString()
                        if (updatedOutput.contains("My Vault>") || updatedOutput.contains("Keeper>") || updatedOutput.contains("Not logged in>")) {
                            logger.info("Confirmed: Got prompt after sending newline")
                            outputBuffer.setLength(0)
                            return true
                        } else if (updatedOutput.contains("Successfully authenticated")) {
                            logger.info("Authentication successful, shell is ready even if still syncing")
                            outputBuffer.setLength(0)
                            return true
                        }
                    } catch (e: Exception) {
                        logger.warn("Error sending test newline", e)
                    }
                }
                
                // Show progress every 5 seconds
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed - lastProgressTime > 5_000L) {
                    val lastLines = currentOutput.lines().takeLast(2).joinToString(" | ")
                    logger.info("Still waiting (${elapsed/1000}s)... Process alive: ${process?.isAlive}, Last output: $lastLines")
                    lastProgressTime = elapsed
                }
            }
            
            // Check if process died
            if (process?.isAlive != true) {
                logger.error("Keeper shell process died during startup")
                return false
            }
            
            Thread.sleep(200)
        }
        
        // Timeout - show diagnostic info
        bufferLock.withLock {
            val output = outputBuffer.toString()
            logger.error("Timeout waiting for shell ready after ${timeoutMs}ms")
            logger.error("Process alive: ${process?.isAlive}")
            logger.error("Output buffer length: ${output.length}")
            logger.error("Last 500 chars of output: '${output.takeLast(500)}'")
        }
        
        return false
    }
    
    /**
     * Execute a command in the persistent shell
     */
    fun executeCommand(command: String, timeoutSeconds: Long = 30): String {
        // RETURN MOCK DATA IN TESTS
        if (isTestMode()) {
            logger.info("TEST MODE: Returning mock response for: $command")
            return when {
                command.contains("add-record") -> """{"record_uid": "test-record-123", "result": "success"}"""
                command.contains("list") -> """[{"record_uid": "test-1", "title": "Test Record"}]"""
                command.contains("generate") -> """[{"password": "TestPassword123!"}]"""
                else -> """{"result": "success", "message": "Test mode response"}"""
            }
        }
        
        commandLock.withLock {
            if (!ensureShellRunning()) {
                throw IllegalStateException("Could not start Keeper shell. Please ensure Keeper CLI is installed and you're authenticated.")
            }
            
            val execution = CommandExecution(CompletableFuture(), command)
            currentCommand.set(execution)
            
            try {
                logger.info("Executing command: $command")
                
                bufferLock.withLock {
                    outputBuffer.setLength(0)
                }
                
                writer?.apply {
                    write(command)
                    write("\n")
                    flush()
                }
                
                val result = execution.future.get(timeoutSeconds, TimeUnit.SECONDS)
                return result.trim()
                
            } catch (e: Exception) {
                logger.error("Command execution failed: $command", e)
                throw RuntimeException("Command failed: ${e.message}", e)
            } finally {
                currentCommand.set(null)
            }
        }
    }
    
    /**
     * Check if shell is ready and responsive
     */
    fun isReady(): Boolean {
        return shellReady.get() && process?.isAlive == true
    }
    
    /**
     * Stop the shell gracefully
     */
    fun stopShell() {
        logger.info("Stopping persistent Keeper shell...")
        shellReady.set(false)
        
        try {
            writer?.apply {
                write("q\n")
                flush()
            }
            Thread.sleep(1000)
        } catch (e: Exception) {
            logger.debug("Error sending quit command", e)
        }
        
        cleanup()
    }
    
    // Private helper methods
    
    private fun ensureShellRunning(): Boolean {
        return if (isReady()) {
            logger.debug("Shell already ready")
            true
        } else {
            logger.info("Shell not ready, starting on-demand due to user action...")
            try {
                startShell()
            } catch (e: Exception) {
                logger.error("Failed to start shell", e)
                false
            }
        }
    }
    
    private fun waitForStartupComplete(): Boolean {
        val maxAttempts = if (isWindows) 1200 else 900
        var attempts = 0
        while (starting.get() && attempts < maxAttempts) {
            Thread.sleep(100)
            attempts++
        }
        return shellReady.get()
    }
    
    private fun cleanup() {
        try {
            readerThread?.interrupt()
            writer?.close()
            reader?.close()
            process?.destroyForcibly()
        } catch (e: Exception) {
            logger.debug("Error during cleanup", e)
        }
        
        process = null
        writer = null
        reader = null
        readerThread = null
        shellReady.set(false)
        starting.set(false)
        
        bufferLock.withLock {
            outputBuffer.setLength(0)
        }
        
        logger.info("Cleanup completed")
    }
    
    /**
     * Get the last startup output for authentication detection
     */
    fun getLastStartupOutput(): String {
        return bufferLock.withLock {
            outputBuffer.toString()
        }
    }

    private data class ExternalResult(val exitCode: Int, val output: String)

    private fun buildExternalKeeperCommand(vararg args: String): List<String> {
        return if (keeperIsModule) {
            // Example: "python3 -m keepercommander biometric --status"
            val parts = keeperCliPath.split(" ").toMutableList()
            parts.addAll(args)
            parts
        } else {
            // Example: "/usr/local/bin/keeper biometric --status"
            listOf(keeperCliPath) + args
        }
    }

    private fun runExternalKeeperCommand(args: List<String>, timeoutSeconds: Long = 15): ExternalResult {
        return try {
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            val completed = p.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                p.destroyForcibly()
                ExternalResult(-1, "timeout")
            } else {
                val out = p.inputStream.bufferedReader().readText()
                ExternalResult(p.exitValue(), out)
            }
        } catch (e: Exception) {
            logger.debug("External command failed: ${e.message}")
            ExternalResult(-1, "error: ${e.message}")
        }
    }
}