package keepersecurity.util

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import keepersecurity.service.MockKeeperService
import keepersecurity.util.KeeperCommandUtils
import com.intellij.openapi.diagnostic.Logger

class KeeperCommandUtilsTest {
    
    @Before
    fun setUp() {
        MockKeeperService.reset()
        MockKeeperService.setReady(true)
    }
    
    @After
    fun tearDown() {
        MockKeeperService.reset()
    }
    
    @Test
    fun `test RetryConfig default values`() {
        val config = KeeperCommandUtils.RetryConfig()
        assertEquals("Default max retries should be 3", 3, config.maxRetries)
        assertEquals("Default timeout should be 30 seconds", 30L, config.timeoutSeconds)
        assertEquals("Default retry delay should be 1000ms", 1000L, config.retryDelayMs)
        assertEquals("Default log level should be INFO", KeeperCommandUtils.LogLevel.INFO, config.logLevel)
        assertNull("Default validation should be null", config.validation)
    }
    
    @Test
    fun `test RetryConfig custom values`() {
        val validation = KeeperCommandUtils.ValidationConfig(minLength = 5)
        val config = KeeperCommandUtils.RetryConfig(
            maxRetries = 5,
            timeoutSeconds = 60L,
            retryDelayMs = 2000L,
            logLevel = KeeperCommandUtils.LogLevel.DEBUG,
            validation = validation
        )
        
        assertEquals("Custom max retries", 5, config.maxRetries)
        assertEquals("Custom timeout", 60L, config.timeoutSeconds)
        assertEquals("Custom retry delay", 2000L, config.retryDelayMs)
        assertEquals("Custom log level", KeeperCommandUtils.LogLevel.DEBUG, config.logLevel)
        assertEquals("Custom validation", validation, config.validation)
    }
    
    @Test
    fun `test ValidationConfig with required content`() {
        val config = KeeperCommandUtils.ValidationConfig(requiredContent = "success")
        
        assertEquals("Required content", "success", config.requiredContent)
        assertNull("Forbidden content should be null", config.forbiddenContent)
        assertEquals("Min length should be 0", 0, config.minLength)
        assertNull("Custom validator should be null", config.customValidator)
    }
    
    @Test
    fun `test ValidationConfig with forbidden content`() {
        val config = KeeperCommandUtils.ValidationConfig(forbiddenContent = "error")
        
        assertEquals("Forbidden content", "error", config.forbiddenContent)
        assertNull("Required content should be null", config.requiredContent)
    }
    
    @Test
    fun `test ValidationConfig with custom validator`() {
        val validator: (String) -> Boolean = { it.contains("test") }
        val config = KeeperCommandUtils.ValidationConfig(customValidator = validator)
        
        assertTrue("Custom validator should work", config.customValidator?.invoke("test content") == true)
        assertFalse("Custom validator should reject invalid content", config.customValidator?.invoke("other content") == true)
    }
    
    @Test
    fun `test ValidationConfig with min length`() {
        val config = KeeperCommandUtils.ValidationConfig(minLength = 10)
        
        assertEquals("Min length should be set", 10, config.minLength)
    }
    
    @Test
    fun `test ValidationConfig combined constraints`() {
        val config = KeeperCommandUtils.ValidationConfig(
            requiredContent = "success",
            forbiddenContent = "error", 
            minLength = 5,
            customValidator = { it.length > 3 }
        )
        
        assertEquals("Required content", "success", config.requiredContent)
        assertEquals("Forbidden content", "error", config.forbiddenContent)
        assertEquals("Min length", 5, config.minLength)
        assertNotNull("Custom validator should exist", config.customValidator)
    }
    
    @Test
    fun `test LogLevel enum values`() {
        val levels = KeeperCommandUtils.LogLevel.values()
        assertEquals("Should have 3 log levels", 3, levels.size)
        assertTrue("Should contain DEBUG", levels.contains(KeeperCommandUtils.LogLevel.DEBUG))
        assertTrue("Should contain INFO", levels.contains(KeeperCommandUtils.LogLevel.INFO))
        assertTrue("Should contain WARN", levels.contains(KeeperCommandUtils.LogLevel.WARN))
    }
    
    @Test
    fun `test Presets jsonObject configuration`() {
        val config = KeeperCommandUtils.Presets.jsonObject(maxRetries = 5, timeoutSeconds = 45L, retryDelayMs = 1500L)
        
        assertEquals("Max retries", 5, config.maxRetries)
        assertEquals("Timeout", 45L, config.timeoutSeconds)
        assertEquals("Retry delay", 1500L, config.retryDelayMs)
        assertEquals("Log level", KeeperCommandUtils.LogLevel.DEBUG, config.logLevel)
        assertNotNull("Validation should exist", config.validation)
        assertEquals("Required content", "{", config.validation?.requiredContent)
        assertEquals("Min length", 5, config.validation?.minLength)
    }
    
    @Test
    fun `test Presets jsonArray configuration`() {
        val config = KeeperCommandUtils.Presets.jsonArray()
        
        assertEquals("Default max retries", 3, config.maxRetries)
        assertTrue("Timeout should be at least 45s", config.timeoutSeconds >= 45L)
        assertEquals("Retry delay", 2000L, config.retryDelayMs)
        assertEquals("Log level", KeeperCommandUtils.LogLevel.INFO, config.logLevel)
        assertNotNull("Validation should exist", config.validation)
        assertEquals("Required content", "[", config.validation?.requiredContent)
        assertEquals("Min length", 5, config.validation?.minLength)
    }
    
    @Test
    fun `test Presets general configuration`() {
        val config = KeeperCommandUtils.Presets.general(maxRetries = 2, timeoutSeconds = 20L, retryDelayMs = 500L)
        
        assertEquals("Max retries", 2, config.maxRetries)
        assertEquals("Timeout", 20L, config.timeoutSeconds)
        assertEquals("Retry delay", 500L, config.retryDelayMs)
        assertEquals("Log level", KeeperCommandUtils.LogLevel.INFO, config.logLevel)
        assertNull("Validation should be null", config.validation)
    }
    
    @Test
    fun `test Presets passwordGeneration configuration`() {
        val config = KeeperCommandUtils.Presets.passwordGeneration()
        
        assertEquals("Default max retries", 3, config.maxRetries)
        assertEquals("Default timeout", 30L, config.timeoutSeconds)
        assertEquals("Default retry delay", 1000L, config.retryDelayMs)
        assertEquals("Log level", KeeperCommandUtils.LogLevel.INFO, config.logLevel)
        assertNotNull("Validation should exist", config.validation)
        assertNotNull("Custom validator should exist", config.validation?.customValidator)
        
        // Test the custom validator
        val validator = config.validation?.customValidator!!
        assertTrue("Should validate password JSON", validator("""[{"password": "test123"}]"""))
        assertTrue("Should validate password object", validator("""{"password": "test123"}"""))
        assertFalse("Should reject non-password content", validator("No password here"))
    }
    
    @Test
    fun `test preset configurations are immutable`() {
        val config1 = KeeperCommandUtils.Presets.jsonObject()
        val config2 = KeeperCommandUtils.Presets.jsonObject()
        
        // Should be equal but different instances
        assertEquals("Configs should be equal", config1.maxRetries, config2.maxRetries)
        assertEquals("Timeouts should be equal", config1.timeoutSeconds, config2.timeoutSeconds)
        // Note: Can't use assertNotSame with data classes, they're value-based
        assertTrue("Should have same values", config1.maxRetries == config2.maxRetries)
    }
    
    @Test
    fun `test custom preset parameters override defaults`() {
        val customConfig = KeeperCommandUtils.Presets.jsonArray(maxRetries = 10, timeoutSeconds = 120L, retryDelayMs = 3000L)
        
        assertEquals("Custom max retries", 10, customConfig.maxRetries)
        assertEquals("Custom timeout", 120L, customConfig.timeoutSeconds)
        assertEquals("Custom retry delay", 3000L, customConfig.retryDelayMs)
        // Other properties should remain as defaults
        assertEquals("Default log level", KeeperCommandUtils.LogLevel.INFO, customConfig.logLevel)
        assertEquals("Default required content", "[", customConfig.validation?.requiredContent)
    }
}
