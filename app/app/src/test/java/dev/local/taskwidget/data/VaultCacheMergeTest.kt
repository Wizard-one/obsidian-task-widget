package dev.local.taskwidget.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VaultCacheMergeTest {
    @Test
    fun scanKeepsTaskChangesMadeWhileItWasReading() {
        val scannedFiles = JSONObject().put("edited", JSONObject().put("tasks", "old"))
            .put("deleted", JSONObject().put("tasks", "old"))
            .put("untouched", JSONObject().put("tasks", "same"))
        val scannedMtimes = JSONObject().put("edited", 1).put("deleted", 1).put("untouched", 1)
        val latestFiles = JSONObject().put("edited", JSONObject().put("tasks", "new"))
            .put("created", JSONObject().put("tasks", "new"))
        val latestMtimes = JSONObject().put("edited", 2).put("deleted", 2).put("created", 2)

        mergeNewerCacheEntries(
            scannedFiles, scannedMtimes, latestFiles, latestMtimes,
            setOf("edited", "deleted", "created"),
        )

        assertEquals("new", scannedFiles.getJSONObject("edited").getString("tasks"))
        assertFalse(scannedFiles.has("deleted"))
        assertEquals("new", scannedFiles.getJSONObject("created").getString("tasks"))
        assertEquals("same", scannedFiles.getJSONObject("untouched").getString("tasks"))
        assertEquals(2, scannedMtimes.getInt("edited"))
        assertEquals(2, scannedMtimes.getInt("deleted"))
    }
}
