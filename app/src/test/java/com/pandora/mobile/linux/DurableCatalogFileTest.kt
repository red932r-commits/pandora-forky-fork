package com.pandora.mobile.linux

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableCatalogFileTest {
    @Test
    fun restoresValidatedMirrorAndPreservesCorruptPrimary() {
        val root = Files.createTempDirectory("pandora-catalog").toFile()
        try {
            val file = root.resolve("projects.json")
            val storage = DurableCatalogFile(file)
            storage.write("valid:first", ::validate)
            storage.write("valid:latest", ::validate)
            file.writeText("damaged")

            assertEquals("latest", storage.read("default", ::parse))
            assertEquals("valid:latest", file.readText())
            assertEquals("damaged", root.resolve("projects.json.corrupt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptionIsSurfacedAndCannotSilentlyBecomeAnEmptyCatalog() {
        val root = Files.createTempDirectory("pandora-catalog").toFile()
        try {
            val file = root.resolve("archive.json").apply { writeText("damaged") }
            val storage = DurableCatalogFile(file)

            assertTrue(runCatching { storage.read("default", ::parse) }.exceptionOrNull() is CatalogCorruptionException)
            assertTrue(runCatching { storage.read("default", ::parse) }.exceptionOrNull() is CatalogCorruptionException)
            assertEquals("damaged", root.resolve("archive.json.corrupt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun interruptedReplaceRecoversFromMirror() {
        val root = Files.createTempDirectory("pandora-catalog").toFile()
        try {
            val file = root.resolve("chat-cache.json")
            val storage = DurableCatalogFile(file)
            storage.write("valid:cached", ::validate)
            file.delete()

            assertEquals("cached", storage.read("default", ::parse))
            assertEquals("valid:cached", file.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun validate(text: String) {
        parse(text)
    }

    private fun parse(text: String): String {
        require(text.startsWith("valid:")) { "invalid catalog" }
        return text.removePrefix("valid:")
    }
}
