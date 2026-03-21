package com.mit.attendance.agent


// ─────────────────────────────────────────
//  CODE BLOCK PARSER
//  Extracts the first fenced code block from a ChatGPT markdown reply.
//  Mirrors what chat.py returns via inner_text() — raw markdown.
// ─────────────────────────────────────────
object CodeBlockParser {

    /**
     * Extracts content from the first fenced code block found in [reply].
     *
     * Handles:
     *   ```cs
     *   ...code...
     *   ```
     *
     *   ```csharp
     *   ...code...
     *   ```
     *
     *   ``` (no language tag)
     *   ...code...
     *   ```
     *
     * Falls back to the full reply text if no fence is found.
     */
    fun extract(reply: String, expectedExtension: String? = null): ParseResult {
        val lines = reply.lines()
        var inBlock = false
        var lang = ""
        val blockLines = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (!inBlock) {
                if (trimmed.startsWith("```")) {
                    lang = trimmed.removePrefix("```").trim()
                    inBlock = true
                }
            } else {
                if (trimmed == "```") {
                    // end of block
                    return ParseResult(
                        content  = blockLines.joinToString("\n"),
                        language = lang,
                        found    = true
                    )
                } else {
                    blockLines.add(line)
                }
            }
        }

        // No fenced block found — return raw reply as fallback
        return ParseResult(
            content  = reply.trim(),
            language = expectedExtension ?: "",
            found    = false
        )
    }

    data class ParseResult(
        val content: String,   // extracted code / text
        val language: String,  // language tag from the fence (e.g. "cs", "python")
        val found: Boolean     // whether a proper code fence was found
    )
}