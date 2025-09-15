package keepersecurity.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.SerializationException
import org.junit.Test
import org.junit.Assert.*

class KeeperModelsTest {
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    @Test
    fun `test KeeperFolder basic serialization`() {
        val folder = KeeperFolder("123456789012345678901", "My Folder")
        val jsonString = json.encodeToString(folder)
        val decoded = json.decodeFromString<KeeperFolder>(jsonString)
        
        assertEquals("Folder UID should match", folder.folderUid, decoded.folderUid)
        assertEquals("Folder name should match", folder.name, decoded.name)
        assertNull("Flags should be null", decoded.flags)
        assertNull("Parent UID should be null", decoded.parentUid)
    }

    @Test
    fun `test KeeperFolder with all fields`() {
        val folder = KeeperFolder(
            folderUid = "abc123456789012345678",
            name = "Work Documents",
            flags = "shared",
            parentUid = "parent123456789012345678"
        )
        
        val jsonString = json.encodeToString(folder)
        val decoded = json.decodeFromString<KeeperFolder>(jsonString)
        
        assertEquals("Folder UID", folder.folderUid, decoded.folderUid)
        assertEquals("Folder name", folder.name, decoded.name)
        assertEquals("Flags", folder.flags, decoded.flags)
        assertEquals("Parent UID", folder.parentUid, decoded.parentUid)
    }

    @Test
    fun `test KeeperFolder deserialization from Keeper CLI format`() {
        val keeperJson = """
            {
                "folder_uid": "BT7L_4GPQaOTtKOEZOoGgw",
                "name": "Personal",
                "flags": "",
                "parent_uid": null
            }
        """
        
        val folder = json.decodeFromString<KeeperFolder>(keeperJson)
        assertEquals("BT7L_4GPQaOTtKOEZOoGgw", folder.folderUid)
        assertEquals("Personal", folder.name)
        assertEquals("", folder.flags)
        assertNull(folder.parentUid)
    }

    @Test
    fun `test KeeperRecord basic serialization`() {
        val record = KeeperRecord("rec123456789012345678", "My Record")
        val jsonString = json.encodeToString(record)
        val decoded = json.decodeFromString<KeeperRecord>(jsonString)
        
        assertEquals("Record UID should match", record.recordUid, decoded.recordUid)
        assertEquals("Record title should match", record.title, decoded.title)
        assertNull("Fields should be null", decoded.fields)
        assertNull("Custom fields should be null", decoded.custom)
    }

    @Test
    fun `test KeeperRecord with fields`() {
        val passwordField = KeeperField("password", listOf("secret123"))
        val loginField = KeeperField("login", listOf("user@example.com"))
        val record = KeeperRecord(
            recordUid = "rec123456789012345678",
            title = "Login Record",
            fields = listOf(passwordField, loginField)
        )
        
        val jsonString = json.encodeToString(record)
        val decoded = json.decodeFromString<KeeperRecord>(jsonString)
        
        assertEquals("Record UID", record.recordUid, decoded.recordUid)
        assertEquals("Record title", record.title, decoded.title)
        assertEquals("Fields count", 2, decoded.fields?.size)
        assertEquals("Password field type", "password", decoded.fields?.get(0)?.type)
        assertEquals("Password field value", "secret123", decoded.fields?.get(0)?.value?.get(0))
        assertEquals("Login field type", "login", decoded.fields?.get(1)?.type)
        assertEquals("Login field value", "user@example.com", decoded.fields?.get(1)?.value?.get(0))
    }

    @Test
    fun `test KeeperRecord with custom fields`() {
        val customField = KeeperCustomField("API Key", listOf("key_123456"))
        val record = KeeperRecord(
            recordUid = "rec123456789012345678",
            title = "API Record",
            custom = listOf(customField)
        )
        
        val jsonString = json.encodeToString(record)
        val decoded = json.decodeFromString<KeeperRecord>(jsonString)
        
        assertEquals("Custom fields count", 1, decoded.custom?.size)
        assertEquals("Custom field label", "API Key", decoded.custom?.get(0)?.label)
        assertEquals("Custom field value", "key_123456", decoded.custom?.get(0)?.value?.get(0))
    }

    @Test
    fun `test KeeperField with multiple values`() {
        val field = KeeperField("phone", listOf("+1-555-1234", "+1-555-5678"))
        val jsonString = json.encodeToString(field)
        val decoded = json.decodeFromString<KeeperField>(jsonString)
        
        assertEquals("Field type", "phone", decoded.type)
        assertEquals("Values count", 2, decoded.value?.size)
        assertEquals("First value", "+1-555-1234", decoded.value?.get(0))
        assertEquals("Second value", "+1-555-5678", decoded.value?.get(1))
    }

    @Test
    fun `test KeeperField with empty values`() {
        val field = KeeperField("note", null)
        val jsonString = json.encodeToString(field)
        val decoded = json.decodeFromString<KeeperField>(jsonString)
        
        assertEquals("Field type", "note", decoded.type)
        assertNull("Values should be null", decoded.value)
    }

    @Test
    fun `test KeeperCustomField serialization`() {
        val customField = KeeperCustomField("License Key", listOf("ABC-123-DEF-456"))
        val jsonString = json.encodeToString(customField)
        val decoded = json.decodeFromString<KeeperCustomField>(jsonString)
        
        assertEquals("Custom field label", customField.label, decoded.label)
        assertEquals("Custom field values", customField.value, decoded.value)
    }

    @Test
    fun `test KeeperSecret basic serialization`() {
        val secret = KeeperSecret("password123")
        val jsonString = json.encodeToString(secret)
        val decoded = json.decodeFromString<KeeperSecret>(jsonString)
        
        assertEquals("Password should match", secret.password, decoded.password)
    }

    @Test
    fun `test KeeperSecret with null password`() {
        val secret = KeeperSecret(null)
        val jsonString = json.encodeToString(secret)
        val decoded = json.decodeFromString<KeeperSecret>(jsonString)
        
        assertNull("Password should be null", decoded.password)
    }

    @Test
    fun `test GeneratedPassword serialization`() {
        val generatedPassword = GeneratedPassword("Kx9#mL2\$vN8!qR5@")
        val jsonString = json.encodeToString(generatedPassword)
        val decoded = json.decodeFromString<GeneratedPassword>(jsonString)
        
        assertEquals("Generated password should match", generatedPassword.password, decoded.password)
    }

    @Test
    fun `test deserialization with unknown fields ignored`() {
        val jsonWithUnknownFields = """
            {
                "record_uid": "abc123456789012345678",
                "title": "Test Record",
                "unknown_field": "should be ignored",
                "another_unknown": 123,
                "fields": []
            }
        """
        
        val record = json.decodeFromString<KeeperRecord>(jsonWithUnknownFields)
        assertEquals("Record UID", "abc123456789012345678", record.recordUid)
        assertEquals("Record title", "Test Record", record.title)
        assertNotNull("Fields should not be null", record.fields)
        assertTrue("Fields should be empty", record.fields?.isEmpty() == true)
    }

    @Test
    fun `test lenient parsing with trailing commas`() {
        val jsonWithTrailingComma = """
            {
                "record_uid": "abc123456789012345678",
                "title": "Test Record",
            }
        """
        
        // Should not throw due to lenient parsing
        val record = json.decodeFromString<KeeperRecord>(jsonWithTrailingComma)
        assertEquals("Record UID", "abc123456789012345678", record.recordUid)
        assertEquals("Record title", "Test Record", record.title)
    }

    @Test
    fun `test real Keeper CLI folder output parsing`() {
        val realKeeperOutput = """
        [
            {
                "folder_uid": "BT7L_4GPQaOTtKOEZOoGgw",
                "name": "Personal",
                "flags": "",
                "parent_uid": null
            },
            {
                "folder_uid": "Xj9K_8MPQsRtUoLzVcXyFq",
                "name": "Work",
                "flags": "shared",
                "parent_uid": "BT7L_4GPQaOTtKOEZOoGgw"
            }
        ]
        """
        
        val folders = json.decodeFromString<List<KeeperFolder>>(realKeeperOutput)
        assertEquals("Should have 2 folders", 2, folders.size)
        
        val personalFolder = folders[0]
        assertEquals("Personal folder UID", "BT7L_4GPQaOTtKOEZOoGgw", personalFolder.folderUid)
        assertEquals("Personal folder name", "Personal", personalFolder.name)
        assertNull("Personal folder parent should be null", personalFolder.parentUid)
        
        val workFolder = folders[1]
        assertEquals("Work folder name", "Work", workFolder.name)
        assertEquals("Work folder parent", "BT7L_4GPQaOTtKOEZOoGgw", workFolder.parentUid)
        assertEquals("Work folder flags", "shared", workFolder.flags)
    }

    @Test
    fun `test real Keeper CLI record output parsing`() {
        val realKeeperRecord = """
        {
            "record_uid": "BT7L_4GPQaOTtKOEZOoGgw",
            "title": "GitHub Account",
            "fields": [
                {
                    "type": "login",
                    "value": ["john.doe@company.com"]
                },
                {
                    "type": "password", 
                    "value": ["SuperSecret123!"]
                },
                {
                    "type": "url",
                    "value": ["https://github.com"]
                }
            ],
            "custom": [
                {
                    "label": "Personal Access Token",
                    "value": ["ghp_abcdef1234567890"]
                }
            ]
        }
        """
        
        val record = json.decodeFromString<KeeperRecord>(realKeeperRecord)
        assertEquals("Record UID", "BT7L_4GPQaOTtKOEZOoGgw", record.recordUid)
        assertEquals("Record title", "GitHub Account", record.title)
        assertEquals("Standard fields count", 3, record.fields?.size)
        assertEquals("Custom fields count", 1, record.custom?.size)
        
        // Check standard fields
        val loginField = record.fields?.find { it.type == "login" }
        assertEquals("Login value", "john.doe@company.com", loginField?.value?.get(0))
        
        val passwordField = record.fields?.find { it.type == "password" }
        assertEquals("Password value", "SuperSecret123!", passwordField?.value?.get(0))
        
        // Check custom fields
        val tokenField = record.custom?.get(0)
        assertEquals("Custom field label", "Personal Access Token", tokenField?.label)
        assertEquals("Custom field value", "ghp_abcdef1234567890", tokenField?.value?.get(0))
    }

    @Test
    fun `test GeneratedPassword from real generate command`() {
        val generateOutput = """
        [
            {
                "password": "Tp8${'$'}nK3mL9qR2"
            }
        ]
        """
        
        val passwords = json.decodeFromString<List<GeneratedPassword>>(generateOutput)
        assertEquals("Should have 1 password", 1, passwords.size)
        assertEquals("Generated password", "Tp8\$nK3mL9qR2", passwords[0].password)
    }
}
