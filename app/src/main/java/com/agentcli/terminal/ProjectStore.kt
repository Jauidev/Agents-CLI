package com.agentcli.terminal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Un proyecto = una carpeta de trabajo recordada por la app (Modo Proyectos, F4).
 *
 * @param name       Nombre visible (normalmente el de la carpeta).
 * @param guestPath  Ruta dentro del guest (p.ej. /sdcard/Download/Test Ia).
 * @param lastUsed   Epoch (ms) de la última vez que se abrió; ordena la lista.
 * @param lastAgent  Último CLI usado en este proyecto (claude/codex/gemini).
 */
data class Project(
    val name: String,
    val guestPath: String,
    val lastUsed: Long,
    val lastAgent: String,
) {
    /**
     * Token estable y seguro para tmux derivado de la ruta (tmux no admite
     * '.'/':'), usado para separar sesiones entre proyectos: <agente>_<token>.
     */
    val token: String
        get() = Integer.toHexString(guestPath.hashCode())

    /** Nombre de la sesión tmux de [agent] en este proyecto. */
    fun tmuxSession(agent: String): String = "${agent}_$token"
}

/**
 * Persiste la lista de proyectos recientes en SharedPreferences como un array
 * JSON. La identidad de un proyecto es su [Project.guestPath].
 */
object ProjectStore {

    private const val PREFS = "projects"
    private const val KEY_LIST = "list"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Proyectos ordenados por uso reciente (el más reciente primero). */
    fun all(ctx: Context): List<Project> {
        val raw = prefs(ctx).getString(KEY_LIST, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val list = ArrayList<Project>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list += Project(
                name = o.optString("name"),
                guestPath = o.optString("path"),
                lastUsed = o.optLong("lastUsed"),
                lastAgent = o.optString("lastAgent", "claude"),
            )
        }
        return list.sortedByDescending { it.lastUsed }
    }

    fun byPath(ctx: Context, guestPath: String): Project? =
        all(ctx).firstOrNull { it.guestPath == guestPath }

    /** Crea el proyecto si no existe, o actualiza su marca de tiempo (y nombre). */
    fun touch(ctx: Context, name: String, guestPath: String, agent: String? = null): Project {
        val existing = byPath(ctx, guestPath)
        val updated = Project(
            name = name.ifBlank { existing?.name ?: guestPath.substringAfterLast('/') },
            guestPath = guestPath,
            lastUsed = System.currentTimeMillis(),
            lastAgent = agent ?: existing?.lastAgent ?: "claude",
        )
        save(ctx, all(ctx).filterNot { it.guestPath == guestPath } + updated)
        return updated
    }

    /** Guarda el último CLI usado en el proyecto (para reabrir en esa pestaña). */
    fun setLastAgent(ctx: Context, guestPath: String, agent: String) {
        val p = byPath(ctx, guestPath) ?: return
        save(ctx, all(ctx).filterNot { it.guestPath == guestPath } + p.copy(lastAgent = agent))
    }

    /** Quita el proyecto de la lista (no borra la carpeta del dispositivo). */
    fun remove(ctx: Context, guestPath: String) {
        save(ctx, all(ctx).filterNot { it.guestPath == guestPath })
    }

    private fun save(ctx: Context, projects: List<Project>) {
        val arr = JSONArray()
        projects.sortedByDescending { it.lastUsed }.forEach { p ->
            arr.put(
                JSONObject()
                    .put("name", p.name)
                    .put("path", p.guestPath)
                    .put("lastUsed", p.lastUsed)
                    .put("lastAgent", p.lastAgent),
            )
        }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    /** Tiempo relativo legible ("hace 2 h", "ayer", …) para las tarjetas. */
    fun relativeTime(epochMs: Long): String {
        if (epochMs <= 0) return ""
        val diff = System.currentTimeMillis() - epochMs
        val min = diff / 60_000
        val hours = diff / 3_600_000
        val days = diff / 86_400_000
        return when {
            min < 1 -> "ahora"
            min < 60 -> "hace ${min} min"
            hours < 24 -> "hace ${hours} h"
            days == 1L -> "ayer"
            days < 7 -> "hace ${days} días"
            else -> "hace ${days / 7} sem"
        }
    }
}
