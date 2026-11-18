package org.javacs.kt.formatting

import org.javacs.kt.KtfmtConfiguration
import com.facebook.ktfmt.format.Formatter as Ktfmt
import com.facebook.ktfmt.format.FormattingOptions as KtfmtOptions
import org.eclipse.lsp4j.FormattingOptions as LspFormattingOptions

private class KdocBlock(val text: String, val indent: String)

private class MaskedCode(val code: String, val blocks: List<KdocBlock>)

private val KDOC_PLACEHOLDER = Regex("/\\*TULIS_KDOC_(\\d+)\\*/")

class KtfmtFormatter(private val config: KtfmtConfiguration) : Formatter {
    override fun format(
        code: String,
        options: LspFormattingOptions,
    ): String {
        val style = when (config.style) {
            "google" -> KtfmtOptions.Style.GOOGLE
            "facebook" -> KtfmtOptions.Style.FACEBOOK
            "dropbox" -> KtfmtOptions.Style.DROPBOX
            else -> KtfmtOptions.Style.GOOGLE
        }
        val blockIndent = options.tabSize.takeUnless { it == 0 } ?: config.indent

        fun ktfmtOptions(removeUnusedImports: Boolean) = KtfmtOptions(
            style = style,
            maxWidth = config.maxWidth,
            blockIndent = blockIndent,
            continuationIndent = config.continuationIndent,
            removeUnusedImports = removeUnusedImports,
        )

        try {
            return Ktfmt.format(ktfmtOptions(config.removeUnusedImports), code)
        } catch (e: Exception) {
            System.err.println("[TULIS] ktfmt failed: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }

        val masked = maskKdocs(code)
        if (masked == null || masked.blocks.isEmpty()) return code
        return try {
            val formatted = Ktfmt.format(ktfmtOptions(false), masked.code)
            val restored = unmaskKdocs(formatted, masked.blocks)
            if (restored != null) {
                System.err.println("[TULIS] ktfmt retried with ${masked.blocks.size} KDoc block(s) left untouched")
                restored
            } else {
                code
            }
        } catch (e: Exception) {
            System.err.println("[TULIS] ktfmt skipped: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            code
        }
    }

    private fun maskKdocs(code: String): MaskedCode? {
        if (code.contains("TULIS_KDOC_")) return null
        val out = StringBuilder(code.length)
        val blocks = ArrayList<KdocBlock>()
        val n = code.length
        var i = 0
        while (i < n) {
            val c = code[i]
            if (c == '/' && code.startsWith("/**", i) && !code.startsWith("/**/", i)) {
                val end = code.indexOf("*/", i + 2)
                if (end < 0) return null
                val lineStart = code.lastIndexOf('\n', i - 1) + 1
                val prefix = code.substring(lineStart, i)
                blocks.add(KdocBlock(code.substring(i, end + 2), if (prefix.isBlank()) prefix else ""))
                out.append("/*TULIS_KDOC_").append(blocks.size - 1).append("*/")
                i = end + 2
            } else if (c == '/' && code.startsWith("/*", i)) {
                val end = code.indexOf("*/", i + 2)
                if (end < 0) return null
                out.append(code, i, end + 2)
                i = end + 2
            } else if (c == '/' && code.startsWith("//", i)) {
                var end = code.indexOf('\n', i)
                if (end < 0) end = n
                out.append(code, i, end)
                i = end
            } else if (c == '"') {
                val end = skipString(code, i)
                out.append(code, i, end)
                i = end
            } else if (c == '\'') {
                var j = i + 1
                if (j < n && code[j] == '\\') j += 2 else j += 1
                while (j < n && code[j] != '\'') j++
                val end = minOf(j + 1, n)
                out.append(code, i, end)
                i = end
            } else {
                out.append(c)
                i++
            }
        }
        return MaskedCode(out.toString(), blocks)
    }

    private fun skipString(code: String, start: Int): Int {
        val n = code.length
        if (code.startsWith("\"\"\"", start)) {
            var j = code.indexOf("\"\"\"", start + 3)
            if (j < 0) return n
            j += 3
            while (j < n && code[j] == '"') j++
            return j
        }
        var j = start + 1
        while (j < n) {
            val ch = code[j]
            if (ch == '\\') {
                j += 2
                continue
            }
            if (ch == '"') return j + 1
            if (ch == '\n') return j
            if (ch == '$' && j + 1 < n && code[j + 1] == '{') {
                var depth = 1
                j += 2
                while (j < n && depth > 0) {
                    if (code[j] == '{') depth++ else if (code[j] == '}') depth--
                    j++
                }
                continue
            }
            j++
        }
        return n
    }

    private fun unmaskKdocs(formatted: String, blocks: List<KdocBlock>): String? {
        val out = StringBuilder(formatted.length + 256)
        var last = 0
        var restored = 0
        for (m in KDOC_PLACEHOLDER.findAll(formatted)) {
            val block = blocks.getOrNull(m.groupValues[1].toInt()) ?: return null
            out.append(formatted, last, m.range.first)
            val lineStart = formatted.lastIndexOf('\n', m.range.first - 1) + 1
            val prefix = formatted.substring(lineStart, m.range.first)
            out.append(reindentKdoc(block, if (prefix.isBlank()) prefix else ""))
            last = m.range.last + 1
            restored++
        }
        if (restored != blocks.size) return null
        out.append(formatted, last, formatted.length)
        return out.toString()
    }

    private fun reindentKdoc(block: KdocBlock, newIndent: String): String {
        val lines = block.text.split("\n")
        if (lines.size == 1) return block.text
        val sb = StringBuilder(lines[0])
        for (k in 1 until lines.size) {
            val line = lines[k]
            sb.append('\n')
            if (block.indent.isNotEmpty() && line.startsWith(block.indent)) {
                sb.append(newIndent).append(line, block.indent.length, line.length)
            } else if (block.indent.isEmpty() && line.isNotBlank()) {
                sb.append(newIndent).append(line)
            } else {
                sb.append(line)
            }
        }
        return sb.toString()
    }
}
