package com.agentcli.terminal

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.agentcli.terminal.databinding.ActivityFilesBinding
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Explorador de archivos del proyecto (C2).
 *
 * Trabaja con [java.io.File] sobre la ruta REAL de Android
 * ([LinuxEngine.hostFileFor]) en vez de pasar por proot: es instantáneo y no
 * depende de que haya una sesión viva. Sirve para revisar lo que hizo el agente
 * sin salir de la app.
 */
class FilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFilesBinding

    /** Raíz del proyecto: no se navega por encima de aquí. */
    private lateinit var root: File
    private lateinit var current: File

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_AgentTerminal)
        super.onCreate(savedInstanceState)
        binding = ActivityFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val guestPath = intent.getStringExtra(EXTRA_PATH) ?: "/root"
        root = LinuxEngine.hostFileFor(this, guestPath)
        current = root

        binding.btnBack.setOnClickListener { goUpOrFinish() }
        render()
    }

    override fun onResume() {
        super.onResume()
        render() // el agente puede haber creado/borrado archivos mientras tanto
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() = goUpOrFinish()

    private fun goUpOrFinish() {
        if (current.absolutePath == root.absolutePath) {
            finish()
        } else {
            current = current.parentFile ?: root
            render()
        }
    }

    // --- Listado --------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun render() {
        binding.breadcrumb.text = relativeLabel(current)
        binding.fileList.removeAllViews()

        val children = current.listFiles()
        if (children == null) {
            binding.fileList.addView(hint("No puedo leer esta carpeta."))
            return
        }
        if (children.isEmpty()) {
            binding.fileList.addView(hint("Carpeta vacía."))
            return
        }
        // Carpetas primero, luego archivos; ambos por nombre.
        children
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .forEach { binding.fileList.addView(buildRow(it)) }
    }

    /** Ruta mostrada en la cabecera, relativa a la raíz del proyecto. */
    private fun relativeLabel(dir: File): String {
        val rel = dir.absolutePath.removePrefix(root.absolutePath).trim('/')
        return if (rel.isEmpty()) root.name else "${root.name}/$rel"
    }

    private fun hint(msg: String) = TextView(this).apply {
        text = msg
        setTextColor(ContextCompat.getColor(this@FilesActivity, R.color.text_faint))
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(0, dp(40), 0, 0)
    }

    private fun buildRow(file: File): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = ContextCompat.getDrawable(this@FilesActivity, R.drawable.card_bg)
            setOnClickListener { onFileClicked(file) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
        row.addView(TextView(this).apply {
            text = file.name
            setTextColor(
                ContextCompat.getColor(
                    this@FilesActivity,
                    if (file.isDirectory) R.color.text else R.color.text_dim,
                ),
            )
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            val icon = ContextCompat.getDrawable(
                this@FilesActivity,
                if (file.isDirectory) R.drawable.ic_folder else R.drawable.ic_file,
            )?.mutate()
            icon?.setTint(
                ContextCompat.getColor(
                    this@FilesActivity,
                    if (file.isDirectory) R.color.accent else R.color.overlay,
                ),
            )
            icon?.setBounds(0, 0, dp(18), dp(18))
            setCompoundDrawablesRelative(icon, null, null, null)
            compoundDrawablePadding = dp(10)
        })
        row.addView(TextView(this).apply {
            text = if (file.isDirectory) modified(file) else "${humanBytes(file.length())} · ${modified(file)}"
            setTextColor(ContextCompat.getColor(this@FilesActivity, R.color.text_faint))
            textSize = 11f
            setPadding(dp(28), dp(2), 0, 0)
        })
        return row
    }

    private fun modified(file: File): String =
        SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(Date(file.lastModified()))

    private fun humanBytes(bytes: Long): String {
        val units = listOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024; unit++
        }
        return String.format(Locale.US, if (unit == 0) "%.0f %s" else "%.1f %s", value, units[unit])
    }

    private fun onFileClicked(file: File) {
        if (file.isDirectory) {
            current = file
            render()
            return
        }
        if (file.length() > MAX_OPEN_BYTES) {
            snack("Demasiado grande para abrirlo aquí (${humanBytes(file.length())}).")
            return
        }
        startActivity(
            Intent(this, FileEditorActivity::class.java)
                .putExtra(FileEditorActivity.EXTRA_HOST_PATH, file.absolutePath),
        )
    }

    private fun snack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()

    companion object {
        /** Ruta del proyecto vista desde el guest (p.ej. /sdcard/Download/web). */
        const val EXTRA_PATH = "extra_path"

        private const val MAX_OPEN_BYTES = 2L * 1024 * 1024
    }
}
