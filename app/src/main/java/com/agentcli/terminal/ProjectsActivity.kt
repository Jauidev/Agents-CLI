package com.agentcli.terminal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.agentcli.terminal.databinding.ActivityProjectsBinding
import com.google.android.material.snackbar.Snackbar

/**
 * Pantalla de entrada de la app (Modo Proyectos, F4).
 *
 * Muestra los proyectos recientes como tarjetas; al tocar uno se abre la
 * [MainActivity] (terminal) rooteada en la carpeta del proyecto. También permite
 * añadir un proyecto nuevo con el selector de carpetas del sistema (SAF).
 */
class ProjectsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProjectsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        // Del tema de splash al normal antes de inflar la UI.
        setTheme(R.style.Theme_AgentTerminal)
        super.onCreate(savedInstanceState)
        binding = ActivityProjectsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAddProject.setOnClickListener { onAddProjectClicked() }
        binding.btnEnv.setOnClickListener { startActivity(Intent(this, EnvActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        renderProjects()
    }

    // --- Lista de proyectos ---------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun renderProjects() {
        val projects = ProjectStore.all(this)
        val list = binding.projectList
        // Quita las tarjetas previas, conserva el hint de estado vacío (1er hijo).
        if (list.childCount > 1) list.removeViews(1, list.childCount - 1)

        binding.emptyHint.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
        projects.forEach { list.addView(buildCard(it)) }
    }

    /** Construye la tarjeta de un proyecto (nombre, ruta, tiempo, CLI, quitar). */
    private fun buildCard(p: Project): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@ProjectsActivity, R.drawable.card_bg)
            setPadding(dp(16), dp(14), dp(12), dp(14))
            setOnClickListener { openProject(p) }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) }
        card.layoutParams = lp

        // Columna de textos (peso 1).
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = p.name
            setTextColor(ContextCompat.getColor(this@ProjectsActivity, R.color.text))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            // Icono de carpeta vectorial tintado con el acento de la app.
            val d = ContextCompat.getDrawable(this@ProjectsActivity, R.drawable.ic_folder)?.mutate()
            d?.setTint(ContextCompat.getColor(this@ProjectsActivity, R.color.accent))
            d?.setBounds(0, 0, dp(20), dp(20))
            setCompoundDrawablesRelative(d, null, null, null)
            compoundDrawablePadding = dp(8)
        })
        col.addView(TextView(this).apply {
            text = p.guestPath
            setTextColor(ContextCompat.getColor(this@ProjectsActivity, R.color.text_faint))
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(2), 0, 0)
        })
        // Meta: punto del último CLI + su nombre + tiempo relativo.
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        meta.addView(View(this).apply {
            background = ContextCompat.getDrawable(this@ProjectsActivity, R.drawable.dot)
            backgroundTintList = android.content.res.ColorStateList.valueOf(agentColor(p.lastAgent))
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) }
        })
        meta.addView(TextView(this).apply {
            val time = ProjectStore.relativeTime(p.lastUsed)
            text = if (time.isEmpty()) agentLabel(p.lastAgent) else "${agentLabel(p.lastAgent)} · $time"
            setTextColor(ContextCompat.getColor(this@ProjectsActivity, R.color.text_dim))
            textSize = 12f
        })
        col.addView(meta)
        card.addView(col)

        // Botón para quitar de la lista (no borra la carpeta).
        card.addView(TextView(this).apply {
            text = "✕"
            setTextColor(ContextCompat.getColor(this@ProjectsActivity, R.color.text_faint))
            textSize = 18f
            setPadding(dp(12), dp(8), dp(8), dp(8))
            setOnClickListener {
                ProjectStore.remove(this@ProjectsActivity, p.guestPath)
                renderProjects()
            }
        })
        return card
    }

    private fun agentColor(agent: String): Int {
        val res = when (agent) {
            "codex" -> R.color.cli_codex
            "gemini" -> R.color.cli_gemini
            else -> R.color.cli_claude
        }
        return ContextCompat.getColor(this, res)
    }

    private fun agentLabel(agent: String): String = when (agent) {
        "codex" -> "Codex CLI"
        "gemini" -> "Gemini CLI"
        else -> "Claude Code"
    }

    /** Abre la terminal del proyecto (actualiza su marca de tiempo). */
    private fun openProject(p: Project) {
        ProjectStore.touch(this, p.name, p.guestPath)
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_PROJECT_NAME, p.name)
                putExtra(MainActivity.EXTRA_PROJECT_PATH, p.guestPath)
                putExtra(MainActivity.EXTRA_PROJECT_AGENT, p.lastAgent)
            },
        )
    }

    // --- Añadir proyecto (SAF) ------------------------------------------------

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) onFolderPicked(uri) }

    private val requestStorage = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) pickFolder.launch(null)
        else snack("Sin permiso de almacenamiento no puedo abrir tus carpetas.")
    }

    private fun onAddProjectClicked() {
        val perm = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            pickFolder.launch(null)
        } else {
            requestStorage.launch(perm)
        }
    }

    private fun onFolderPicked(treeUri: Uri) {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return
        val parts = docId.split(":", limit = 2)
        val volume = parts[0]
        val sub = parts.getOrElse(1) { "" }
        if (!volume.equals("primary", ignoreCase = true)) {
            snack("Por ahora solo el almacenamiento interno. Elige una carpeta de ahí.")
            return
        }
        val guestPath = if (sub.isEmpty()) "/sdcard" else "/sdcard/$sub"
        val name = guestPath.substringAfterLast('/').ifEmpty { "sdcard" }
        val project = ProjectStore.touch(this, name, guestPath)
        openProject(project)
    }

    private fun snack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
}
