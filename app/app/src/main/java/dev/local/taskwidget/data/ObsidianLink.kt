package dev.local.taskwidget.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.net.URLEncoder

/** 用 obsidian:// URL scheme 在 Obsidian 里打开任务所在的笔记 */
object ObsidianLink {

    /** 构造 obsidian://open 链接。vault 名为空时省略 vault 参数(打开当前 vault 中同名文件)。 */
    fun buildUri(vaultName: String, path: String): Uri = Uri.parse(buildUriString(vaultName, path))

    internal fun buildUriString(vaultName: String, path: String): String {
        val fileNoExt = path.removeSuffix(".md")
        val sb = StringBuilder("obsidian://open?")
        if (vaultName.isNotBlank()) sb.append("vault=").append(enc(vaultName)).append('&')
        sb.append("file=").append(enc(fileNoExt))
        return sb.toString()
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

    /**
     * 笔记 widget 打开策略:有 Vault 相对路径时优先显式交给 Obsidian,
     * 否则(或启动失败)把 SAF 文档 URI 授权给系统 Markdown/文本应用。
     */
    fun openNoteOrFile(context: Context, vaultPath: String?, documentUri: Uri): Boolean {
        if (vaultPath != null) {
            val obsidian = Intent(Intent.ACTION_VIEW, buildUri(VaultRepository.getVaultName(context), vaultPath))
                .setPackage("md.obsidian")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(obsidian)
                return true
            } catch (_: Exception) {
                // 未安装 Obsidian 或无法处理时继续走 content URI
            }
        }

        for (mime in listOf("text/markdown", "text/plain")) {
            val viewer = Intent(Intent.ACTION_VIEW)
                .setDataAndType(documentUri, mime)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(viewer)
                return true
            } catch (_: Exception) {
                // 尝试兼容性更高的下一个 MIME
            }
        }
        return false
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
