package com.agentcli.terminal

/**
 * Representa una "sesión" o pestaña de terminal.
 *
 * Cada agente corre en su propia instancia de ttyd dentro de Termux, escuchando
 * en un puerto distinto de localhost. Así podemos tener las tres sesiones vivas
 * en paralelo y cambiar entre ellas sin matar procesos.
 *
 * @param id           Identificador estable (se usa para el menú de navegación).
 * @param title        Nombre visible en la barra inferior.
 * @param port         Puerto local donde escucha el ttyd de este agente.
 * @param bootstrapArg Valor que se pasa al script de arranque en Termux para
 *                     saber qué CLI activar (ver termux/agent-session.sh).
 */
data class AgentSession(
    val id: Int,
    val title: String,
    val port: Int,
    val bootstrapArg: String,
) {
    /** URL del WebView: el frontend (xterm.js) que sirve ttyd. */
    val url: String
        get() = "http://127.0.0.1:$port"
}

/**
 * Catálogo v0 de sesiones. Hardcodeado a propósito: en una iteración futura
 * esto vendrá de preferencias/persistencia y podrá editarse.
 *
 * Los puertos son arbitrarios pero deben coincidir con los que levante el
 * script de Termux (termux/agent-session.sh).
 */
object Sessions {
    val all: List<AgentSession> = listOf(
        AgentSession(id = 1, title = "Claude Code", port = 7681, bootstrapArg = "claude"),
        AgentSession(id = 2, title = "Codex CLI",   port = 7682, bootstrapArg = "codex"),
        AgentSession(id = 3, title = "Gemini CLI",  port = 7683, bootstrapArg = "gemini"),
    )

    /** Sesión mostrada al abrir la app. */
    val default: AgentSession = all.first()

    fun byId(id: Int): AgentSession = all.first { it.id == id }
}
