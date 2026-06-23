# Keeper Security JetBrains Plugin

<!-- Plugin description -->
A comprehensive JetBrains IDE plugin that integrates Keeper Security vault functionality directly into your development workflow. Supports **Classic** and **Nested Shared Folder** vault items in the same actions. Use Keeper references in `.env` files and in **JetBrains HTTP Client** (`.http` files) where supported, save **Run Keeper Securely** run configurations, and use Tools-menu actions to manage secrets without pasting plaintext into your project.

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
  - [JetBrains HTTP Client (.http files)](#jetbrains-http-client-http-files)
- [Troubleshooting](#troubleshooting)
- [Common Issues](#common-issues)
- [License](#license)

## Overview

A comprehensive JetBrains IDE plugin that integrates Keeper Security vault functionality directly into your development workflow: secret references in `.env` and **HTTP Client** requests, **Run Keeper Securely** from the Tools menu or as a saved run configuration, and vault actions for **Classic** and **Nested Shared Folder** items from the editor.

The goal is to enable developers to manage secrets securely without leaving their development environment, while maintaining the highest security standards and providing seamless integration with existing Keeper Security infrastructure.

**Supported IDEs:** IntelliJ IDEA, PyCharm, WebStorm, PhpStorm, RubyMine, CLion, GoLand, and all other JetBrains IDEs.

## Features

- **Secret Management**: Save, retrieve, and generate secrets directly from JetBrains IDEs using Keeper Security vault
- **Secure Execution**: Run commands with secrets injected from Keeper vault through `.env` file processing
- **JetBrains HTTP Client** (optional): Reference vault values in `.http` files via the `$keeper(...)` dynamic variable where the HTTP Client plugin is bundled (e.g. IntelliJ IDEA Ultimate)
- **Run configurations**: Save a **Run Keeper Securely** configuration (`.env` path, working directory, command) under **Run → Edit Configurations** with output in the Run tool window
- **Fast Performance**: Uses persistent Keeper shell for blazing-fast secret operations
- **Nested Shared Folders**: Classic and Nested Shared folders and records in the same actions; automatic routing to `record-*` or `nsf-*` Commander commands
- **Folder Management**: Select Classic or Nested Shared folders for organized secret storage
- **Record Operations**: Create new records, update existing ones, and retrieve field references
- **Comprehensive Logging**: Built-in logging system with detailed operation tracking
- **Retry Logic**: Robust error handling with automatic retry for shell startup timing

## Prerequisites

- **Keeper Commander CLI**: Must be installed and authenticated on your system
  - Download from [Keeper Commander Installation Guide](https://docs.keeper.io/commander/)
  - Authenticate using persistent login or biometric login
  - **Nested Shared Folder** create/update flows need a recent Commander with `nsf-*` commands ([CLI reference](https://docs.keeper.io/keeperpam/commander-cli/command-reference/nested-shared-folder)). Run `pip install --upgrade keepercommander` if you see unknown `nsf-record-add` errors.
- **Keeper Security Account**: Active subscription with vault access
- **System Requirements**:
  - JetBrains IDE: **2024.3 or later** (`pluginSinceBuild = 243`)
  - **HTTP Client** features require an IDE distribution that bundles the JetBrains HTTP Client (optional dependency); IntelliJ IDEA Community Edition does not include it by default
- **Building this plugin from source**: JDK **21** (Eclipse Temurin or JetBrains Runtime recommended for Gradle)

## Setup

### 1. Install the Plugin
- Open your JetBrains IDE (IntelliJ IDEA, PyCharm, WebStorm, etc.)
- Go to **File → Settings → Plugins** (or **IntelliJ IDEA → Preferences → Plugins** on macOS)
- Search for "**Keeper Security**" in the Marketplace tab
- Click **Install** and restart your IDE

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
| Get Keeper Secret | Insert existing secrets (Classic or Nested Shared) as references | Retrieve stored secrets without exposing values |
| Add Keeper Record | Create new vault record from selected text | Replace hardcoded secrets with vault references |
| Update Keeper Record | Update existing record by UID and replace text | Modify existing secret values |
| Generate Keeper Secret | Generate secure passwords and store in vault | Create new secure credentials |
| Get Keeper Folder | Select Classic or Nested Shared folder for this project | Choose storage location for new records |
| Run Keeper Securely | Run a command with secrets from `.env` (Tools menu or saved run configuration) | Run applications or scripts with vault-backed env vars |

### Command Details

#### Check Keeper Authorization
**Purpose**: Verify that Keeper Commander CLI is properly installed and authenticated.

**Steps**:
1. Go to `Tools → Keeper Vault → Check Keeper Authorization`
2. Plugin verifies CLI installation and authentication status
3. Shows detailed status including biometric auth availability
4. Use this if other commands fail or for initial setup verification

#### Get Keeper Secret  
**Purpose**: Insert existing Keeper Security secrets into your code or HTTP requests without exposing actual values.

**Steps**:
1. Position cursor where you want to insert the secret reference (including inside a `.http` file, when using HTTP Client)
2. Right-click → `Get Keeper Secret` or `Tools → Keeper Vault → Get Keeper Secret`
3. Plugin shows a searchable list of vault records (Classic and Nested Shared), each with a **Classic** or **Nested** badge
4. Select the record you want to use
5. Choose the field from that record
6. Plugin inserts the appropriate reference at the cursor (e.g. `keeper://…` in `.env`/code, or an HTTP Client snippet in `.http` files where supported)

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
3. Run **Get Keeper Folder** first if you want new records in a specific Classic or Nested Shared folder (otherwise the vault root is used)
4. Enter record title when prompted
5. Enter field name for the secret
6. Plugin creates the record in the correct vault (Classic or Nested Shared) and replaces the selection with a secret reference

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
1. Select text containing the updated secret value (or place the caret on the value after `=`)
2. Right-click → `Update Keeper Record`
3. Enter the Keeper **record UID** when prompted
4. Enter the **field name** to update
5. Plugin validates the UID, updates the record in the correct vault (Classic or Nested Shared), and replaces the selection with a `keeper://` reference

#### Generate Keeper Secret
**Purpose**: Generate secure passwords and store them in Keeper Security vault.

**Steps**:
1. Position cursor where you want the secret reference
2. Right-click → `Generate Keeper Secret`
3. Run **Get Keeper Folder** first if you want the new record in a specific Classic or Nested Shared folder
4. Enter record title and field name when prompted
5. Plugin generates a secure password, stores it in the correct vault, and inserts a secret reference at the cursor

**Example**:
```yaml
# Before
admin_password: |

# After  
admin_password: keeper://generated-record-uid/field/password
```

#### Get Keeper Folder
**Purpose**: Select a Classic or Nested Shared folder for this project (used by **Add Keeper Record** and **Generate Keeper Secret**).

**Steps**:
1. Go to `Tools → Keeper Vault → Get Keeper Folder`
2. Search and pick a folder; each row shows a **Classic** or **Nested** badge
3. The selection is saved for this project; add and generate actions use the matching Commander command family (`record-*` or `nsf-*`)

#### Run Keeper Securely
**Purpose**: Run commands with secrets injected from Keeper Security vault through `.env` file processing (`keeper://…` references are resolved at run time).

**Two ways to run**:

1. **Tools menu / editor (interactive)**  
   1. Ensure your project has a `.env` file with `keeper://` references.  
   2. Open a file in the project (e.g. your script).  
   3. **Tools → Keeper Vault → Run Keeper Securely** or right-click → **Run Keeper Securely**.  
   4. Choose the `.env` file and enter the command (defaults suggest your current file name).  
   5. The plugin resolves secrets, runs the command, and shows output in a dialog when successful.

2. **Saved run configuration (recommended for repeat runs)**  
   1. **Run → Edit Configurations… → + → Run Keeper Securely**.  
   2. Set **Environment file** (`.env`), **Working directory** (optional; empty = project root), and **Command** (e.g. `python main.py`).  
   3. Run with the usual Run/Debug actions; output appears in the **Run** tool window.  
   New configurations may prefill the Python interpreter (project SDK or venv) and common entry scripts (`main.py` / `app.py` / `run.py`) when those are present.

**Example `.env` file**:
```env
DATABASE_URL=keeper://db-record-uid/field/connection_string
API_KEY=keeper://api-record-uid/field/key
SECRET_KEY=keeper://app-record-uid/field/secret
```

Record UIDs in `keeper://` references must be valid Keeper record UIDs (22 URL-safe Base64 characters: `A-Z`, `a-z`, `0-9`, `_`, `-`). Invalid UIDs are skipped with an error message.

**Command execution** (conceptually):
```bash
# After resolution, your process receives real env values
python3 app.py
```

### JetBrains HTTP Client (.http files)

Where the **HTTP Client** plugin is available (bundled with many Ultimate-tier IDEs), you can reference Keeper values directly in `.http` files using the **`$keeper`** dynamic variable:

```http
### Example
GET https://api.example.com/v1/resource
Authorization: Bearer {{ $keeper("RECORD_UID", "password") }}
```

- Same record UID and field names as in `keeper://UID/field/...` references.  
- Use **Get Keeper Secret** in a `.http` file to insert the correct snippet.  
- On IDEs **without** HTTP Client, this extension is not loaded; the rest of the plugin still works.

See GitHub issues [#11](https://github.com/Keeper-Security/keeper-jetbrains-plugin/issues/11) (HTTP Client) and [#9](https://github.com/Keeper-Security/keeper-jetbrains-plugin/issues/9) (run configurations).

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
- Verify your `.env` file contains valid `keeper://` references with 22-character record UIDs
- Ensure all referenced secrets exist in your vault
- For **Run → Run Keeper Securely**, confirm paths and working directory in **Edit Configurations**
- For the **Tools** menu flow, check the result dialog and logs if the command fails
- Verify the command syntax is correct for your system

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