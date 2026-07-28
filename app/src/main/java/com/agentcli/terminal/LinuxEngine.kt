package com.agentcli.terminal

import android.content.Context
import android.system.Os
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

/**
 * Motor Linux embebido: extrae proot + rootfs Debian desde los assets y ejecuta
 * comandos dentro del guest vía proot. Sustituye por completo a Termux.
 *
 * Distribución (todo en filesDir, ejecutable gracias a targetSdk 28):
 *   filesDir/engine/  → proot, loader, loader32, libtalloc.so.2, libandroid-shmem.so
 *   filesDir/rootfs/  → Debian bookworm-slim (glibc) extraído
 *
 * Ver docs/DESIGN-embedded-linux.md y docs/DESIGN-M4.md.
 */
object LinuxEngine {

    private const val TAG = "LinuxEngine"

    // engine.tar = tar plano; debian-rootfs.tarball = tar.gz (extensión no .gz
    // para que aapt no lo auto-descomprima). extractTar detecta gzip por magia.
    private const val ASSET_ENGINE = "engine.tar"
    private const val ASSET_ROOTFS = "debian-rootfs.tarball"

    // Se sube al cambiar el rootfs/base/engine para forzar re-provisión.
    // v7: pre-cocinado (node22 + ttyd + claude/codex/gemini) + tmux (persistencia).
    private const val ROOTFS_VERSION = "7-tmux"

    // ttyd (estático) se empaqueta en el engine y se copia aquí dentro del guest.
    private const val TTYD_GUEST_PATH = "usr/local/bin/ttyd"

    // Node oficial (arm64, glibc) instalado en /usr/local — bookworm trae 18 y
    // los CLIs exigen >= 22.
    private const val NODE_VERSION = "v22.23.1"

    fun engineDir(ctx: Context) = File(ctx.filesDir, "engine")
    fun rootfsDir(ctx: Context) = File(ctx.filesDir, "rootfs")
    private fun markerFile(ctx: Context) = File(ctx.filesDir, ".provisioned")

    /**
     * Ruta REAL en Android de una ruta vista desde el guest. Permite a la app
     * leer/escribir los archivos del proyecto sin pasar por proot (adjuntar
     * imágenes, explorador de archivos).
     *
     *  - `/sdcard/...` → `/storage/emulated/0/...` (proot lo monta con -b, ver
     *    [prootPrefix]; el acceso por ruta real funciona gracias a targetSdk 28 +
     *    requestLegacyExternalStorage).
     *  - cualquier otra → dentro del rootfs extraído en filesDir.
     */
    fun hostFileFor(ctx: Context, guestPath: String): File =
        if (guestPath == "/sdcard" || guestPath.startsWith("/sdcard/")) {
            File("/storage/emulated/0" + guestPath.removePrefix("/sdcard"))
        } else {
            File(rootfsDir(ctx), guestPath.trimStart('/'))
        }

    /** Versión del rootfs empaquetado (se muestra en el gestor del entorno). */
    fun rootfsVersion(): String = ROOTFS_VERSION

    /** ¿Ya se extrajo el entorno base con la versión actual del rootfs? */
    fun isProvisioned(ctx: Context): Boolean =
        markerFile(ctx).takeIf { it.exists() }?.readText()?.trim() == ROOTFS_VERSION

    // -------------------------------------------------------------------------
    // Extracción
    // -------------------------------------------------------------------------

    /**
     * Extrae engine + rootfs desde los assets a filesDir. Idempotente: si ya
     * está provisionado y [force] es false, no hace nada.
     */
    @Synchronized
    fun provision(ctx: Context, force: Boolean = false) {
        if (isProvisioned(ctx) && !force) {
            Log.i(TAG, "Ya provisionado; omito extracción.")
            return
        }
        val engine = engineDir(ctx)
        val rootfs = rootfsDir(ctx)

        // Preserva /root (credenciales de los CLIs: ~/.claude, ~/.codex, etc.)
        // entre re-provisiones, para no obligar a re-loguear al actualizar.
        val homeBackup = File(ctx.filesDir, "root-backup")
        val oldHome = File(rootfs, "root")
        homeBackup.deleteRecursively()
        if (oldHome.isDirectory) {
            runCatching { oldHome.copyRecursively(homeBackup, overwrite = true) }
                .onFailure { Log.w(TAG, "backup de /root falló: ${it.message}") }
        }

        engine.deleteRecursively(); engine.mkdirs()
        rootfs.deleteRecursively(); rootfs.mkdirs()

        Log.i(TAG, "Extrayendo $ASSET_ENGINE ...")
        extractTar(ctx, ASSET_ENGINE, engine)
        // Todos los binarios/libs del motor: ejecutables.
        engine.listFiles()?.forEach { Os.chmod(it.absolutePath, 0b111_101_101) /* 0755 */ }

        Log.i(TAG, "Extrayendo $ASSET_ROOTFS ...")
        extractTar(ctx, ASSET_ROOTFS, rootfs)

        // Restaura las credenciales preservadas sobre el /root recién extraído.
        if (homeBackup.isDirectory) {
            runCatching { homeBackup.copyRecursively(File(rootfs, "root"), overwrite = true) }
                .onFailure { Log.w(TAG, "restore de /root falló: ${it.message}") }
            homeBackup.deleteRecursively()
        }

        ensureResolvConf(ctx)
        ensureTtyd(ctx)

        markerFile(ctx).writeText(ROOTFS_VERSION)
        Log.i(TAG, "Provisión completada ($ROOTFS_VERSION).")
    }

    /** Copia el ttyd estático del engine dentro del rootfs del guest. Idempotente. */
    fun ensureTtyd(ctx: Context) {
        val src = File(engineDir(ctx), "ttyd")
        val dst = File(rootfsDir(ctx), TTYD_GUEST_PATH)
        if (!src.exists()) { Log.w(TAG, "engine/ttyd no existe"); return }
        dst.parentFile?.mkdirs()
        src.copyTo(dst, overwrite = true)
        Os.chmod(dst.absolutePath, 0b111_101_101) // 0755
    }

    /** (Re)escribe /etc/resolv.conf en el guest. Idempotente; se puede llamar
     *  siempre antes de operaciones de red. */
    fun ensureResolvConf(ctx: Context) {
        val etc = File(rootfsDir(ctx), "etc").apply { mkdirs() }
        File(etc, "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
    }

    /**
     * Escribe el script de sesión por agente dentro del guest. ttyd lo ejecuta
     * como comando de la terminal: lanza el CLI del agente; si no está instalado,
     * ofrece instalarlo con npm; y siempre cae a bash si el CLI termina.
     * Idempotente (se reescribe en cada arranque de sesión).
     */
    fun ensureSessionScript(ctx: Context) {
        // xdg-open falso: los CLIs lo invocan para "abrir el navegador". Escribe
        // la URL en /tmp/.agent-open-url; la app la observa (FileObserver) y
        // dispara el Intent ACTION_VIEW en Android.
        val xdgOpen = File(rootfsDir(ctx), "usr/local/bin/xdg-open")
        xdgOpen.parentFile?.mkdirs()
        xdgOpen.writeText(
            """
            #!/bin/sh
            # Generado por la app: reenvía la URL al navegador de Android.
            echo "${'$'}1" >> /tmp/.agent-open-url
            exit 0
            """.trimIndent() + "\n",
        )
        Os.chmod(xdgOpen.absolutePath, 0b111_101_101) // 0755

        // Config de tmux: ratón/scroll táctil, sin lag de Esc y barra de estado con
        // la lista de ventanas (E1). Con `mouse on`, TOCAR el nombre de una ventana
        // en la barra cambia a ella: es el selector de ventanas, gratis y nativo.
        // Los colores son la paleta de la app (Catppuccin, ver res/values/colors.xml).
        val tmuxConf = File(rootfsDir(ctx), "root/.tmux.conf")
        tmuxConf.parentFile?.mkdirs()
        tmuxConf.writeText(
            """
            set -g mouse on
            set -sg escape-time 0
            set -g history-limit 10000
            set -g default-shell /bin/bash

            # --- Barra de estado = selector de ventanas (E1) ---
            set -g status on
            set -g status-position bottom
            set -g status-justify left
            set -g status-style "bg=#181825,fg=#7F849C"
            set -g status-left ""
            set -g status-right ""
            setw -g window-status-format " #I:#W "
            setw -g window-status-current-format "#[bg=#CBA6F7,fg=#1E1E2E,bold] #I:#W "

            # Ventana nueva en la carpeta actual; cerrar sin diálogo de confirmación
            # (los chips de la app mandan prefijo + c / W).
            bind c new-window -c "#{pane_current_path}"
            bind W kill-window
            set -g renumber-windows on
            """.trimIndent() + "\n" + idleNotifyConf(ctx),
        )

        // El bit +x de tmux se pierde al empaquetar desde Windows; lo aseguramos.
        File(rootfsDir(ctx), "usr/bin/tmux").takeIf { it.exists() }
            ?.let { runCatching { Os.chmod(it.absolutePath, 0b111_101_101) } }

        val script = File(rootfsDir(ctx), "usr/local/bin/agent-session.sh")
        script.parentFile?.mkdirs()
        script.writeText(
            """
            #!/bin/bash
            # Generado por la app. Uso: agent-session.sh <agente> [workdir] [sesion]
            #
            # Persistencia (F5): la sesión corre DENTRO de tmux. Cada conexión de
            # ttyd (recarga del WebView, cambio de pestaña, volver del navegador)
            # se re-engancha a la misma sesión tmux en vez de crear un CLI nuevo.
            #
            # Modo Proyectos (F4): el 3er arg es el nombre de la sesión tmux, que
            # incluye un hash del proyecto (<agente>_<hash>) para que dos proyectos
            # NO compartan la misma sesión. Por defecto = el nombre del agente.
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export HOME=/root TERM=xterm-256color TMPDIR=/tmp
            # Locale UTF-8 (si no, tildes/ñ y multibyte se rompen). C.UTF-8 viene
            # de serie en glibc.
            export LANG=C.UTF-8 LC_ALL=C.UTF-8
            # Los CLIs abren el navegador vía ${'$'}BROWSER / xdg-open.
            export BROWSER=/usr/local/bin/xdg-open
            AGENT="${'$'}{1:-shell}"
            WORKDIR="${'$'}{2:-/root}"
            SESSION="${'$'}{3:-${'$'}AGENT}"
            [ -d "${'$'}WORKDIR" ] || WORKDIR=/root
            cd "${'$'}WORKDIR" 2>/dev/null || cd /root
            case "${'$'}AGENT" in
              claude) BIN=claude; PKG="@anthropic-ai/claude-code" ;;
              codex)  BIN=codex;  PKG="@openai/codex" ;;
              gemini) BIN=gemini; PKG="@google/gemini-cli" ;;
              *) BIN=""; PKG="" ;;
            esac

            # Sin tmux (no debería pasar con el rootfs actual): comportamiento simple.
            if ! command -v tmux >/dev/null 2>&1; then
              if [ -n "${'$'}BIN" ] && command -v "${'$'}BIN" >/dev/null 2>&1; then exec "${'$'}BIN"; fi
              exec bash -l
            fi

            # IMPORTANTE (F4): cada CLI usa su PROPIO servidor tmux vía -L "${'$'}SESSION".
            # Cada ttyd corre en una instancia de proot distinta (ptrace); si todos
            # compartieran un único servidor tmux, los clientes de otras instancias
            # de proot no se enganchan bien al servidor que trazó la primera → ttyd
            # muestra "Reconnect". Con un socket por CLI, cada servidor se arranca y
            # se usa dentro de su misma instancia de proot.

            # Recarga la config en los servidores tmux que YA estaban vivos (si no,
            # los cambios de .tmux.conf solo los verían las sesiones creadas desde
            # cero). Se ejecuta en ESTA instancia de proot, así que es seguro.
            tmux -L "${'$'}SESSION" source-file /root/.tmux.conf 2>/dev/null || true

            if [ -z "${'$'}BIN" ]; then
              exec tmux -L "${'$'}SESSION" new-session -A -s "${'$'}SESSION" -c "${'$'}WORKDIR"
            fi
            if command -v "${'$'}BIN" >/dev/null 2>&1; then
              # -A: engancha si la sesión ya existe; si no, la crea en WORKDIR
              # ejecutando el CLI. Si el CLI termina (sale/cierra), dentro de la
              # sesión caemos a bash. Y si el PROPIO tmux falla o la sesión acaba,
              # el script cae a un shell en la carpeta en vez de morir (así ttyd
              # nunca queda en el "Reconnect" muerto). Sin 'exec' a propósito.
              tmux -L "${'$'}SESSION" new-session -A -s "${'$'}SESSION" -c "${'$'}WORKDIR" "${'$'}BIN; exec bash -l"
              echo "[sesión tmux '${'$'}SESSION' terminó — shell de respaldo]"
              cd "${'$'}WORKDIR" 2>/dev/null || cd /root
              exec bash -l
            fi
            echo "== ${'$'}BIN no está instalado =="
            printf '¿Instalar %s ahora con npm? [S/n] ' "${'$'}PKG"
            read -r R
            if [ "${'$'}R" = "n" ] || [ "${'$'}R" = "N" ]; then exec bash -l; fi
            npm install -g "${'$'}PKG" && exec "${'$'}0" "${'$'}AGENT" "${'$'}WORKDIR" "${'$'}SESSION"
            echo "[La instalación falló — shell de respaldo]"
            exec bash -l
            """.trimIndent() + "\n",
        )
        Os.chmod(script.absolutePath, 0b111_101_101) // 0755
    }

    // -------------------------------------------------------------------------
    // Avisos del agente (B1)
    // -------------------------------------------------------------------------

    /** Script del guest que avisa a la app; ver [ensureAgentHooks]. */
    private const val NOTIFY_BIN = "/usr/local/bin/agent-notify"

    /** Fichero cola de avisos dentro del guest (lo vigila LinuxService). */
    const val NOTIFY_FILE = ".agent-notify"

    /** Bandera que pone MainActivity al irse a segundo plano: "sí, avísame". */
    const val NOTIFY_ARMED_FILE = ".agent-notify-armed"

    private const val PREFS = "agentterminal"
    private const val KEY_NOTIFY_IDLE = "notify_idle"

    /** ¿Está activo el detector genérico por silencio de tmux? (D2 lo conmuta.) */
    fun isIdleNotifyEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_NOTIFY_IDLE, true)

    fun setIdleNotifyEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NOTIFY_IDLE, enabled).apply()
    }

    /**
     * Trozo de .tmux.conf con el detector genérico de "el agente se quedó quieto".
     * Es la capa que cubre a Gemini y a cualquier shell, porque solo Claude Code y
     * Codex tienen hooks propios. `agent-notify` exige la bandera de "app en
     * segundo plano", así que en primer plano no dispara nunca.
     */
    private fun idleNotifyConf(ctx: Context): String =
        if (!isIdleNotifyEnabled(ctx)) "" else """

        # --- Aviso por silencio (B1), capa genérica ---
        setw -g monitor-silence 25
        set -g silence-action any
        set -g visual-silence off
        set-hook -g alert-silence 'run-shell "$NOTIFY_BIN #{session_name} idle"'
        """.trimIndent() + "\n"

    /**
     * Instala en el guest el avisador y lo engancha a los CLIs que tienen hooks
     * propios (Claude Code y Codex). Idempotente y NO destructivo: si el usuario
     * ya tiene configuración propia, se respeta y solo se añade lo que falta.
     */
    fun ensureAgentHooks(ctx: Context) {
        val rootfs = rootfsDir(ctx)

        // 1) El avisador: escribe una línea en la cola que vigila LinuxService.
        val notify = File(rootfs, NOTIFY_BIN.trimStart('/'))
        notify.parentFile?.mkdirs()
        notify.writeText(
            """
            #!/bin/sh
            # Generado por la app (B1). Uso: agent-notify <sesion|agente> <tipo> [msg]
            # Solo avisa si la app está en segundo plano; si no, sale en silencio.
            [ -f /tmp/$NOTIFY_ARMED_FILE ] || exit 0
            SESSION="${'$'}{1:-agent}"
            KIND="${'$'}{2:-idle}"
            MSG="${'$'}{3:-}"
            NOW=${'$'}(date +%s)
            STAMP="/tmp/.agent-notify-last-${'$'}(echo "${'$'}SESSION" | tr -c 'A-Za-z0-9_' '_')"
            LAST=${'$'}(cat "${'$'}STAMP" 2>/dev/null)
            LAST=${'$'}{LAST:-0}
            # Antirrebote: un aviso como mucho cada 20 s por sesión.
            if [ ${'$'}((NOW - LAST)) -lt 20 ]; then exit 0; fi
            echo "${'$'}NOW" > "${'$'}STAMP"
            printf '%s|%s|%s|%s\n' "${'$'}SESSION" "${'$'}KIND" "${'$'}NOW" "${'$'}MSG" >> /tmp/$NOTIFY_FILE
            exit 0
            """.trimIndent() + "\n",
        )
        Os.chmod(notify.absolutePath, 0b111_101_101) // 0755

        // 2) Claude Code: hooks Notification (pide permiso) y Stop (terminó).
        runCatching { ensureClaudeHooks(rootfs) }
            .onFailure { Log.w(TAG, "hooks de Claude: ${it.message}") }

        // 3) Codex: clave `notify` de su config.toml.
        runCatching { ensureCodexNotify(rootfs) }
            .onFailure { Log.w(TAG, "notify de Codex: ${it.message}") }

        // Gemini CLI no expone hooks → lo cubre el detector por silencio de tmux.
    }

    /** Fusiona nuestros hooks en /root/.claude/settings.json sin pisar los suyos. */
    private fun ensureClaudeHooks(rootfs: File) {
        val settings = File(rootfs, "root/.claude/settings.json")
        val current = if (settings.exists()) settings.readText() else ""
        if (current.contains("agent-notify")) return // ya enganchado

        val json = runCatching { JSONObject(current) }.getOrElse { JSONObject() }
        val hooks = json.optJSONObject("hooks") ?: JSONObject()
        mapOf("Notification" to "notification", "Stop" to "stop").forEach { (event, kind) ->
            val entries = hooks.optJSONArray(event) ?: JSONArray()
            entries.put(
                JSONObject().put(
                    "hooks",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "command")
                            .put("command", "$NOTIFY_BIN claude $kind"),
                    ),
                ),
            )
            hooks.put(event, entries)
        }
        json.put("hooks", hooks)
        settings.parentFile?.mkdirs()
        settings.writeText(json.toString(2) + "\n")
    }

    /** Añade la clave `notify` al config.toml de Codex si no está ya. */
    private fun ensureCodexNotify(rootfs: File) {
        val cfg = File(rootfs, "root/.codex/config.toml")
        val current = if (cfg.exists()) cfg.readText() else ""
        if (current.contains("agent-notify")) return
        cfg.parentFile?.mkdirs()
        val prefix = if (current.isEmpty() || current.endsWith("\n")) "" else "\n"
        cfg.appendText(
            prefix + "\n# Añadido por Agents CLI: avisa a la app cuando Codex termina.\n" +
                "notify = [\"$NOTIFY_BIN\", \"codex\", \"notify\"]\n",
        )
    }

    /**
     * Extrae un .tar.gz de assets a [destDir]. Maneja directorios, ficheros,
     * symlinks y hardlinks; ignora nodos de dispositivo (proot enlaza /dev).
     */
    private fun extractTar(ctx: Context, assetName: String, destDir: File) {
        ctx.assets.open(assetName).use { rawAsset ->
            // Detecta gzip por los magic bytes (0x1f 0x8b) para soportar tanto
            // tar plano (engine.tar) como tar.gz (debian-rootfs.tarball).
            val buffered = BufferedInputStream(rawAsset)
            val raw: InputStream = if (isGzip(buffered)) GzipCompressorInputStream(buffered) else buffered
            run {
                TarArchiveInputStream(raw).use { tar ->
                    while (true) {
                        val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                        val outFile = safeChild(destDir, entry.name)
                        when {
                            entry.isDirectory -> outFile.mkdirs()

                            entry.isSymbolicLink -> {
                                outFile.parentFile?.mkdirs()
                                outFile.delete()
                                runCatching { Os.symlink(entry.linkName, outFile.absolutePath) }
                                    .onFailure { Log.w(TAG, "symlink ${outFile.name}: ${it.message}") }
                            }

                            entry.isLink -> { // hardlink
                                outFile.parentFile?.mkdirs()
                                outFile.delete()
                                val target = safeChild(destDir, entry.linkName)
                                // SELinux suele denegar link() en datos de la app:
                                // fallback a copiar el contenido (el target ya se
                                // extrajo antes, el tar va en orden).
                                val linked = runCatching {
                                    Os.link(target.absolutePath, outFile.absolutePath)
                                }.isSuccess
                                if (!linked && target.exists()) {
                                    target.copyTo(outFile, overwrite = true)
                                    runCatching { Os.chmod(outFile.absolutePath, entry.mode and 0x1FF) }
                                } else if (!linked) {
                                    Log.w(TAG, "hardlink imposible y target ausente: ${entry.name} -> ${entry.linkName}")
                                }
                            }

                            entry.isFile -> {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { tar.copyTo(it) }
                                // Conserva bit de ejecución del tar.
                                val mode = entry.mode and 0x1FF
                                runCatching { Os.chmod(outFile.absolutePath, mode) }
                            }
                            // Nodos char/block/fifo: se ignoran a propósito.
                        }
                    }
                }
            }
        }
    }

    /** Mira los 2 primeros bytes (magic gzip) sin consumir el stream. */
    private fun isGzip(input: BufferedInputStream): Boolean {
        input.mark(2)
        val b0 = input.read()
        val b1 = input.read()
        input.reset()
        return b0 == 0x1f && b1 == 0x8b
    }

    /** Evita path traversal (entradas con ".."). */
    private fun safeChild(base: File, name: String): File {
        val child = File(base, name).canonicalFile
        require(child.path.startsWith(base.canonicalFile.path)) { "ruta insegura en tar: $name" }
        return child
    }

    // -------------------------------------------------------------------------
    // Ejecución vía proot
    // -------------------------------------------------------------------------

    data class Result(val exitCode: Int, val output: String)

    /** Mata una sesión tmux por su nombre completo (p.ej. "claude_1a2b3c") para
     *  que se recree. En Modo Proyectos el nombre incluye el hash del proyecto.
     *  Silencioso si no existe. */
    fun killTmuxSession(ctx: Context, session: String) {
        // Cada CLI tiene su propio servidor tmux (socket -L <session>); matamos ese.
        runCatching { runInGuest(ctx, "tmux -L '$session' kill-server 2>/dev/null || true", 15_000) }
    }

    private const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /** Prefijo de argumentos de proot (sin el comando final del guest). */
    private fun prootPrefix(ctx: Context): List<String> {
        val engine = engineDir(ctx)
        val args = mutableListOf(
            File(engine, "proot").absolutePath,
            "-r", rootfsDir(ctx).absolutePath,
            "-0",                 // usuario root fingido
            "-w", "/root",        // working dir dentro del guest
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
        )
        // Almacenamiento compartido del móvil, visible como /sdcard en el guest,
        // para que los CLIs trabajen sobre los archivos del usuario.
        val sdcard = File("/storage/emulated/0")
        if (sdcard.exists()) args += listOf("-b", "/storage/emulated/0:/sdcard")
        // SELinux de Android prohíbe hard links en datos de la app; dpkg los
        // usa para sus backups (status-old). proot los convierte en symlinks.
        args += listOf("--link2symlink", "--kill-on-exit")
        return args
    }

    /** Aplica el entorno necesario para proot + guest a un ProcessBuilder. */
    fun applyGuestEnv(ctx: Context, pb: ProcessBuilder) {
        val engine = engineDir(ctx)
        pb.environment().apply {
            put("PROOT_LOADER", File(engine, "loader").absolutePath)
            put("PROOT_LOADER_32", File(engine, "loader32").absolutePath)
            put("PROOT_TMP_DIR", ctx.cacheDir.absolutePath)
            put("LD_LIBRARY_PATH", engine.absolutePath)
            put("HOME", "/root")
            put("TERM", "xterm-256color")
            put("PATH", GUEST_PATH)
            // Sin locale UTF-8 el guest queda en POSIX/C → las tildes, ñ y demás
            // caracteres multibyte se rompen (salen como "_"/basura). C.UTF-8 viene
            // de serie en glibc (no requiere locale-gen).
            put("LANG", "C.UTF-8")
            put("LC_ALL", "C.UTF-8")
            // Android exporta TMPDIR=<cache de la app>, ruta que NO existe dentro
            // del guest → mktemp de los postinst (ca-certificates) revienta.
            put("TMPDIR", "/tmp")
        }
    }

    /**
     * Construye la línea de comandos completa (proot + guest) para ejecutar
     * [guestCmd] dentro del guest. [guestCmd] es la lista tal cual (p.ej.
     * ["ttyd", "-p", "7681", ...]).
     */
    fun buildGuestCommand(ctx: Context, guestCmd: List<String>): List<String> =
        prootPrefix(ctx) + guestCmd

    /**
     * Ejecuta [script] con /bin/sh -lc dentro del guest y devuelve stdout+stderr.
     * Para tareas puntuales (self-test). Sesiones interactivas → ttyd.
     */
    fun runInGuest(ctx: Context, script: String, timeoutMs: Long = 60_000): Result {
        val cmd = buildGuestCommand(ctx, listOf("/bin/sh", "-lc", script))
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        applyGuestEnv(ctx, pb)
        return try {
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            val finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                Result(-1, out + "\n[timeout tras ${timeoutMs}ms]")
            } else {
                Result(proc.exitValue(), out)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al ejecutar proot", e)
            Result(-1, "ERROR al lanzar proot: ${e.message}\ncmd=$cmd")
        }
    }

    /**
     * Ejecuta [script] en el guest transmitiendo cada línea a [onLine] en vivo
     * (para instalaciones largas con progreso). Bloquea hasta terminar.
     */
    fun runInGuestStreaming(
        ctx: Context,
        script: String,
        timeoutMs: Long = 15 * 60_000,
        onLine: (String) -> Unit,
    ): Int {
        val cmd = buildGuestCommand(ctx, listOf("/bin/sh", "-lc", script))
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        applyGuestEnv(ctx, pb)
        return try {
            val proc = pb.start()
            val reader = proc.inputStream.bufferedReader()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val line = reader.readLine() ?: break
                Log.i("guest", line)   // espejo a logcat para depurar
                onLine(line)
                if (System.currentTimeMillis() > deadline) {
                    proc.destroyForcibly(); onLine("[timeout]"); break
                }
            }
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            runCatching { proc.exitValue() }.getOrDefault(-1)
        } catch (e: Exception) {
            onLine("ERROR: ${e.message}")
            -1
        }
    }

    // -------------------------------------------------------------------------
    // Stack de software (Node, ttyd) — M2 (Debian/apt)
    // -------------------------------------------------------------------------

    /** ¿Están node 22, ttyd y los certificados TLS listos en el guest? */
    fun isStackInstalled(ctx: Context): Boolean {
        val rootfs = rootfsDir(ctx)
        // Node 22 oficial en /usr/local (trae npm incluido). El node 18 de apt
        // no basta: los CLIs exigen >= 22.
        val hasNode22 = File(rootfs, "usr/local/bin/node").exists()
        // Generado por update-ca-certificates (postinst): buen indicador de que
        // ca-certificates quedó bien configurado (necesario para npm https).
        val hasCerts = File(rootfs, "etc/ssl/certs/ca-certificates.crt").exists()
        return hasNode22 && hasCerts && File(rootfs, TTYD_GUEST_PATH).exists()
    }

    /**
     * Instala nodejs, npm y ttyd (y utilidades base) en el guest vía apt.
     * Requiere red (que funciona desde el proceso real de la app). Transmite
     * progreso por [onLine]. Devuelve true si al final node y ttyd existen.
     */
    fun installStack(ctx: Context, onLine: (String) -> Unit): Boolean {
        if (!isProvisioned(ctx)) onLine("Extrayendo el entorno (solo la primera vez)…")
        provision(ctx)
        ensureResolvConf(ctx)

        // Rootfs pre-cocinado: si ya trae node 22 + ttyd + certificados (lo
        // normal con el asset actual), no hay nada que descargar.
        if (isStackInstalled(ctx)) {
            onLine("✓ Entorno listo (todo incluido en la app).")
            return true
        }

        // --- Fallback (rootfs sin pre-cocinar): instalar por red como antes ---
        // APT::Sandbox::User=root evita el fallo de "drop privileges" bajo proot.
        val apt = "DEBIAN_FRONTEND=noninteractive apt-get -o APT::Sandbox::User=root"
        onLine("Reparando estado de dpkg si hace falta…")
        runInGuestStreaming(ctx, "dpkg --configure -a 2>&1 || true", onLine = onLine)
        onLine("Actualizando índices (apt-get update)…")
        runInGuestStreaming(ctx, "$apt update 2>&1", onLine = onLine)
        onLine("")
        onLine("Instalando nodejs, npm, git, ca-certificates, curl… (puede tardar)")
        // ttyd NO está en los repos de Debian bookworm → lo aportamos como binario
        // estático desde el engine (ensureTtyd), no por apt.
        runInGuestStreaming(
            ctx,
            "$apt install -y --no-install-recommends nodejs npm git ca-certificates curl 2>&1",
            timeoutMs = 20 * 60_000,
            onLine = onLine,
        )
        // Segunda pasada: re-configura cualquier paquete que quedara a medias
        // (p.ej. si un postinst falló en un intento anterior).
        onLine("Verificando configuración de paquetes…")
        runInGuestStreaming(ctx, "dpkg --configure -a 2>&1 | tail -20 || true", onLine = onLine)

        // Node 22 oficial en /usr/local (tapa al node 18 de bookworm por PATH):
        // los CLIs de agentes exigen node >= 22 (Claude Code: >=22.0.0).
        if (!File(rootfsDir(ctx), "usr/local/bin/node").exists()) {
            onLine("")
            onLine("Instalando Node $NODE_VERSION (los CLIs exigen node >= 22)…")
            runInGuestStreaming(
                ctx,
                "cd /tmp && curl -fsSL -o node.tar.gz " +
                    "https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-linux-arm64.tar.gz " +
                    "&& tar -xzf node.tar.gz -C /usr/local --strip-components=1 " +
                    "&& rm -f node.tar.gz && echo node=$(/usr/local/bin/node -v) 2>&1",
                timeoutMs = 10 * 60_000,
                onLine = onLine,
            )
        }
        // Debian instala /usr/bin/nodejs; los CLIs esperan `node`. Symlink solo si
        // nodejs existe de verdad (evita dejar un symlink colgante).
        runInGuestStreaming(
            ctx,
            "if [ -x /usr/bin/nodejs ] && [ ! -e /usr/bin/node ]; then ln -sf /usr/bin/nodejs /usr/bin/node; fi; " +
                "echo node=$(command -v node) nodejs=$(command -v nodejs) ttyd=$(command -v ttyd)",
            onLine = onLine,
        )
        ensureTtyd(ctx)
        val ok = isStackInstalled(ctx)
        onLine("")
        onLine(if (ok) "✓ Stack instalado." else "✗ Falta node o ttyd tras la instalación.")
        return ok
    }

    /**
     * Self-test de M1: provisiona si hace falta y ejecuta comandos de diagnóstico
     * dentro del guest. Devuelve un informe legible para mostrar en pantalla.
     */
    fun selfTest(ctx: Context): String {
        val sb = StringBuilder()
        sb.appendLine("== Self-test motor Linux ==")
        try {
            provision(ctx)
            ensureResolvConf(ctx)
            sb.appendLine("engine: ${engineDir(ctx)}")
            sb.appendLine("rootfs: ${rootfsDir(ctx)}")
            sb.appendLine()
            val r = runInGuest(
                ctx,
                "echo '--- uname ---'; uname -a; " +
                    "echo '--- alpine ---'; cat /etc/alpine-release 2>/dev/null; " +
                    "echo '--- id ---'; id; " +
                    "echo EXIT=\$?",
            )
            sb.appendLine("exitCode = ${r.exitCode}")
            sb.appendLine(r.output)

            // Sonda de RED desde el proceso real de la app (clave para M2/M4).
            sb.appendLine("--- NET PROBE (proceso real de la app) ---")
            val net = runInGuest(
                ctx,
                "echo '[dns]'; nslookup example.com 2>&1 | tail -3; " +
                    "echo '[tcp-ip https 1.1.1.1]'; " +
                    "wget -T 12 -q -O /dev/null https://1.1.1.1/ && echo TCP_OK || echo TCP_FAIL; " +
                    "echo '[getent hosts]'; getent hosts example.com 2>&1 | head -2",
                timeoutMs = 40_000,
            )
            sb.appendLine("net.exitCode = ${net.exitCode}")
            sb.appendLine(net.output)
        } catch (e: Exception) {
            sb.appendLine("EXCEPCIÓN: ${e.message}")
            sb.appendLine(Log.getStackTraceString(e))
        }
        return sb.toString()
    }
}
