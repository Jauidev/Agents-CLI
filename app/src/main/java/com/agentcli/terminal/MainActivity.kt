package com.agentcli.terminal

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.webkit.MimeTypeMap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.agentcli.terminal.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Activity única de la app.
 *
 *  - WebView que renderiza la terminal (xterm.js servido por ttyd en localhost).
 *  - Barra inferior para cambiar de sesión (Claude/Codex/Gemini → distinto puerto).
 *  - Pantalla de estado que: (a) instala el entorno (Node/ttyd) la 1ª vez con log
 *    en vivo, y (b) muestra errores de conexión con opción de reintento.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentSession: AgentSession = Sessions.default

    /** Proyecto activo (Modo Proyectos, F4): carpeta y nombre visibles. */
    private var projectName: String = ""
    private var projectPath: String = "/root"

    /** Reintentos restantes al conectar con ttyd (se rearma en cada intento). */
    private var connectAttempts = 0
    private var loadFailed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Cambiamos del tema de splash (windowBackground con logo) al tema normal
        // antes de inflar la UI.
        setTheme(R.style.Theme_AgentTerminal)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Proyecto recibido de ProjectsActivity. Define la carpeta de las 3 pestañas.
        projectPath = intent.getStringExtra(EXTRA_PROJECT_PATH) ?: "/root"
        projectName = intent.getStringExtra(EXTRA_PROJECT_NAME)
            ?: projectPath.substringAfterLast('/').ifEmpty { "root" }
        val lastAgent = intent.getStringExtra(EXTRA_PROJECT_AGENT) ?: "claude"
        currentSession = Sessions.all.firstOrNull { it.bootstrapArg == lastAgent } ?: Sessions.default

        setupWebView()
        setupCliSelector()
        setupStatusScreen()
        setupKeyBar()

        binding.headerBack.setOnClickListener { finish() } // volver a Proyectos
        binding.headerFiles.setOnClickListener {
            startActivity(
                Intent(this, FilesActivity::class.java)
                    .putExtra(FilesActivity.EXTRA_PATH, projectPath),
            )
        }
        // Abrimos en el último CLI usado en este proyecto.
        enterSession(currentSession)
    }

    // --- Fila de teclas especiales (F4A) --------------------------------------

    /** Tecla del key bar: o un evento de teclado (key+code+mods) para xterm.js. */
    private data class KeyDef(
        val label: String,
        val key: String,
        val code: Int,
        val ctrl: Boolean = false,
        val shift: Boolean = false,
    )

    /** Teclas agrupadas: navegación | control | símbolos (separadas por divisores). */
    private val keyGroups = listOf(
        // Esenciales (sí o sí): salir, autocompletar, historial/navegación, cancelar.
        listOf(
            KeyDef("Esc", "Escape", 27),
            KeyDef("Tab", "Tab", 9),
            KeyDef("↑", "ArrowUp", 38),
            KeyDef("↓", "ArrowDown", 40),
            KeyDef("←", "ArrowLeft", 37),
            KeyDef("→", "ArrowRight", 39),
            KeyDef("Ctrl+C", "c", 67, ctrl = true),
        ),
        // Secundarias.
        listOf(
            KeyDef("⇧Tab", "Tab", 9, shift = true),
            KeyDef("Ctrl+D", "d", 68, ctrl = true),
        ),
        // Símbolos.
        listOf(
            KeyDef("/", "/", 191),
            KeyDef("|", "|", 220),
            KeyDef("~", "~", 192),
            KeyDef("-", "-", 189),
        ),
    )

    /** dp → px según la densidad de la pantalla. */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * Teclas de gestión de ventanas de tmux (E1). Se mandan como <prefijo> + tecla
     * DESDE DENTRO del terminal (ver [sendTmuxPrefix]); nunca con `tmux` desde
     * Kotlin, porque cada runInGuest es otra instancia de proot y su cliente no
     * engancha con el servidor tmux que trazó la primera (ver agent-session.sh).
     */
    private val windowNew = KeyDef("＋", "c", 67)
    private val windowPrev = KeyDef("◂", "p", 80)
    private val windowNext = KeyDef("▸", "n", 78)
    private val windowKill = KeyDef("✕", "W", 87, shift = true)

    private fun setupKeyBar() {
        binding.keyBarRow.gravity = android.view.Gravity.CENTER_VERTICAL

        // Chip de pegar: inyecta el portapapeles en el terminal (acento de marca).
        addChip(makeChip("Pegar").apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
            setChipIcon(this, R.drawable.ic_paste, R.color.accent)
            setOnClickListener { pasteFromClipboard() }
        })
        // Chip de adjuntar imagen (A3): la copia al proyecto y pega su ruta.
        addChip(makeChip("Imagen").apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
            setChipIcon(this, R.drawable.ic_attach, R.color.accent)
            setOnClickListener { pickImage.launch("image/*") }
        })
        addDivider()

        keyGroups.forEach { group ->
            group.forEach { def ->
                addChip(makeChip(def.label).apply { setOnClickListener { sendKeyToTerminal(def) } })
            }
            addDivider()
        }

        // Grupo de ventanas de tmux (E1): nueva, anterior, siguiente, cerrar.
        // Además, con `mouse on` se puede tocar el nombre de la ventana en la barra
        // de estado de tmux para saltar a ella.
        addChip(makeChip(windowNew.label).apply { setOnClickListener { sendTmuxPrefix(windowNew) } })
        addChip(makeChip(windowPrev.label).apply { setOnClickListener { sendTmuxPrefix(windowPrev) } })
        addChip(makeChip(windowNext.label).apply { setOnClickListener { sendTmuxPrefix(windowNext) } })
        // Cerrar pide confirmación: un toque accidental mataría la ventana del CLI.
        addChip(makeChip(windowKill.label).apply {
            setOnClickListener {
                Snackbar.make(binding.root, "¿Cerrar esta ventana de tmux?", Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.keyBar)
                    .setAction("Cerrar") { sendTmuxPrefix(windowKill) }
                    .show()
            }
        })
    }

    /** Crea un botón con estilo de "chip" grande (redondeado, fondo sutil). */
    private fun makeChip(label: String): Button =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            minWidth = dp(48); minimumWidth = dp(48)
            minHeight = dp(44); minimumHeight = dp(44)
            stateListAnimator = null
            includeFontPadding = false
            gravity = android.view.Gravity.CENTER
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.key_chip_bg)
        }

    /** Pone un icono vectorial tintado a la izquierda del texto de un chip. */
    private fun setChipIcon(btn: Button, drawableRes: Int, colorRes: Int) {
        val d = ContextCompat.getDrawable(this, drawableRes)?.mutate() ?: return
        d.setTint(ContextCompat.getColor(this, colorRes))
        val s = dp(16)
        d.setBounds(0, 0, s, s)
        btn.setCompoundDrawablesRelative(d, null, null, null)
        btn.compoundDrawablePadding = dp(6)
    }

    private fun addChip(btn: Button) {
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(4); marginEnd = dp(4) }
        binding.keyBarRow.addView(btn, lp)
    }

    /** Separador vertical fino entre grupos de teclas. */
    private fun addDivider() {
        val div = View(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.overlay))
        }
        val lp = LinearLayout.LayoutParams(dp(1), dp(28)).apply {
            marginStart = dp(6); marginEnd = dp(6)
        }
        binding.keyBarRow.addView(div, lp)
    }

    /**
     * Inyecta la tecla en xterm.js despachando un keydown sintético al textarea
     * oculto del terminal (xterm-helper-textarea). xterm.js no comprueba
     * isTrusted, y las teclas imprimibles funcionan con keyCode >= 48.
     */
    private fun sendKeyToTerminal(def: KeyDef) {
        val js = """
            (function(){
              var ta = document.querySelector('.xterm-helper-textarea');
              if (!ta) return;
              ta.focus();
              ta.dispatchEvent(new KeyboardEvent('keydown', {
                key: ${jsString(def.key)}, keyCode: ${def.code}, which: ${def.code},
                ctrlKey: ${def.ctrl}, shiftKey: ${def.shift},
                bubbles: true, cancelable: true
              }));
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    /**
     * Manda la tecla prefijo de tmux (Ctrl+B) seguida de [def] al terminal. Los dos
     * keydown van en el mismo bloque JS: xterm.js los procesa en orden, así que
     * tmux los recibe como una secuencia de prefijo válida.
     */
    private fun sendTmuxPrefix(def: KeyDef) {
        val js = """
            (function(){
              var ta = document.querySelector('.xterm-helper-textarea');
              if (!ta) return;
              ta.focus();
              function k(key, code, ctrl, shift){
                ta.dispatchEvent(new KeyboardEvent('keydown', {
                  key: key, keyCode: code, which: code,
                  ctrlKey: ctrl, shiftKey: shift,
                  bubbles: true, cancelable: true
                }));
              }
              k('b', 66, true, false);
              k(${jsString(def.key)}, ${def.code}, ${def.ctrl}, ${def.shift});
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    private fun jsString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * Inyecta [text] en el terminal despachando un evento 'paste' sintético al
     * textarea de xterm.js con el texto en su clipboardData. Así se respetan
     * saltos de línea y caracteres especiales (a diferencia de teclear).
     */
    private fun pasteTextIntoTerminal(text: String) {
        val quoted = JSONObject.quote(text) // escapa comillas, saltos de línea, etc.
        val js = """
            (function(){
              var ta = document.querySelector('.xterm-helper-textarea');
              if (!ta) return;
              ta.focus();
              var dt = new DataTransfer();
              dt.setData('text/plain', $quoted);
              ta.dispatchEvent(new ClipboardEvent('paste', {
                clipboardData: dt, bubbles: true, cancelable: true
              }));
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    /** Pega el portapapeles de Android en el terminal. */
    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
        if (text.isNullOrEmpty()) {
            toast("Portapapeles vacío")
            return
        }
        pasteTextIntoTerminal(text)
    }

    // --- Adjuntar imágenes al prompt (A3) -------------------------------------

    /** Selector de imagen del sistema (URI de contenido: no pide permisos). */
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) attachImage(uri) }

    /**
     * Copia la imagen elegida dentro del proyecto (o en /tmp del guest si no hay
     * proyecto) y pega su ruta en el prompt, para que el CLI —que es multimodal—
     * pueda leerla. Se usa ruta RELATIVA dentro del proyecto porque los tres CLIs
     * trabajan sobre el cwd.
     */
    private fun attachImage(uri: Uri) {
        thread {
            val result = runCatching {
                val name = "img-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                    .format(Date())}.${imageExtension(uri)}"
                val (dir, guestRef) = if (projectPath == "/root") {
                    File(LinuxEngine.rootfsDir(this), "tmp") to "/tmp/$name"
                } else {
                    File(LinuxEngine.hostFileFor(this, projectPath), ATTACH_DIR) to "$ATTACH_DIR/$name"
                }
                dir.mkdirs()
                val dest = File(dir, name)
                (contentResolver.openInputStream(uri)
                    ?: error("no pude abrir la imagen")).use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                guestRef
            }
            runOnUiThread {
                result
                    .onSuccess { ref ->
                        pasteTextIntoTerminal("$ref ")
                        toast("Imagen adjuntada: $ref")
                    }
                    .onFailure { toast("No pude adjuntar la imagen: ${it.message}") }
            }
        }
    }

    /** Extensión del archivo a partir del tipo MIME (png por defecto). */
    private fun imageExtension(uri: Uri): String {
        val mime = contentResolver.getType(uri)
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?: uri.lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.length in 1..4 }
            ?: "png"
    }

    // --- WebView --------------------------------------------------------------

    /**
     * Remapea el fondo del índice 16 de la paleta 256 (negro puro #000000) al
     * fondo del terminal. Claude Code rellena su logo de bienvenida con bg-16
     * asumiendo un terminal de fondo negro; sobre nuestra paleta (#1E1E2E) eso se
     * ve como un rectángulo negro feo. Con el renderizador DOM las celdas son
     * spans con clase `xterm-bg-16`, así que un CSS las funde con el fondo y solo
     * quedan los trazos de color del logo. Idempotente (id fijo).
     */
    private fun injectTerminalCss() {
        val js = """
            (function(){
              if (document.getElementById('agentterm-css')) return;
              var s = document.createElement('style');
              s.id = 'agentterm-css';
              s.textContent = '.xterm-bg-16{background-color:#1E1E2E !important;}';
              document.head.appendChild(s);
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            builtInZoomControls = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loadFailed = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (loadFailed) return
                // La terminal cargó de verdad: cancelamos el watchdog y mostramos.
                pageReady = true
                loadHandler.removeCallbacks(loadWatchdog)
                binding.progress.visibility = View.GONE
                showTerminal()
                injectTerminalCss()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) return
                // No reintentamos aquí: el watchdog es el único que reintenta, así
                // cubre TAMBIÉN el caso en que la carga se cuelga sin error (ttyd
                // aceptando el puerto pero aún sin responder) → antes: spinner
                // infinito al cambiar de CLI.
                loadFailed = true
            }
        }
    }

    // --- Selector de CLI (cabecera, arriba a la izquierda) --------------------

    /** Icono de cada CLI en el selector, por agente. */
    private val cliIconRes = mapOf(
        "claude" to R.drawable.ic_cli_claude,
        "codex" to R.drawable.ic_cli_codex,
        "gemini" to R.drawable.ic_cli_gemini,
    )

    private val cliToggles = mutableMapOf<String, LinearLayout>()
    private val cliIcons = mutableMapOf<String, android.widget.ImageView>()
    private val cliDots = mutableMapOf<String, View>()

    /** Construye los 3 botones de CLI en la cabecera (icono + punto de vida). */
    private fun setupCliSelector() {
        Sessions.all.forEach { session ->
            val agent = session.bootstrapArg
            val icon = android.widget.ImageView(this).apply {
                setImageResource(cliIconRes[agent] ?: R.drawable.ic_cli_claude)
                layoutParams = FrameLayout.LayoutParams(dp(26), dp(26)).apply {
                    gravity = android.view.Gravity.CENTER
                }
            }
            val dot = View(this).apply {
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.dot)
                backgroundTintList =
                    android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.accent_green))
                visibility = View.INVISIBLE
                layoutParams = FrameLayout.LayoutParams(dp(7), dp(7)).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.END
                    topMargin = dp(6); marginEnd = dp(4)
                }
            }
            val iconWrap = FrameLayout(this).apply {
                addView(icon); addView(dot)
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            }
            val toggle = LinearLayout(this).apply {
                gravity = android.view.Gravity.CENTER
                addView(iconWrap)
                setOnClickListener { onCliSelected(session) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ).apply { marginEnd = dp(2) }
            binding.cliSelector.addView(toggle, lp)

            cliToggles[agent] = toggle
            cliIcons[agent] = icon
            cliDots[agent] = dot
        }
    }

    private fun onCliSelected(session: AgentSession) {
        if (session.id == currentSession.id) return
        currentSession = session
        // Recordamos el CLI elegido para reabrir el proyecto en esta pestaña.
        if (projectPath != "/root") {
            ProjectStore.setLastAgent(this, projectPath, session.bootstrapArg)
        }
        enterSession(session)
    }

    /** Resalta el toggle del CLI activo (pastilla + icono full; resto atenuado).
     *  Los iconos son los logos reales de cada CLI (PNG a color), sin tintar. */
    private fun highlightActiveCli() {
        Sessions.all.forEach { session ->
            val agent = session.bootstrapArg
            val active = session.id == currentSession.id
            cliToggles[agent]?.background =
                if (active) ContextCompat.getDrawable(this, R.drawable.cli_toggle_bg) else null
            cliIcons[agent]?.imageTintList = null // conservar el color del logo
            cliIcons[agent]?.alpha = if (active) 1f else 0.45f
        }
    }

    // --- Pantalla de estado (instalación / error) -----------------------------

    private fun setupStatusScreen() {
        binding.errorState.errorMessage.apply {
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
        }
        binding.errorState.btnBootstrap.setOnClickListener {
            if (!isReady()) installEnvironment()
            else connectToSession(currentSession)
        }
        binding.errorState.btnRetry.setOnClickListener {
            connectToSession(currentSession)
        }
    }

    /**
     * Punto de entrada a una sesión: si el entorno no está instalado, muestra el
     * prompt de instalación; si lo está, arranca ttyd y conecta.
     */
    /** Entorno listo = rootfs de la versión actual extraído + node/ttyd presentes. */
    private fun isReady() = LinuxEngine.isProvisioned(this) && LinuxEngine.isStackInstalled(this)

    /** Evita lanzar dos provisiones a la vez (p.ej. al cambiar de pestaña). */
    private var provisioning = false

    private fun enterSession(session: AgentSession) {
        title = "${getString(R.string.app_name)} — ${session.title}"
        updateHeader(session)
        when {
            isReady() -> connectToSession(session)
            // Primera apertura: el rootfs viene completo en la APK, así que
            // extraemos directamente sin pedir nada al usuario.
            !provisioning -> installEnvironment()
            // Ya hay una provisión en marcha: no hacemos nada, al acabar conecta.
        }
    }

    // --- Carga del WebView con watchdog ---------------------------------------

    /** true cuando la terminal actual terminó de cargar (cancela el watchdog). */
    private var pageReady = false
    private val loadHandler = Handler(Looper.getMainLooper())

    /**
     * Reintenta la carga si la terminal no aparece a tiempo. Cubre tanto el error
     * de conexión como el CUELGUE silencioso (ttyd acepta el puerto pero aún no
     * responde al cambiar de CLI) — antes eso dejaba el spinner girando sin fin.
     */
    private val loadWatchdog = object : Runnable {
        override fun run() {
            if (pageReady) return
            if (connectAttempts > 0) {
                connectAttempts--
                loadFailed = false
                binding.webView.stopLoading()
                binding.webView.loadUrl(currentSession.url)
                loadHandler.postDelayed(this, 1800)
            } else {
                showConnectionError()
            }
        }
    }

    /** Arranca (o asegura) el ttyd de la sesión y carga el WebView con watchdog. */
    private fun connectToSession(session: AgentSession) {
        LinuxService.start(this, session, projectPath, tmuxSessionFor(session), projectName)
        startUrlWatcher()
        showLoading()
        pageReady = false
        loadFailed = false
        connectAttempts = 12 // ~22s de margen (el CLI del guest tarda en arrancar)
        loadHandler.removeCallbacks(loadWatchdog)
        binding.webView.loadUrl(session.url)
        loadHandler.postDelayed(loadWatchdog, 1800)
    }

    // --- Proyecto activo ------------------------------------------------------

    /**
     * Nombre de la sesión tmux del [session] en el proyecto activo. Incluye un
     * hash de la ruta para que dos proyectos no compartan sesión (F4). Con /root
     * (sin proyecto) usa el nombre del agente, como antes.
     */
    private fun tmuxSessionFor(session: AgentSession): String =
        if (projectPath == "/root") session.bootstrapArg
        else "${session.bootstrapArg}_${Integer.toHexString(projectPath.hashCode())}"

    /** Refresca la cabecera: resalta el CLI activo y muestra el proyecto. */
    private fun updateHeader(session: AgentSession) {
        binding.headerFolder.text = projectName
        highlightActiveCli()
    }

    /** Feedback breve y consistente: Snackbar flotando sobre la barra de teclas. */
    private fun toast(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT)
            .setAnchorView(binding.keyBar)
            .show()
    }

    // --- Apertura automática del navegador (OAuth de los CLIs) ----------------

    /**
     * El xdg-open falso del guest escribe URLs en rootfs/tmp/.agent-open-url.
     * Aquí vigilamos ese fichero y abrimos el navegador de Android con la URL.
     */
    private var urlObserver: FileObserver? = null

    @Suppress("DEPRECATION") // constructor (String) — el de File pide API 29
    private fun startUrlWatcher() {
        if (urlObserver != null) return
        val tmpDir = File(LinuxEngine.rootfsDir(this), "tmp").apply { mkdirs() }
        val flagFile = File(tmpDir, ".agent-open-url")
        urlObserver = object : FileObserver(
            tmpDir.absolutePath,
            CLOSE_WRITE or MODIFY or CREATE or MOVED_TO,
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (path != flagFile.name) return
                val url = runCatching {
                    flagFile.readText().lines().lastOrNull { it.isNotBlank() }?.trim()
                }.getOrNull() ?: return
                if (!url.startsWith("http://") && !url.startsWith("https://")) return
                runCatching { flagFile.writeText("") } // consumir para no repetir
                runOnUiThread {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            }
        }.apply { startWatching() }
    }

    // --- Ciclo de vida: reconexión + indicador de sesión viva -----------------

    /** True si la app estuvo en segundo plano (para recargar al volver). */
    private var wasPaused = false

    private val liveHandler = Handler(Looper.getMainLooper())
    private val liveRunnable = object : Runnable {
        override fun run() {
            refreshLiveIndicators()
            liveHandler.postDelayed(this, 4000)
        }
    }

    override fun onResume() {
        super.onResume()
        setNotifyArmed(false)
        // Al volver de segundo plano recargamos el WebView: el websocket de ttyd
        // se reconecta y re-engancha la sesión tmux (no se pierde nada del CLI).
        if (wasPaused) {
            wasPaused = false
            if (isReady() && binding.webView.visibility == View.VISIBLE) {
                connectToSession(currentSession)
            }
        }
        liveHandler.post(liveRunnable)
    }

    override fun onPause() {
        wasPaused = true
        setNotifyArmed(true)
        liveHandler.removeCallbacks(liveRunnable)
        super.onPause()
    }

    /**
     * Arma o desarma los avisos del agente (B1). El script `agent-notify` del guest
     * solo escribe en la cola si existe esta bandera, así que con la app delante no
     * se notifica nada. Al volver, se limpia el aviso pendiente.
     */
    private fun setNotifyArmed(armed: Boolean) {
        runCatching {
            val flag = File(
                File(LinuxEngine.rootfsDir(this), "tmp").apply { mkdirs() },
                LinuxEngine.NOTIFY_ARMED_FILE,
            )
            if (armed) {
                flag.writeText("1")
            } else {
                flag.delete()
                getSystemService(android.app.NotificationManager::class.java)
                    ?.cancel(LinuxService.ALERT_NOTIF_ID)
            }
        }
    }

    /** Actualiza el punto verde de cada CLI según si su ttyd está escuchando. */
    private fun refreshLiveIndicators() {
        thread {
            val states = Sessions.all.map { it.bootstrapArg to isPortOpen(it.port) }
            runOnUiThread {
                states.forEach { (agent, alive) ->
                    cliDots[agent]?.visibility = if (alive) View.VISIBLE else View.INVISIBLE
                }
            }
        }
    }

    private fun isPortOpen(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) }
        true
    }.getOrDefault(false)

    override fun onDestroy() {
        liveHandler.removeCallbacks(liveRunnable)
        loadHandler.removeCallbacks(loadWatchdog)
        urlObserver?.stopWatching()
        urlObserver = null
        super.onDestroy()
    }

    /** Prepara el entorno (extracción del rootfs incluido) y conecta al terminar. */
    private fun installEnvironment() {
        provisioning = true
        showStatus(getString(R.string.install_title), getString(R.string.install_extracting), mono = true)
        binding.errorState.installProgress.visibility = View.VISIBLE
        binding.errorState.btnBootstrap.isEnabled = false
        binding.errorState.btnRetry.isEnabled = false
        val console = LogConsole(this, binding.errorState.errorMessage)
        thread {
            val ok = LinuxEngine.installStack(this) { console.append(it) }
            console.flush()
            runOnUiThread {
                provisioning = false
                binding.errorState.btnBootstrap.isEnabled = true
                binding.errorState.btnRetry.isEnabled = true
                if (ok) connectToSession(currentSession)
                else showStatus(getString(R.string.install_failed_title), console.text(), mono = true)
            }
        }
    }

    // --- Estados visuales -----------------------------------------------------

    private fun showTerminal() {
        binding.progress.visibility = View.GONE
        binding.errorState.root.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
    }

    private fun showLoading() {
        binding.progress.visibility = View.VISIBLE
        binding.errorState.root.visibility = View.GONE
    }

    private fun showInstallPrompt() {
        binding.errorState.btnBootstrap.text = getString(R.string.btn_install)
        showStatus(getString(R.string.install_prompt_title), getString(R.string.install_prompt_msg))
    }

    private fun showConnectionError() {
        binding.errorState.btnBootstrap.text = getString(R.string.btn_start_terminal)
        showStatus(getString(R.string.conn_error_title), getString(R.string.conn_error_msg, currentSession.url))
    }

    private fun showStatus(titleText: String, message: String, mono: Boolean = false) {
        binding.progress.visibility = View.GONE
        binding.webView.visibility = View.GONE
        binding.errorState.installProgress.visibility = View.GONE
        binding.errorState.root.visibility = View.VISIBLE
        binding.errorState.errorTitle.text = titleText
        binding.errorState.errorMessage.apply {
            // Log de instalación → monospace pequeño; mensajes de estado → legible.
            typeface = if (mono) Typeface.MONOSPACE else Typeface.DEFAULT
            textSize = if (mono) 12f else 15f
            text = message
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (binding.webView.visibility == View.VISIBLE && binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed() // vuelve a la pantalla de Proyectos
        }
    }

    companion object {
        /** Carpeta (oculta) del proyecto donde se copian las imágenes adjuntas. */
        private const val ATTACH_DIR = ".attachments"

        const val EXTRA_PROJECT_NAME = "extra_project_name"
        const val EXTRA_PROJECT_PATH = "extra_project_path"
        const val EXTRA_PROJECT_AGENT = "extra_project_agent"
    }
}
