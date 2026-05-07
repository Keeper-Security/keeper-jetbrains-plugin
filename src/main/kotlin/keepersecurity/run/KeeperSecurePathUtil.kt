package keepersecurity.run

import java.io.File

object KeeperSecurePathUtil {
    @JvmStatic
    fun resolveToFile(path: String, projectBase: String): File {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return File(projectBase)
        val f = File(trimmed)
        return if (f.isAbsolute) f else File(projectBase, trimmed)
    }
}
