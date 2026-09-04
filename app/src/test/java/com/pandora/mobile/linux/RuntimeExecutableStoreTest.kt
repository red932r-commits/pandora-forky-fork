package com.pandora.mobile.linux

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeExecutableStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun updatesAndRollsBackLegacyNpmSymlinkWithoutTouchingUserData() {
        val workspace = temporary.newFolder("workspace")
        val old = executable(workspace, ".local/lib/node_modules/@openai/codex/bin/codex.js", "old")
        val store = RuntimeExecutableStore(workspace, "codex")
        store.live.parentFile!!.mkdirs()
        Files.createSymbolicLink(store.live.toPath(), store.live.parentFile!!.toPath().relativize(old.toPath()))
        val data = File(workspace, ".codex/auth.json").apply { parentFile!!.mkdirs(); writeText("user state") }
        val candidate = executable(workspace, ".local/share/pandora/runtimes/codex/new/bin/codex", "new")
        store.activate(candidate)
        assertEquals("new", store.live.readText())
        assertTrue(store.canRollback)
        assertFalse(Files.readSymbolicLink(store.live.toPath()).isAbsolute)
        store.rollback()
        assertEquals("old", store.live.readText())
        assertFalse(store.canRollback)
        assertEquals("user state", data.readText())
    }

    @Test fun regularBinaryRollbackSurvivesWorkspaceRelocation() {
        val workspace = temporary.newFolder("workspace")
        executable(workspace, ".local/bin/zmx", "old")
        val store = RuntimeExecutableStore(workspace, "zmx")
        store.activate(executable(workspace, ".local/share/pandora/runtimes/zmx/new/zmx", "new"))
        val moved = File(temporary.root, "restored")
        assertTrue(workspace.renameTo(moved))
        val restored = RuntimeExecutableStore(moved, "zmx")
        assertEquals("new", restored.live.readText())
        restored.rollback()
        assertEquals("old", restored.live.readText())
        assertTrue(restored.live.canExecute())
    }

    @Test fun invalidCandidateLeavesCurrentInstallationUntouched() {
        val workspace = temporary.newFolder("workspace")
        executable(workspace, ".local/bin/codex", "old")
        val store = RuntimeExecutableStore(workspace, "codex")
        val external = executable(workspace, "projects/codex", "bad")
        assertThrows(IllegalArgumentException::class.java) { store.activate(external) }
        assertEquals("old", store.live.readText())
        assertFalse(store.canRollback)
    }

    @Test fun pruningRetainsCurrentAndPreviousAndDoesNotFollowExternalLinks() {
        val workspace = temporary.newFolder("workspace")
        val store = RuntimeExecutableStore(workspace, "codex")
        val first = store.createStage("0.1.0")
        store.activate(executable(first, "bin/codex", "first"))
        val external = temporary.newFolder("unrelated")
        val data = File(external, "project").apply { writeText("keep") }
        Files.createSymbolicLink(File(first, "external").toPath(), external.toPath())
        val second = store.createStage("0.2.0")
        store.activate(executable(second, "bin/codex", "second"))
        val third = store.createStage("0.3.0")
        store.activate(executable(third, "bin/codex", "third"))
        store.prune()
        assertFalse(first.exists())
        assertTrue(second.exists())
        assertTrue(third.exists())
        assertEquals("keep", data.readText())
        store.rollback()
        store.prune()
        assertEquals("second", store.live.readText())
        assertFalse(third.exists())
        assertTrue(second.exists())
    }

    private fun executable(root: File, path: String, contents: String): File = File(root, path).apply {
        parentFile!!.mkdirs()
        writeText(contents)
        check(setExecutable(true))
    }
}
