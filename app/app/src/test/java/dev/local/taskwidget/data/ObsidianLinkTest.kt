package dev.local.taskwidget.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ObsidianLinkTest {

    @Test
    fun buildUriString_encodesVaultUnicodeSpacesAndNestedPath() {
        assertEquals(
            "obsidian://open?vault=%E6%88%91%E7%9A%84%20Vault&file=%E9%A1%B9%E7%9B%AE%2F%E4%BC%9A%E8%AE%AE%20%E8%AE%B0%E5%BD%95",
            ObsidianLink.buildUriString("我的 Vault", "项目/会议 记录.md"),
        )
    }

    @Test
    fun buildUriString_omitsBlankVault() {
        assertEquals(
            "obsidian://open?file=Inbox",
            ObsidianLink.buildUriString("", "Inbox.md"),
        )
    }
}
