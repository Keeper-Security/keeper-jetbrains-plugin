package keepersecurity.util

import com.intellij.openapi.diagnostic.Logger

/**
 * Utility class for parsing JSON from Keeper CLI output that may contain additional text
 */
object KeeperJsonUtils {
    
    /**
     * Extract JSON from Keeper shell output that may contain additional text like "[79] record(s)"
     * Uses a simple but robust approach to find the actual JSON content.
     */
    fun extractJsonFromOutput(output: String, expectedType: String, logger: Logger? = null): String {
        logger?.debug("🔍 Searching for JSON $expectedType in output (${output.length} chars)")
        logger?.debug("Raw output preview: ${output.take(300)}...")
        
        try {
            // Strategy 1: Find the JSON by looking for the pattern after common Keeper CLI text
            val cleanedJson = findJsonAfterKeeperText(output, expectedType, logger)
            if (cleanedJson != null) {
                logger?.debug("Found JSON using Keeper text pattern")
                return cleanedJson
            }
            
            // Strategy 2: Look for properly formed JSON arrays/objects in the text
            val structuredJson = findStructuredJson(output, expectedType, logger)
            if (structuredJson != null) {
                logger?.debug("Found structured JSON")
                return structuredJson
            }
            
            // Strategy 3: Try to reconstruct JSON from individual objects
            if (expectedType == "array") {
                val reconstructedJson = reconstructJsonArray(output, logger)
                if (reconstructedJson != null) {
                    logger?.debug("Reconstructed JSON array from objects")
                    return reconstructedJson
                }
            }
            
        } catch (e: Exception) {
            logger?.warn("Error during JSON extraction: ${e.message}")
        }
        
        logger?.error("No JSON $expectedType found. Full output: $output")
        throw RuntimeException("No JSON $expectedType found in output")
    }
    
    /**
     * Look for JSON after typical Keeper CLI output patterns
     */
    private fun findJsonAfterKeeperText(output: String, expectedType: String, logger: Logger? = null): String? {
        val lines = output.lines()
        val targetChar = if (expectedType == "array") '[' else '{'
        
        // Look for lines that might precede JSON
        val triggerPatterns = listOf(
            Regex("Decrypted \\[\\d+\\] record\\(s\\)"),
            Regex("My Vault>"),
            Regex("\\[\\d+\\] record\\(s\\)")
        )
        
        for (i in lines.indices) {
            val line = lines[i].trim()
            
            // Check if this line matches a trigger pattern
            val matchesTrigger = triggerPatterns.any { it.containsMatchIn(line) }
            
            if (matchesTrigger) {
                logger?.debug("🎯 Found trigger pattern in line $i: '$line'")
                
                // Look for JSON in the following lines
                for (j in (i + 1) until lines.size) {
                    val jsonCandidate = lines[j].trim()
                    if (jsonCandidate.startsWith(targetChar.toString())) {
                        // Found potential JSON start, extract from here to end
                        val remainingLines = lines.subList(j, lines.size)
                        val jsonText = remainingLines.joinToString("\n").trim()
                        
                        if (isValidJsonStructure(jsonText, expectedType)) {
                            logger?.debug("Valid JSON found after trigger at line $j")
                            return jsonText
                        }
                    }
                }
            }
        }
        
        return null
    }
    
    /**
     * Look for properly structured JSON anywhere in the text
     */
    private fun findStructuredJson(output: String, expectedType: String, logger: Logger? = null): String? {
        val targetChar = if (expectedType == "array") '[' else '{'
        val endChar = if (expectedType == "array") ']' else '}'
        
        var startPos = 0
        while (startPos < output.length) {
            val jsonStart = output.indexOf(targetChar, startPos)
            if (jsonStart == -1) break
            
            // Skip obvious false matches like [84]
            if (targetChar == '[') {
                val nextClosePos = output.indexOf(']', jsonStart)
                if (nextClosePos != -1 && nextClosePos - jsonStart < 10) {
                    val content = output.substring(jsonStart + 1, nextClosePos)
                    if (content.trim().matches(Regex("\\d+"))) {
                        // This is [number], skip it
                        startPos = nextClosePos + 1
                        continue
                    }
                }
            }
            
            // Try to find the matching end bracket/brace
            val jsonEnd = findMatchingBracket(output, jsonStart, targetChar, endChar)
            if (jsonEnd != -1) {
                val jsonCandidate = output.substring(jsonStart, jsonEnd + 1)
                
                if (isValidJsonStructure(jsonCandidate, expectedType)) {
                    logger?.debug("Found valid JSON structure at position $jsonStart")
                    return jsonCandidate
                }
            }
            
            startPos = jsonStart + 1
        }
        
        return null
    }
    
    /**
     * Try to reconstruct a JSON array from individual JSON objects found in the text
     */
    private fun reconstructJsonArray(output: String, logger: Logger? = null): String? {
        val jsonObjects = mutableListOf<String>()
        val lines = output.lines()
        var currentObject = StringBuilder()
        var braceCount = 0
        var inJsonObject = false
        
        for (line in lines) {
            val trimmed = line.trim()
            
            // Count braces to track JSON object boundaries
            for (char in trimmed) {
                when (char) {
                    '{' -> {
                        braceCount++
                        if (braceCount == 1) inJsonObject = true
                    }
                    '}' -> {
                        braceCount--
                        if (braceCount == 0 && inJsonObject) {
                            // Complete JSON object
                            currentObject.append(line).append('\n')
                            val jsonObj = currentObject.toString().trim()
                            if (jsonObj.isNotEmpty()) {
                                jsonObjects.add(jsonObj)
                            }
                            currentObject.clear()
                            inJsonObject = false
                            continue
                        }
                    }
                }
            }
            
            if (inJsonObject) {
                currentObject.append(line).append('\n')
            }
        }
        
        if (jsonObjects.isNotEmpty()) {
            logger?.debug("🔧 Reconstructed ${jsonObjects.size} JSON objects")
            val arrayJson = "[\n" + jsonObjects.joinToString(",\n") + "\n]"
            return arrayJson
        }
        
        return null
    }
    
    /**
     * Find the matching closing bracket/brace for a given opening position
     */
    private fun findMatchingBracket(text: String, startPos: Int, openChar: Char, closeChar: Char): Int {
        var count = 1
        var pos = startPos + 1
        
        while (pos < text.length && count > 0) {
            when (text[pos]) {
                openChar -> count++
                closeChar -> count--
            }
            if (count == 0) return pos
            pos++
        }
        
        return -1
    }
    
    /**
     * Basic validation that the extracted text looks like valid JSON
     */
    private fun isValidJsonStructure(json: String, expectedType: String): Boolean {
        val trimmed = json.trim()
        
        return when (expectedType) {
            "array" -> {
                trimmed.startsWith("[") && trimmed.endsWith("]") && 
                trimmed.contains("{") && trimmed.length > 10
            }
            "object" -> {
                trimmed.startsWith("{") && trimmed.endsWith("}") && 
                trimmed.contains(":") && trimmed.length > 5
            }
            else -> false
        }
    }
    
    /**
     * Extract JSON array from Keeper CLI output
     */
    fun extractJsonArray(output: String, logger: Logger? = null): String {
        return extractJsonFromOutput(output, "array", logger)
    }
    
    /**
     * Extract JSON object from Keeper CLI output  
     */
    fun extractJsonObject(output: String, logger: Logger? = null): String {
        return extractJsonFromOutput(output, "object", logger)
    }
}