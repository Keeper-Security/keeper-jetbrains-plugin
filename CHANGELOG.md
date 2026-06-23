# Keeper Security JetBrains Plugin Changelog

## [Unreleased]

### Added
- **Nested Shared Folder support** across *Get Keeper Folder*, *Get Keeper Secret*, *Add Keeper Record*, *Update Keeper Record*, and *Generate Keeper Secret*. The plugin routes to Classic (`record-*`) or Nested Shared (`nsf-*`) commands based on vault metadata.
- **Searchable folder and record picker** with **Classic** / **Nested** badges on each row.

### Changed
- **Get Keeper Folder** and **Get Keeper Secret** now include Nested Shared folders and records alongside Classic ones.
- **Update Keeper Record** validates the record UID and chooses `record-update` vs `nsf-record-update` automatically.
- **Run Keeper Securely** validates `keeper://` record UIDs and hardens environment injection from resolved secrets.

### Fixed
- **Commander compatibility** for current folder and record discriminators (`classic_folder` / `nested_share_folder`, `Classic` / `Nested`), with support for older wire values.

## [1.2.0] - 2026-06-22

### Added
- **Run Keeper Securely** as a saved run configuration (**Run → Edit Configurations**): `.env` path, working directory, command, output in the Run tool window; defaults for Python SDK / venv and common entry scripts when creating a new configuration
- **JetBrains HTTP Client** integration for `.http` files: `{{ $keeper("recordUid", "field") }}` dynamic variable (where HTTP Client is bundled); **Get Keeper Secret** inserts the snippet in `.http` / `.rest` files
- Shared **`KeeperSecureScriptRunner`** pipeline for Tools → Run Keeper Securely and the run configuration

### Changed
- README: documents HTTP Client, run configurations, prerequisites, and links to GitHub issues [#9](https://github.com/Keeper-Security/keeper-jetbrains-plugin/issues/9) / [#11](https://github.com/Keeper-Security/keeper-jetbrains-plugin/issues/11)

## [1.0.0] - 2025-01-20

### Added
- Initial release of Keeper Security JetBrains Plugin
- Check Keeper Authorization action to verify CLI installation and authentication
- Get Keeper Secret action to insert vault secrets as references
- Add Keeper Record action to create new vault records
- Update Keeper Record action to modify existing vault records  
- Generate Keeper Secrets action to create secure passwords
- Choose Keeper Folder action to select vault storage location
- Persistent shell service with optimized performance (200-500ms vs 3-5 seconds)
- Cross-platform support for Windows, macOS, and Linux
- Lazy loading - shell only starts when user triggers actions
- Intelligent retry logic for shell startup timing issues
- Background task execution with progress indicators
- Comprehensive error handling and user feedback
- Integration with Keeper Commander CLI v17+

### Technical Features
- OS-specific timeout optimization (Windows: 120s, macOS/Linux: 45s)
- Robust JSON parsing with startup message detection
- Automatic shell health monitoring and restart capabilities
- Thread-safe command execution with timeout handling
- Comprehensive logging for debugging and troubleshooting

### Requirements  
- IntelliJ IDEA 2024.3+ (all JetBrains IDEs supported)
- Keeper Commander CLI v17.5+ installed and authenticated
- Active Keeper Security account with vault access

### Added
- Basic Keeper vault integration
- Core authentication workflow
- Initial secret retrieval functionality
