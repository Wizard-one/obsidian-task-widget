package dev.local.taskwidget.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.net.URLEncoder

/** 用 obsidian:// URL scheme 在 Obsidian 里打开任务所在的笔记 */
object ObsidianLink {

    /** 构造 obsidian://open 链接。vault 名为空时省略 vault 参数(打开当前 vault 中同名文件)。 */
    fun buildUri(vaultName: String, path: String): Uri {
        val fileNoExt = path.removeSuffix(".md")
        val sb = StringBuilder("obsidian://open?")
        if (vaultName.isNotBlank()) sb.append("vault=").append(enc(vaultName)).append('&')
        sb.append("file=").append(enc(fileNoExt))
        return Uri.parse(sb.toString())
    }

    /** 尝试在 Obsidian 中打开;未安装 Obsidian 时提示。 */
    fun open(context: Context, path: String) {
        val uri = buildUri(VaultRepository.getVaultName(context), path)
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "未安装 Obsidian,或无法打开该笔记", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
