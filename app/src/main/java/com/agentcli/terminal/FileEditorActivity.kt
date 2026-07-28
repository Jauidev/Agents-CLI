package com.agentcli.terminal

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import com.agentcli.terminal.databinding.ActivityFileEditorBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.io.File

/**
 * Visor/editor de un archivo del proyecto (C2).
 *
 * Guarda de forma atómica (fichero temporal + rename) para no dejar el archivo a
 * medias si algo falla a mitad de escritura, y avisa antes de salir con cambios
 * sin guardar. El resaltado lo pone [SyntaxHighlighter] con un pequeño retardo
 * al teclear para no repintar en cada pulsación.
 */
class FileEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileEditorBinding
    private lateinit var file: File

    private var dirty = false
    private var highlightable = false
    private val handler = Handler(Looper.getMainLooper())
    private val rehighlight = Runnable {
        SyntaxHighlighter.highlight(binding.editor.text, file.name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_AgentTerminal)
        super.onCreate(savedInstanceState)
        binding = ActivityFileEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        file = File(intent.getStringExtra(EXTRA_HOST_PATH) ?: run { finish(); return })
        binding.fileName.text = file.name
        binding.btnBack.setOnClickListener { confirmExit() }
        binding.btnSave.setOnClickListener { save() }

        load()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() = confirmExit()

    override fun onDestroy() {
        handler.removeCallbacks(rehighlight)
        super.onDestroy()
    }

    // --- Carga ----------------------------------------------------------------

    private fun load() {
        val bytes = runCatching { file.readBytes() }.getOrElse {
            showReadOnly("No pude leer el archivo: ${it.message}")
            return
        }
        if (looksBinary(bytes)) {
            showReadOnly("Es un archivo binario; no se puede editar como texto.")
            return
        }
        binding.editor.setText(String(bytes, Charsets.UTF_8))
        highlightable = bytes.size <= SyntaxHighlighter.MAX_HIGHLIGHT_BYTES &&
            SyntaxHighlighter.supports(file.name)
        if (highlightable) SyntaxHighlighter.highlight(binding.editor.text, file.name)

        binding.editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                dirty = true
                if (!highlightable) return
                // Repintar en cada tecla ahoga el hilo de UI: se agrupa.
                handler.removeCallbacks(rehighlight)
                handler.postDelayed(rehighlight, HIGHLIGHT_DELAY_MS)
            }
        })
    }

    /** Un NUL en la cabecera es la señal clásica de "esto no es texto". */
    private fun looksBinary(bytes: ByteArray): Boolean =
        bytes.take(8 * 1024).any { it == 0.toByte() }

    private fun showReadOnly(message: String) {
        binding.editor.isEnabled = false
        binding.editor.setText(message)
        binding.btnSave.isEnabled = false
        binding.btnSave.alpha = 0.4f
    }

    // --- Guardado -------------------------------------------------------------

    private fun save() {
        val text = binding.editor.text.toString()
        val result = runCatching {
            // Atómico si se puede: escribimos al lado y renombramos encima. Si el
            // rename falla (algunos volúmenes de /sdcard), copiamos como respaldo.
            val tmp = File(file.parentFile, ".${file.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
        result
            .onSuccess {
                dirty = false
                snack("Guardado")
            }
            .onFailure { snack("No pude guardar: ${it.message}") }
    }

    private fun confirmExit() {
        if (!dirty) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.editor_unsaved_title)
            .setMessage(R.string.editor_unsaved_msg)
            .setNegativeButton(R.string.editor_discard) { _, _ -> finish() }
            .setPositiveButton(R.string.editor_save) { _, _ -> save(); finish() }
            .show()
    }

    private fun snack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()

    companion object {
        /** Ruta ABSOLUTA en Android (no la del guest). */
        const val EXTRA_HOST_PATH = "extra_host_path"

        private const val HIGHLIGHT_DELAY_MS = 250L
    }
}
