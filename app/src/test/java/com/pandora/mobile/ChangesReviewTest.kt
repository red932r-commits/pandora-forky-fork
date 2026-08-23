package com.pandora.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangesReviewTest {
    @Test
    fun porcelainAndNumstatBecomeTypedFiles() {
        val files = parseChangedFiles(
            statusOutput = " M app/Main.kt\n?? app/New.kt\nD  old.txt\n",
            numstatOutput = "12\t3\tapp/Main.kt\n4\t0\tapp/New.kt\n0\t8\told.txt\n",
        )

        assertEquals(
            listOf(
                ChangedFile("app/Main.kt", ChangeKind.MODIFIED, 12, 3),
                ChangedFile("app/New.kt", ChangeKind.ADDED, 4, 0),
                ChangedFile("old.txt", ChangeKind.DELETED, 0, 8),
            ),
            files,
        )
    }

    @Test
    fun unifiedDiffTracksOldAndNewLineNumbers() {
        val lines = parseUnifiedDiff(
            """diff --git a/a.kt b/a.kt
--- a/a.kt
+++ b/a.kt
@@ -4,2 +4,3 @@
 same
-old
+new
+extra
""".trimIndent(),
        )

        assertEquals(DiffLine(DiffLineKind.CONTEXT, "same", 4, 4), lines[4])
        assertEquals(DiffLine(DiffLineKind.DELETION, "old", 5, null), lines[5])
        assertEquals(DiffLine(DiffLineKind.ADDITION, "new", null, 5), lines[6])
        assertEquals(DiffLine(DiffLineKind.ADDITION, "extra", null, 6), lines[7])
    }

    @Test
    fun filtersIncludeRenamedFilesWithoutLosingTheOtherKinds() {
        val files = listOf(
            ChangedFile("changed.kt", ChangeKind.MODIFIED),
            ChangedFile("moved.kt", ChangeKind.RENAMED),
        )

        assertEquals(listOf(files[1]), filterChangedFiles(files, ChangeKind.RENAMED))
        assertEquals(files, filterChangedFiles(files, null))
    }

    @Test
    fun diffOutputDistinguishesTextBinaryAndFailure() {
        val text = classifyDiffOutput(1, "@@ -1 +1 @@\n-old\n+new", exitCodeOneIsDiff = true)
        val binary = classifyDiffOutput(0, "Binary files a/image.png and b/image.png differ")
        val failed = classifyDiffOutput(128, "fatal: bad revision")

        assertTrue(text is DiffLoadState.Text)
        assertTrue(binary is DiffLoadState.BinaryOrUnsupported)
        assertEquals(DiffLoadState.Failed("fatal: bad revision"), failed)
    }
}
