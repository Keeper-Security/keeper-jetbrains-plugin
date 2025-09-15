# Keeper Security JetBrains Plugin

<!-- Plugin description -->
A comprehensive JetBrains IDE plugin that integrates Keeper Security vault functionality directly into your development workflow. The plugin provides secure secret management capabilities including saving, retrieving, generating, and running commands with secrets from Keeper Security vault.

The goal is to enable developers to manage secrets securely without leaving their development environment, while maintaining the highest security standards and providing seamless integration with existing Keeper Security infrastructure.

**Supported IDEs:** IntelliJ IDEA, PyCharm, WebStorm, PhpStorm, RubyMine, CLion, GoLand, and all other JetBrains IDEs.
<!-- Plugin description end -->

## Table of Contents
- [Overview](#overview)
- [Features](#features)
- [Prerequisites](#prerequisites)
- [Setup](#setup)
- [Usage](#usage)
  - [Available Actions](#available-actions)
  - [Command Details](#command-details)
- [Troubleshooting](#troubleshooting)
- [Common Issues](#common-issues)
- [License](#license)

## Overview

A comprehensive JetBrains IDE plugin that integrates Keeper Security vault functionality directly into your development workflow. The plugin provides secure secret management capabilities including saving, retrieving, generating, and running commands with secrets from Keeper Security vault.

The goal is to enable developers to manage secrets securely without leaving their development environment, while maintaining the highest security standards and providing seamless integration with existing Keeper Security infrastructure.

**Supported IDEs:** IntelliJ IDEA, PyCharm, WebStorm, PhpStorm, RubyMine, CLion, GoLand, and all other JetBrains IDEs.

## Features

- **Secret Management**: Save, retrieve, and generate secrets directly from JetBrains IDEs using Keeper Security vault
- **Secure Execution**: Run commands with secrets injected from Keeper vault through `.env` file processing
- **Fast Performance**: Uses persistent Keeper shell for blazing-fast secret operations
- **Folder Management**: Select and manage Keeper vault folders for organized secret storage
- **Record Operations**: Create new records, update existing ones, and retrieve field references
- **Comprehensive Logging**: Built-in logging system with detailed operation tracking
- **Retry Logic**: Robust error handling with automatic retry for shell startup timing

## Prerequisites

- **Keeper Commander CLI**: Must be installed and authenticated on your system
  - Download from [Keeper Commander Installation Guide](https://docs.keeper.io/commander/)
  - Authenticate using persistent login or biometric login
- **Keeper Security Account**: Active subscription with vault access
- **System Requirements**:
  - JetBrains IDE: 2021.1 or later
  - Java: 11 or later

## Setup

### 1. Install the Plugin
- From JetBrains Marketplace: Search "Keeper Security" (when published)
- Or install manually from plugin file

### 2. Install Keeper Commander CLI
- Follow the [Keeper Commander Installation Guide](https://docs.keeper.io/commander/)
- Ensure the CLI is accessible from your system PATH
- Open terminal/command prompt and run `keeper login`
- Enter your Keeper Security credentials
- Verify installation with `keeper --version`

### 3. Authenticate with Keeper Commander CLI
- Open terminal/command prompt  
- Run `keeper login` and enter your credentials
- Authenticate using persistent login or biometric login
- **Important**: Wait for the "My Vault>" prompt to appear before using the plugin

### 4. Verify Plugin Access
- Open any JetBrains IDE
- Go to **Tools → Keeper Vault → Check Keeper Authorization**
- Verify the authentication status shows success

## Usage

### Available Actions

All Keeper actions are available through two locations:
1. **Tools Menu**: `Tools → Keeper Vault → [Action]`
2. **Right-click Context Menu**: Right-click in editor → `[Action]`

| Action | Description | Use Case |
|--------|-------------|----------|
| Check Keeper Authorization | Verify Keeper CLI installation and authentication | Troubleshoot connection issues |
| Get Keeper Secret | Insert existing secrets from vault as references | Retrieve stored secrets without exposing values |
| Add Keeper Record | Create new vault record from selected text | Replace hardcoded secrets with vault references |
| Update Keeper Record | Update existing vault record and replace text | Modify existing secret values |
| Generate Keeper Secret | Generate secure passwords and store in vault | Create new secure credentials |
| Get Keeper Folder | Select vault folder for organized storage | Choose storage location for new records |
| Run Keeper Securely | Execute commands with injected secrets from .env | Run applications with vault credentials |

### Command Details

#### Check Keeper Authorization
**Purpose**: Verify that Keeper Commander CLI is properly installed and authenticated.

**Steps**:
1. Go to `Tools → Keeper Vault → Check Keeper Authorization`
2. Plugin verifies CLI installation and authentication status
3. Shows detailed status including biometric auth availability
4. Use this if other commands fail or for initial setup verification

#### Get Keeper Secret  
**Purpose**: Insert existing Keeper Security secrets into your code without exposing actual values.

**Steps**:
1. Position cursor where you want to insert the secret reference
2. Right-click → `Get Keeper Secret` or `Tools → Keeper Vault → Get Keeper Secret`
3. Plugin shows list of available vault records
4. Select the specific record you want to use
5. Choose the field from that record
6. Plugin inserts secret reference at cursor position

**Reference Format**: `keeper://record-uid/field/field-name`

**Example**:
```python
# Cursor position before command
database_password = |

# After selecting from vault  
database_password = keeper://abc123def456/field/password
```

#### Add Keeper Record
**Purpose**: Save selected text as a secret in Keeper Security vault and replace it with a reference.

**Steps**:
1. Select text containing a secret (password, token, API key, etc.)
2. Right-click → `Add Keeper Record` or `Tools → Keeper Vault → Add Keeper Record`
3. Enter record title when prompted
4. Enter field name for the secret
5. Plugin creates new vault record
6. Selected text is replaced with secret reference

**Example**:
```javascript
// Before: Selected text
const apiKey = "sk-1234567890abcdef";

// After: Replaced with reference  
const apiKey = keeper://new-record-uid/field/api_key;
```

#### Update Keeper Record
**Purpose**: Update an existing Keeper record with new secret value and replace selected text with reference.

**Steps**:
1. Select text containing the updated secret value
2. Right-click → `Update Keeper Record`
3. Choose existing record from the list
4. Select field to update
5. Plugin updates the vault record
6. Selected text is replaced with reference

#### Generate Keeper Secret
**Purpose**: Generate secure passwords and store them in Keeper Security vault.

**Steps**:
1. Position cursor where you want the secret reference
2. Right-click → `Generate Keeper Secret`
3. Enter record title and field name
4. Plugin generates secure password and stores it in vault
5. Secret reference is inserted at cursor position

**Example**:
```yaml
# Before
admin_password: |

# After  
admin_password: keeper://generated-record-uid/field/password
```

#### Get Keeper Folder
**Purpose**: Select the vault folder where new secrets will be stored for this project.

**Steps**:
1. Go to `Tools → Keeper Vault → Get Keeper Folder`
2. Plugin displays available vault folders
3. Select desired folder for this workspace
4. Future `Add Keeper Record` and `Generate Keeper Secret` operations will use this folder

#### Run Keeper Securely
**Purpose**: Run commands with secrets injected from Keeper Security vault through `.env` file processing.

**Steps**:
1. Ensure your project has a `.env` file with `keeper://` references
2. Open any file in your project
3. Right-click → `Run Keeper Securely`
4. Select or confirm the `.env` file to use
5. Enter the command to run (e.g., `python3 app.py`)
6. Plugin fetches actual secret values from vault
7. Creates terminal with injected environment variables
8. Executes your command with real secret values

**Example `.env` file**:
```env
DATABASE_URL=keeper://db-record-uid/field/connection_string
API_KEY=keeper://api-record-uid/field/key
SECRET_KEY=keeper://app-record-uid/field/secret
```

**Command execution**:
```bash
# Plugin runs your command with actual values injected
python3 app.py
# DATABASE_URL=postgresql://user:pass@host:5432/db
# API_KEY=ak_live_1234567890abcdef  
# SECRET_KEY=super-secret-key-value
```

## Troubleshooting

### Enable Debug Logging
If you encounter issues, enable detailed logging:
1. Go to `Help → Diagnostic Tools → Debug Log Settings`
2. Add: `keepersecurity`
3. Restart IDE
4. Check logs in `Help → Show Log in Files`

### Common Issues

#### 1. Plugin Commands Not Working
**Problem**: Actions fail with authentication or connection errors

**Solutions**:
- Run `Tools → Keeper Vault → Check Keeper Authorization` first
- Ensure Keeper Commander CLI is installed and in PATH
- Verify authentication with `keeper login` in terminal
- Wait for "My Vault>" prompt to appear after login
- Restart IDE if commands were working before

#### 2. "No JSON array found in output" Error
**Problem**: Plugin fails to parse Keeper CLI output

**Solutions**:
- This is usually a timing issue with shell startup
- Plugin has built-in retry logic, wait for completion
- Check internet connection and firewall settings
- Verify Keeper vault accessibility
- Try the command again after a moment

#### 3. Keeper Commander CLI Not Found
**Problem**: "Keeper Commander CLI is not installed" error

**Solutions**:
- Install Keeper Commander CLI following the [installation guide](https://docs.keeper.io/commander/)
- Ensure CLI is accessible from your system PATH
- Verify installation with `keeper --version` in terminal
- Restart IDE after CLI installation

#### 4. Authentication Failures
**Problem**: "Keeper Commander CLI is not authenticated" errors

**Solutions**:
- Open terminal and run `keeper login`
- Enter your Keeper Security credentials
- Wait for successful authentication and "My Vault>" prompt
- Ensure authentication persists between sessions
- Try biometric authentication if available

#### 5. Empty Folder or Record Lists
**Problem**: No folders or records appear when trying to select them

**Solutions**:
- Verify you have access to folders/records in your vault
- Check that Keeper CLI has proper permissions
- Try refreshing by running the command again
- Ensure your Keeper account has the necessary access rights

#### 6. Run Securely Command Issues
**Problem**: Commands don't have access to injected secrets or fail to execute

**Solutions**:
- Verify your `.env` file contains valid `keeper://` references
- Ensure all referenced secrets exist in your vault
- Check that plugin created the terminal correctly
- Verify the command syntax is correct for your system
- Ensure no other terminals are interfering with environment variables

#### 7. Plugin Performance Issues
**Problem**: Commands are slow or hang

**Solutions**:
- Plugin uses persistent shell for speed after first use
- First command may take longer (shell startup)
- Subsequent commands should be much faster
- Check internet connection stability
- Verify Keeper vault server accessibility

## License

This plugin is licensed under the MIT License.

---

**Support**: For issues specific to the Keeper Security service, visit [Keeper Security Support](https://docs.keeper.io/). For plugin-specific issues, check the plugin documentation and logs.