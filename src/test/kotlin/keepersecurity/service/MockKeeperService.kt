package keepersecurity.service

/**
 * Mock Keeper service that completely bypasses biometric authentication for
 * tests and provides deterministic responses for both Classic vault and
 * Nested Shared Folders commands.
 *
 * The fixtures returned by the mock mirror the unified shapes the real CLI
 * produces:
 *
 *  - `ls --format=json -f -R` returns a mixed list of Classic and
 *    Nested Shared Folders, each tagged with a `source` discriminator
 *    (`classic_folder` / `nested_share_folder`, plus older legacy wire values).
 *  - `list --format json` returns a similarly mixed list of records, each
 *    tagged with a `record_category` discriminator (`Classic` / `Nested`,
 *    plus older legacy wire values).
 *  - `get <uid> --format json` accepts both Classic and Nested Shared Folder
 *    UIDs (the real CLI does the same), so no separate `nsf-get` mock is
 *    required for the read path.
 *  - Only the mutating Nested Shared Folder commands (`nsf-record-add` /
 *    `nsf-record-update`) are routed separately because Commander keeps
 *    them as distinct command names.
 */
object MockKeeperService {
    private var isReady = true // Always ready in tests
    private val mockCommands = mutableMapOf<String, String>()
    private var shouldFailNextCommand = false

    /** Sample UID used across fixtures so tests can match on a stable value. */
    const val MOCK_CLASSIC_RECORD_UID = "test123456789012345678"
    const val MOCK_DRIVE_RECORD_UID = "drv7890123456789012345"
    const val MOCK_CLASSIC_FOLDER_UID = "folder123456789012345"
    const val MOCK_DRIVE_FOLDER_UID = "drvfolder12345678901a"

    init {
        // Setup default successful responses to avoid any authentication
        setupDefaultSuccessfulResponses()
    }

    private fun setupDefaultSuccessfulResponses() {
        // Mixed Classic + Nested Shared record listing matches the
        // production payload shape (each entry is tagged via
        // `record_category` for namespace filtering).
        addMockCommand(
            "list --format json",
            """
            [
                {"record_uid": "$MOCK_CLASSIC_RECORD_UID", "title": "Classic Login", "record_category": "Classic"},
                {"record_uid": "$MOCK_DRIVE_RECORD_UID", "title": "Drive Login", "record_category": "KeeperDrive"}
            ]
            """.trimIndent()
        )

        // Mixed Classic + Nested Shared Folder listing — same `source`
        // discriminator
        addMockCommand(
            "ls --format=json -f -R",
            """
            [
                {"uid": "$MOCK_CLASSIC_FOLDER_UID", "name": "Classic Folder", "source": "Legacy"},
                {"uid": "$MOCK_DRIVE_FOLDER_UID", "name": "Drive Folder", "source": "KeeperDrive"}
            ]
            """.trimIndent()
        )

        addMockCommand("generate -f json",
            """[{"password": "MockGeneratedPassword123"}]""")

        addMockCommand("", "My Vault>") // Empty command response
        addMockCommand("this-device", "Device Name: Test Device\nStatus: SUCCESSFUL")

        // Pattern-based responses for dynamic commands. Both classic and
        // Nested Shared Folders namespaces are covered. `get <UID> --format json`
        // works on Nested Shared Folder UIDs in the real CLI, so the same fixture
        // handles both.
        addMockCommandPattern(Regex("get .* --format json.*"),
            """{"record_uid": "$MOCK_CLASSIC_RECORD_UID", "password": "mock-password", "login": "mock-user"}""")

        addMockCommandPattern(Regex("nsf-record-add.*"), MOCK_DRIVE_RECORD_UID)
        addMockCommandPattern(Regex("nsf-record-update.*"), "")
        addMockCommandPattern(Regex("record-add.*"), MOCK_CLASSIC_RECORD_UID)
        addMockCommandPattern(Regex("record-update.*"), "")
    }

    fun setReady(ready: Boolean) {
        isReady = ready
    }

    fun addMockCommand(command: String, response: String) {
        mockCommands[command] = response
    }

    fun addMockCommandPattern(pattern: Regex, response: String) {
        mockCommands[pattern.pattern] = response
    }

    fun clearMockCommands() {
        mockCommands.clear()
    }

    fun setNextCommandToFail(fail: Boolean) {
        shouldFailNextCommand = fail
    }

    fun executeCommand(command: String): String {
        if (shouldFailNextCommand) {
            shouldFailNextCommand = false
            throw RuntimeException("Mock command failure")
        }
        
        if (!isReady) {
            throw RuntimeException("Mock Keeper shell not ready")
        }
        
        // Try exact match first
        mockCommands[command]?.let { return it }
        
        // Try pattern matching. `nsf-record-add` is registered before
        // `record-add`, so the more specific nsf-* patterns win when the
        // command string contains both substrings.
        mockCommands.entries
            .sortedByDescending { it.key.length }
            .forEach { (pattern, response) ->
                if (command.contains(pattern) || command.matches(Regex(pattern))) {
                    return response
                }
            }

        // Always return successful responses. The unified `list` / `ls`
        // commands already return both Classic and Nested Shared Folder rows
        // (records carry `record_category`, folders carry `source`), so
        // there are no Drive-specific list / get fallthrough branches.
        // Only the mutating `nsf-record-add` / `nsf-record-update` paths
        // are still routed by prefix because Commander still exposes them
        // as distinct commands.
        return when {
            command.startsWith("generate") -> """[{"password": "HardcodedTestPassword123"}]"""
            command.startsWith("nsf-record-add") -> MOCK_DRIVE_RECORD_UID
            command.startsWith("nsf-record-update") -> ""
            command.startsWith("list") -> """[{"record_uid": "$MOCK_CLASSIC_RECORD_UID", "title": "Hardcoded Test Record", "record_category": "Classic"}]"""
            command.startsWith("get") -> """{"password": "hardcoded-secret", "login": "test-user", "title": "Test Record"}"""
            command.startsWith("ls") -> """[{"uid": "$MOCK_CLASSIC_FOLDER_UID", "name": "Test Folder", "source": "Legacy"}]"""
            command.startsWith("record-add") -> MOCK_CLASSIC_RECORD_UID
            command.startsWith("record-update") -> ""
            else -> "hardcoded-success"
        }
    }
    
    fun isReady(): Boolean = true // Always ready in tests - HARDCODED!
    
    fun reset() {
        isReady = true // Keep always ready
        mockCommands.clear()
        shouldFailNextCommand = false
        // Re-setup defaults after reset
        setupDefaultSuccessfulResponses()
    }
}
