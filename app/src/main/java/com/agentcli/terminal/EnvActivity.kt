package com.agentcli.terminal

import android.content.Intent
import android.os.Bundle
import android.os.StatFs
import androidx.appcompat.app.AppCompatActivity
import com.agentcli.terminal.databinding.ActivityEnvBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Gestor del entorno Linux (D2).
 *
 * Deja hacer desde la app lo que antes obligaba a pelearse con la terminal:
 * ver qué hay instalado y cuánto ocupa, actualizar los CLIs, instalar paquetes
 * extra, limpiar cachés y re-provisionar el rootfs. Todo se ejecuta con
 * [LinuxEngine.runInGuestStreaming] y se pinta en un [LogConsole].
 */
class EnvActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEnvBinding
    private lateinit var console: LogConsole

    /** Evita lanzar dos tareas del guest a la vez (apt/npm no son reentrantes). */
    private var busy = false

    /** Paquetes de apt que se ofrecen como "extras". */
    private val extras = listOf(
        "Python 3 (+ pip)" to "python3 python3-pip",
        "Go" to "golang-go",
        "ripgrep (búsqueda rápida)" to "ripgrep",
        "jq (JSON)" to "jq",
        "unzip / zip" to "unzip zip",
        "vim" to "vim",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_AgentTerminal)
        super.onCreate(savedInstanceState)
        binding = ActivityEnvBinding.inflate(layoutInflater)
        setContentView(binding.root)
        console = LogConsole(this, binding.envLog)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnUpdateClis.setOnClickListener { updateClis() }
        binding.btnInstallExtras.setOnClickListener { askExtras() }
        binding.btnClean.setOnClickListener { clean() }
        binding.btnReprovision.setOnClickListener { confirmReprovision() }

        binding.switchIdleNotify.isChecked = LinuxEngine.isIdleNotifyEnabled(this)
        binding.switchIdleNotify.setOnCheckedChangeListener { _, checked ->
            LinuxEngine.setIdleNotifyEnabled(this, checked)
            // El .tmux.conf se regenera con (o sin) el hook de silencio; los
            // servidores tmux vivos lo recogen al reconectar (source-file).
            LinuxEngine.ensureSessionScript(this)
        }

        loadStatus()
    }

    // --- Estado ---------------------------------------------------------------

    /** Lee versiones y ocupación en segundo plano (el walk del rootfs tarda). */
    private fun loadStatus() {
        if (!LinuxEngine.isProvisioned(this)) {
            binding.envStatus.text = "El entorno todavía no está extraído.\n" +
                "Abre un proyecto para que la app lo prepare."
            return
        }
        thread {
            val versions = LinuxEngine.runInGuest(
                this,
                "echo \"node=\$(node -v 2>/dev/null)\"; npm ls -g --depth=0 2>/dev/null",
                timeoutMs = 60_000,
            ).output

            val rootfs = LinuxEngine.rootfsDir(this)
            val used = runCatching {
                rootfs.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }.getOrDefault(0L)
            val free = runCatching {
                StatFs(filesDir.absolutePath).availableBytes
            }.getOrDefault(0L)

            val text = buildString {
                appendLine("rootfs      ${LinuxEngine.rootfsVersion()}")
                appendLine("node        ${find(versions, Regex("node=(v[\\d.]+)"))}")
                appendLine("claude      ${find(versions, Regex("@anthropic-ai/claude-code@(\\S+)"))}")
                appendLine("codex       ${find(versions, Regex("@openai/codex@(\\S+)"))}")
                appendLine("gemini      ${find(versions, Regex("@google/gemini-cli@(\\S+)"))}")
                appendLine("ocupa       ${humanBytes(used)}")
                append("libre       ${humanBytes(free)}")
            }
            runOnUiThread { binding.envStatus.text = text }
        }
    }

    private fun find(haystack: String, regex: Regex): String =
        regex.find(haystack)?.groupValues?.get(1) ?: "—"

    private fun humanBytes(bytes: Long): String {
        if (bytes <= 0) return "—"
        val units = listOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024; unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }

    // --- Acciones -------------------------------------------------------------

    private fun updateClis() = runGuestTask(
        "Actualizando los CLIs",
        "npm install -g @anthropic-ai/claude-code@latest @openai/codex@latest " +
            "@google/gemini-cli@latest 2>&1",
        needsNetwork = true,
    )

    private fun askExtras() {
        if (busy) { snack(getString(R.string.env_busy)); return }
        val checked = BooleanArray(extras.size)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.env_extras_title)
            .setMultiChoiceItems(
                extras.map { it.first }.toTypedArray(),
                checked,
            ) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Instalar") { _, _ ->
                val pkgs = extras.filterIndexed { i, _ -> checked[i] }
                    .joinToString(" ") { it.second }
                if (pkgs.isBlank()) return@setPositiveButton
                // APT::Sandbox::User=root es obligatorio bajo proot (si no, apt
                // falla al intentar bajar privilegios).
                val apt = "DEBIAN_FRONTEND=noninteractive apt-get -o APT::Sandbox::User=root"
                runGuestTask(
                    "Instalando $pkgs",
                    "$apt update 2>&1 && $apt install -y --no-install-recommends $pkgs 2>&1",
                    needsNetwork = true,
                    timeoutMs = 20 * 60_000,
                )
            }
            .show()
    }

    private fun clean() = runGuestTask(
        "Limpiando cachés",
        // Se respetan los sockets de tmux (/tmp/tmux-*): borrarlos dejaría las
        // sesiones de los CLIs huérfanas y sin poder re-engancharse.
        "apt-get clean 2>&1; rm -rf /var/lib/apt/lists/* 2>&1; " +
            "npm cache clean --force 2>&1; " +
            "find /tmp -mindepth 1 -maxdepth 1 ! -name 'tmux-*' -exec rm -rf {} + 2>&1; " +
            "echo 'cachés limpiadas'",
        timeoutMs = 5 * 60_000,
    )

    private fun confirmReprovision() {
        if (busy) { snack(getString(R.string.env_busy)); return }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.env_reprovision)
            .setMessage(R.string.env_reprovision_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Re-provisionar") { _, _ -> reprovision() }
            .show()
    }

    private fun reprovision() {
        busy = true
        setActionsEnabled(false)
        console.clear()
        console.append("== Re-provisionando el entorno ==")
        // Hay que parar los ttyd antes: si no, se re-extrae el rootfs bajo los pies
        // de procesos vivos.
        stopService(Intent(this, LinuxService::class.java))
        console.append("Sesiones detenidas. Extrayendo…")
        thread {
            val result = runCatching {
                LinuxEngine.provision(this, force = true)
                LinuxEngine.ensureSessionScript(this)
                LinuxEngine.ensureAgentHooks(this)
            }
            console.append(
                result.fold(
                    onSuccess = { "✓ Entorno re-provisionado (tus logins se conservaron)." },
                    onFailure = { "✗ Falló: ${it.message}" },
                ),
            )
            console.flush()
            runOnUiThread {
                busy = false
                setActionsEnabled(true)
                loadStatus()
            }
        }
    }

    /**
     * Ejecuta [script] en el guest volcando su salida al log. Bloquea el resto de
     * acciones mientras dura.
     */
    private fun runGuestTask(
        header: String,
        script: String,
        needsNetwork: Boolean = false,
        timeoutMs: Long = 15 * 60_000,
    ) {
        if (busy) { snack(getString(R.string.env_busy)); return }
        if (!LinuxEngine.isProvisioned(this)) {
            snack("El entorno aún no está extraído. Abre un proyecto primero.")
            return
        }
        busy = true
        setActionsEnabled(false)
        console.clear()
        console.append("== $header ==")
        if (needsNetwork) LinuxEngine.ensureResolvConf(this)
        thread {
            val code = LinuxEngine.runInGuestStreaming(this, script, timeoutMs) { console.append(it) }
            console.append(if (code == 0) "✓ Listo." else "✗ Terminó con código $code.")
            console.flush()
            runOnUiThread {
                busy = false
                setActionsEnabled(true)
                loadStatus()
            }
        }
    }

    private fun setActionsEnabled(enabled: Boolean) {
        binding.btnUpdateClis.isEnabled = enabled
        binding.btnInstallExtras.isEnabled = enabled
        binding.btnClean.isEnabled = enabled
        binding.btnReprovision.isEnabled = enabled
    }

    private fun snack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
}
