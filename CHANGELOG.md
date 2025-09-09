# Keeper Security JetBrains Plugin Changelog

## [Unreleased]

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
