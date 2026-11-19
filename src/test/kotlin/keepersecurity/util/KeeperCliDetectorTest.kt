package keepersecurity.util

import keepersecurity.model.KeeperCliFlavor
import keepersecurity.model.KeeperCliInstall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Exercises [KeeperCliDetector] against fake `keeper` / `keeper-smk` shell
 * scripts in temp dirs that print the real Commander banner. Unix-only, because
 * the fakes are `#!/bin/sh` scripts.
 */
class KeeperCliDetectorTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var home: File
    private lateinit var pathBin: File

    @Before fun setUp() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("windows"))
        home = tmp.newFolder("home")
        pathBin = tmp.newFolder("pathbin")
    }

    private fun fakeCli(file: File, body: String = "echo 'Keeper Commander, version 18.1.8'"): File {
        file.parentFile.mkdirs()
        file.writeText("#!/bin/sh\n$body\n")
        file.setExecutable(true)
        return file
    }

    private fun smkWrapper(host: String, root: File = home): File =
        fakeCli(File(root, ".keeper-smk/$host/bin/keeper-smk"))

    private fun symlink(link: File, target: File): File {
        link.parentFile.mkdirs()
        Files.createSymbolicLink(link.toPath(), target.toPath())
        return link
    }

    private fun detect(
        vararg pathDirs: File,
        modules: List<String> = emptyList(),
        probe: ((List<String>) -> String?)? = null,
    ): List<KeeperCliInstall> {
        val env = KeeperCliDetector.Environment(
            isWindows = false,
            homeDirs = listOf(home),
            pathDirs = pathDirs.toList(),
            pythonModuleCommands = modules,
        )
        return (if (probe != null) KeeperCliDetector(env, probe) else KeeperCliDetector(env)).detectAll()
    }

    @Test fun `only pip keeper on PATH is detected as Commander`() {
        fakeCli(File(pathBin, "keeper"))
        val installs = detect(pathBin)
        assertEquals(1, installs.size)
        assertEquals(KeeperCliFlavor.COMMANDER, installs[0].flavor)
        assertEquals("18.1.8", installs[0].version)
        assertNull(installs[0].smkHost)
    }

    @Test fun `SMK wrapper under home is found without being on PATH`() {
        val wrapper = smkWrapper("keeper.metrondemo.com")
        val installs = detect(pathBin)
        assertEquals(1, installs.size)
        assertEquals(KeeperCliFlavor.SMK, installs[0].flavor)
        assertEquals("keeper.metrondemo.com", installs[0].smkHost)
        assertEquals(wrapper.canonicalPath, installs[0].canonicalPath)
        assertEquals("Keeper SMK: keeper.metrondemo.com (18.1.8)", installs[0].displayLabel())
    }

    @Test fun `Commander and SMK installed side by side are both listed`() {
        fakeCli(File(pathBin, "keeper"))
        smkWrapper("keeper.metrondemo.com")
        val installs = detect(pathBin)
        assertEquals(listOf(KeeperCliFlavor.SMK, KeeperCliFlavor.COMMANDER), installs.map { it.flavor })
    }

    @Test fun `keeper symlinked to the SMK wrapper is one SMK install`() {
        val wrapper = smkWrapper("keeper.metrondemo.com")
        symlink(File(pathBin, "keeper"), wrapper)
        val installs = detect(pathBin)
        assertEquals(1, installs.size)
        assertEquals(KeeperCliFlavor.SMK, installs[0].flavor)
        assertEquals("keeper.metrondemo.com", installs[0].smkHost)
    }

    @Test fun `keeper-smk on PATH and in home pointing to the same file is listed once`() {
        val wrapper = smkWrapper("localhost")
        symlink(File(pathBin, "keeper-smk"), wrapper)
        assertEquals(1, detect(pathBin).size)
    }

    @Test fun `keeper symlinked to the SMK venv binary runs through the host wrapper`() {
        val wrapper = smkWrapper("keeper.metrondemo.com")
        val venvKeeper = fakeCli(File(home, ".keeper-smk/keeper.metrondemo.com/venv/bin/keeper"))
        symlink(File(pathBin, "keeper"), venvKeeper)
        val installs = detect(pathBin)
        assertEquals(1, installs.size)
        assertEquals(KeeperCliFlavor.SMK, installs[0].flavor)
        assertEquals(wrapper.canonicalPath, File(installs[0].path).canonicalPath)
    }

    @Test fun `SMK venv binary without a host wrapper is skipped`() {
        val venvKeeper = fakeCli(File(home, ".keeper-smk/orphan.example.com/venv/bin/keeper"))
        symlink(File(pathBin, "keeper"), venvKeeper)
        assertTrue(detect(pathBin).isEmpty())
    }

    @Test fun `multiple SMK hosts are listed separately in host order`() {
        smkWrapper("localhost")
        smkWrapper("keeper.corp.com")
        val installs = detect(pathBin)
        assertEquals(listOf("keeper.corp.com", "localhost"), installs.map { it.smkHost })
        assertTrue(installs.all { it.flavor == KeeperCliFlavor.SMK })
    }

    @Test fun `SMK host directory may contain a port`() {
        smkWrapper("localhost:8443")
        assertEquals("localhost:8443", detect(pathBin).single().smkHost)
    }

    @Test fun `keeper-smk outside the SMK root is SMK with unknown host`() {
        fakeCli(File(pathBin, "keeper-smk"))
        val install = detect(pathBin).single()
        assertEquals(KeeperCliFlavor.SMK, install.flavor)
        assertNull(install.smkHost)
    }

    @Test fun `binaries that fail or are not Commander are ignored`() {
        fakeCli(File(pathBin, "keeper"), body = "echo 'Keeper Commander, version 18.1.8'; exit 1")
        fakeCli(File(home, ".keeper-smk/bad.example.com/bin/keeper-smk"), body = "echo 'something else'")
        assertTrue(detect(pathBin).isEmpty())
    }

    @Test fun `non-executable files are ignored`() {
        val file = fakeCli(File(pathBin, "keeper"))
        file.setExecutable(false)
        assertTrue(detect(pathBin).isEmpty())
    }

    @Test fun `nothing installed returns an empty list`() {
        assertTrue(detect(pathBin).isEmpty())
    }

    @Test fun `python module is tried only when no Commander executable exists`() {
        val probed = mutableListOf<List<String>>()
        val probe: (List<String>) -> String? = { cmd ->
            probed += cmd
            "Keeper Commander, version 17.0.0"
        }
        val installs = detect(pathBin, modules = listOf("python3 -m keepercommander"), probe = probe)
        val module = installs.single()
        assertTrue(module.isModule)
        assertEquals(KeeperCliFlavor.COMMANDER, module.flavor)
        assertEquals(listOf("python3", "-m", "keepercommander", "shell"), module.commandLine("shell"))

        probed.clear()
        fakeCli(File(pathBin, "keeper"))
        detect(pathBin, modules = listOf("python3 -m keepercommander"), probe = probe)
        assertTrue("module must not be probed when keeper exists", probed.none { it.first() == "python3" })
    }

    @Test fun `SMK alone does not suppress the python module fallback`() {
        smkWrapper("localhost")
        val installs = detect(pathBin, modules = listOf("python3 -m keepercommander"),
            probe = { "Keeper Commander, version 18.1.8" })
        assertEquals(setOf(KeeperCliFlavor.SMK, KeeperCliFlavor.COMMANDER), installs.map { it.flavor }.toSet())
    }

    @Test fun `parseVersion extracts the version number`() {
        assertEquals("18.1.8", KeeperCliDetector.parseVersion("Keeper Commander, version 18.1.8\n"))
        assertNull(KeeperCliDetector.parseVersion("no version here"))
    }

    @Test fun `SMK terminal command is the wrapper path, Commander is keeper`() {
        val smk = KeeperCliInstall("/h/.keeper-smk/x/bin/keeper-smk", "/h/.keeper-smk/x/bin/keeper-smk",
            KeeperCliFlavor.SMK, "18.1.8", smkHost = "x")
        val pip = KeeperCliInstall("/usr/local/bin/keeper", "/usr/local/bin/keeper", KeeperCliFlavor.COMMANDER, "18.1.8")
        assertEquals("/h/.keeper-smk/x/bin/keeper-smk", smk.terminalCommand())
        assertEquals("keeper", pip.terminalCommand())
        assertEquals("\"/My Home/.keeper-smk/x/bin/keeper-smk\"",
            smk.copy(path = "/My Home/.keeper-smk/x/bin/keeper-smk").terminalCommand())
    }
}
