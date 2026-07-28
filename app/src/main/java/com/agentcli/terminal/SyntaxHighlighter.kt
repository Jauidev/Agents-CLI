package com.agentcli.terminal

import android.graphics.Color
import android.text.Spannable
import android.text.style.ForegroundColorSpan

/**
 * Resaltado de sintaxis ligero por expresiones regulares (C2).
 *
 * No pretende ser un parser: colorea comentarios, cadenas, números y palabras
 * clave, que es el 90% de lo que hace legible un archivo cuando solo quieres
 * revisar lo que escribió el agente. Sin dependencias nuevas.
 *
 * Cada lenguaje se compila a UNA sola regex con cuatro grupos de nivel superior
 * (comentario | cadena | número | palabra clave) — todo lo de dentro son grupos
 * no capturadores. Al recorrer las coincidencias de izquierda a derecha, los
 * solapamientos se resuelven solos: una cadena dentro de un comentario ya no se
 * vuelve a pintar, porque el comentario consumió ese trozo.
 */
object SyntaxHighlighter {

    // Paleta Catppuccin, la misma que res/values/colors.xml.
    private val COLOR_COMMENT = Color.parseColor("#7F849C")
    private val COLOR_STRING = Color.parseColor("#A6E3A1")
    private val COLOR_NUMBER = Color.parseColor("#FAB387")
    private val COLOR_KEYWORD = Color.parseColor("#CBA6F7")

    /** Tamaño máximo a resaltar: por encima, el coste en el hilo de UI no compensa. */
    const val MAX_HIGHLIGHT_BYTES = 200 * 1024

    private const val C_COMMENT = """//[^\n]*|/\*[\s\S]*?\*/"""
    private const val HASH_COMMENT = """#[^\n]*"""
    private const val C_STRING =
        """"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`(?:\\.|[^`\\])*`"""
    private const val NUMBER = """\b\d+(?:\.\d+)?\b"""

    private val KW_C_FAMILY = words(
        "abstract", "as", "async", "await", "break", "case", "catch", "class", "companion",
        "const", "continue", "data", "default", "defer", "do", "else", "enum", "export",
        "extends", "false", "final", "finally", "fn", "for", "from", "fun", "func", "go",
        "if", "impl", "implements", "import", "in", "instanceof", "interface", "internal",
        "is", "let", "map", "match", "mod", "mut", "new", "null", "nil", "object", "open",
        "override", "package", "private", "protected", "pub", "public", "range", "return",
        "sealed", "static", "struct", "super", "suspend", "switch", "this", "throw",
        "throws", "trait", "true", "try", "type", "typeof", "use", "val", "var", "void",
        "when", "where", "while", "yield",
    )

    private val KW_PYTHON = words(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def",
        "del", "elif", "else", "except", "False", "finally", "for", "from", "global",
        "if", "import", "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass",
        "raise", "return", "True", "try", "while", "with", "yield",
    )

    private val KW_SHELL = words(
        "case", "do", "done", "elif", "else", "esac", "exit", "export", "fi", "for",
        "function", "if", "in", "local", "read", "return", "then", "until", "while",
    )

    private val KW_JSON = words("true", "false", "null")

    private fun words(vararg kw: String) = """\b(?:${kw.joinToString("|")})\b"""

    /** Una regex por lenguaje; null = sin resaltado (texto plano). */
    private val languages: Map<String, Regex> = buildMap {
        val cLike = compile(C_COMMENT, C_STRING, NUMBER, KW_C_FAMILY)
        listOf(
            "kt", "kts", "java", "js", "mjs", "cjs", "jsx", "ts", "tsx", "c", "h", "cpp",
            "hpp", "cc", "cs", "go", "rs", "swift", "php", "scala", "dart", "gradle",
        ).forEach { put(it, cLike) }

        val hashLike = compile(HASH_COMMENT, C_STRING, NUMBER, KW_PYTHON)
        listOf("py", "rb").forEach { put(it, hashLike) }

        val shellLike = compile(HASH_COMMENT, C_STRING, NUMBER, KW_SHELL)
        listOf("sh", "bash", "zsh", "profile", "bashrc").forEach { put(it, shellLike) }

        val configLike = compile(HASH_COMMENT, C_STRING, NUMBER, KW_JSON)
        listOf("yml", "yaml", "toml", "ini", "conf", "cfg", "properties", "env")
            .forEach { put(it, configLike) }

        val jsonLike = compile(null, C_STRING, NUMBER, KW_JSON)
        listOf("json", "jsonc").forEach { put(it, jsonLike) }

        val xmlLike = compile("""<!--[\s\S]*?-->""", C_STRING, NUMBER, """</?[A-Za-z][\w:.-]*""")
        listOf("xml", "html", "htm", "svg", "vue").forEach { put(it, xmlLike) }

        // Markdown: encabezados y bloques de código como "palabra clave"/"cadena".
        put("md", compile(null, """```[\s\S]*?```|`[^`\n]+`""", null, """(?m)^#{1,6} [^\n]*"""))
    }

    private fun compile(comment: String?, string: String?, number: String?, keyword: String?): Regex {
        // Los cuatro grupos SIEMPRE existen (aunque vacíos) para que los índices
        // 1..4 signifiquen siempre lo mismo. (?!) nunca casa.
        fun g(p: String?) = "(" + (p ?: "(?!)") + ")"
        return Regex(g(comment) + "|" + g(string) + "|" + g(number) + "|" + g(keyword))
    }

    /** ¿Sabemos colorear este archivo? */
    fun supports(fileName: String): Boolean = languages.containsKey(extensionOf(fileName))

    private fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase()

    /**
     * Repinta [text] entero según la extensión de [fileName]. Quita antes los
     * spans que puso una pasada anterior (los ajenos no se tocan).
     */
    fun highlight(text: Spannable, fileName: String) {
        text.getSpans(0, text.length, ForegroundColorSpan::class.java).forEach { text.removeSpan(it) }
        val regex = languages[extensionOf(fileName)] ?: return
        if (text.length > MAX_HIGHLIGHT_BYTES) return

        regex.findAll(text).forEach { m ->
            val color = when {
                m.groups[1] != null -> COLOR_COMMENT
                m.groups[2] != null -> COLOR_STRING
                m.groups[3] != null -> COLOR_NUMBER
                m.groups[4] != null -> COLOR_KEYWORD
                else -> return@forEach
            }
            text.setSpan(
                ForegroundColorSpan(color),
                m.range.first,
                m.range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
}
