package com.agentcli.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * Servicio en primer plano que mantiene vivos los servidores ttyd (uno por
 * sesión/puerto) dentro del guest bajo proot. Es lo que permite que el WebView
 * se conecte a http://127.0.0.1:PUERTO y que las sesiones sobrevivan aunque la
 * app pase a segundo plano.
 *
 * M3: lanza `ttyd … /bin/sh -l`. En M4 el comando final será el CLI del agente.
 */
class LinuxService : Service() {

    // puerto -> proceso ttyd en marcha
    private val running = mutableMapOf<Int, Process>()

    // puerto -> "sesión|workdir" que sirve el ttyd vivo (para detectar cambios de
    // proyecto: el mismo puerto se reutiliza entre proyectos, F4).
    private val runningKey = mutableMapOf<Int, String>()

    /** agente -> proyecto en el que se lanzó (para que el aviso abra el correcto). */
    private data class Launch(val workdir: String, val projectName: String)

    private val launches = mutableMapOf<String, Launch>()
    private var lastLaunch: Launch? = null

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        startNotifyWatcher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val agent = intent?.getStringExtra(EXTRA_AGENT) ?: "claude"
        val port = intent?.getIntExtra(EXTRA_PORT, 7681) ?: 7681
        val workdir = intent?.getStringExtra(EXTRA_WORKDIR) ?: "/root"
        val session = intent?.getStringExtra(EXTRA_SESSION) ?: agent
        val projectName = intent?.getStringExtra(EXTRA_PROJECT_NAME)
            ?: workdir.substringAfterLast('/').ifEmpty { "root" }
        val restart = intent?.getBooleanExtra(EXTRA_RESTART, false) ?: false

        startForeground(NOTIF_ID, buildNotification())
        acquireWakeLock()
        Launch(workdir, projectName).let { launches[agent] = it; lastLaunch = it }
        if (restart) running.remove(port)?.let { runCatching { it.destroyForcibly() } }
        startTtyd(agent, port, workdir, session)
        return START_STICKY
    }

    // --- Avisos del agente (B1) -----------------------------------------------

    /**
     * Vigila la cola de avisos que escribe `agent-notify` dentro del guest (mismo
     * truco que el xdg-open del OAuth) y publica una notificación de Android.
     * Vive en el servicio, no en la Activity, porque el aviso importa justo cuando
     * la app está en segundo plano.
     */
    private var notifyObserver: FileObserver? = null

    @Suppress("DEPRECATION") // constructor (String): el de File exige API 29
    private fun startNotifyWatcher() {
        if (notifyObserver != null) return
        val tmpDir = File(LinuxEngine.rootfsDir(this), "tmp").apply { mkdirs() }
        val queue = File(tmpDir, LinuxEngine.NOTIFY_FILE)
        notifyObserver = object : FileObserver(
            tmpDir.absolutePath,
            CLOSE_WRITE or MODIFY or CREATE or MOVED_TO,
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (path != queue.name) return
                val line = runCatching {
                    queue.readText().lines().lastOrNull { it.isNotBlank() }?.trim()
                }.getOrNull() ?: return
                runCatching { queue.writeText("") } // consumir para no repetir
                publishAgentAlert(line)
            }
        }.apply { startWatching() }
    }

    /** Convierte una línea `sesión|tipo|epoch|msg` en una notificación. */
    private fun publishAgentAlert(line: String) {
        val parts = line.split("|")
        // La sesión de tmux es "<agente>_<hashProyecto>" (F4) → el agente es el
        // prefijo. Si el formato no cuadra (p.ej. tmux no expandió el nombre),
        // caemos a un texto genérico y al último proyecto abierto.
        val agent = parts.getOrNull(0)?.substringBefore('_').orEmpty()
        val kind = parts.getOrNull(1).orEmpty()
        val label = when (agent) {
            "claude" -> "Claude Code"
            "codex" -> "Codex CLI"
            "gemini" -> "Gemini CLI"
            else -> "El agente"
        }
        val text = when (kind) {
            "notification" -> "necesita tu confirmación"
            "stop" -> "terminó la tarea"
            "notify" -> "terminó la tarea"
            else -> "lleva un rato en silencio: puede haber terminado"
        }
        val launch = launches[agent] ?: lastLaunch ?: return

        val tap = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_PROJECT_PATH, launch.workdir)
            putExtra(MainActivity.EXTRA_PROJECT_NAME, launch.projectName)
            if (agent.isNotEmpty()) putExtra(MainActivity.EXTRA_PROJECT_AGENT, agent)
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        ensureAlertChannel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, ALERT_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH)
        }
        val notif = builder
            .setContentTitle("$label — ${launch.projectName}")
            .setContentText("$label $text")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        getSystemService(NotificationManager::class.java).notify(ALERT_NOTIF_ID, notif)
    }

    private fun ensureAlertChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(ALERT_CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                "Avisos del agente",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "El CLI terminó una tarea o pide confirmación" },
        )
    }

    /** Evita que Doze congele el CPU con sesiones largas en marcha (F5). */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(android.os.PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "AgentTerminal:linux",
        ).apply { acquire() }
    }

    private fun startTtyd(agent: String, port: Int, workdir: String, session: String) {
        val key = "$session|$workdir"
        // ¿Ya hay un ttyd vivo para este puerto?
        running[port]?.let {
            if (it.isAlive) {
                if (runningKey[port] == key) {
                    Log.i(TAG, "ttyd :$port ya activo ($key)")
                    return
                }
                // Mismo puerto, distinto proyecto/carpeta → reiniciar con el nuevo.
                Log.i(TAG, "ttyd :$port cambia a $key; reinicio")
                running.remove(port)?.let { p -> runCatching { p.destroyForcibly() } }
            }
        }

        if (!LinuxEngine.isStackInstalled(this)) {
            Log.w(TAG, "Stack no instalado; no arranco ttyd. (Provisiona primero)")
            return
        }

        // El script de sesión decide qué CLI lanzar según el agente (F2).
        LinuxEngine.ensureSessionScript(this)
        // Avisador + hooks de los CLIs (B1). Aquí porque el .tmux.conf que escribe
        // ensureSessionScript ya referencia al script.
        LinuxEngine.ensureAgentHooks(this)

        // Comando del guest: ttyd sirviendo la sesión del agente en su workdir.
        // -W permite entrada del cliente; -i 127.0.0.1 no expone a la red.
        // El tema y la fuente van a juego con la paleta de la app (Catppuccin).
        // ProcessBuilder no pasa por una shell, así que el JSON del tema se manda
        // como un único argumento sin necesidad de comillas/escapes.
        // rendererType=dom: el renderizador canvas/webgl de xterm.js pinta mal los
        // caracteres de bloque/gráficos (logo de Claude Code → rectángulo negro) en
        // el WebView de Android; el renderizador DOM usa texto real y los dibuja
        // bien. fontFamily=monospace usa la mono del sistema (buena cobertura de
        // glifos). lineHeight=1 evita huecos entre celdas de bloque.
        val guestCmd = listOf(
            "ttyd",
            "-p", port.toString(),
            "-i", "127.0.0.1",
            "-W",
            "-t", "fontSize=15",
            "-t", "fontFamily=monospace",
            "-t", "lineHeight=1",
            "-t", "rendererType=dom",
            "-t", "scrollback=5000",
            "-t", "theme=$XTERM_THEME",
            "/bin/bash", "/usr/local/bin/agent-session.sh", agent, workdir, session,
        )
        val cmd = LinuxEngine.buildGuestCommand(this, guestCmd)
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        LinuxEngine.applyGuestEnv(this, pb)

        try {
            val proc = pb.start()
            running[port] = proc
            runningKey[port] = key
            Log.i(TAG, "ttyd arrancado :$port para $agent")
            // Volcamos su salida al logcat para depurar.
            thread(isDaemon = true) {
                proc.inputStream.bufferedReader().forEachLine { Log.i("ttyd:$port", it) }
                Log.i(TAG, "ttyd :$port terminó (exit=${runCatching { proc.exitValue() }.getOrNull()})")
            }
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo arrancar ttyd :$port", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        running.values.forEach { runCatching { it.destroyForcibly() } }
        running.clear()
        notifyObserver?.stopWatching()
        notifyObserver = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        Log.i(TAG, "LinuxService detenido; ttyd(s) parados")
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Agents CLI")
            .setContentText("Motor Linux activo")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Motor Linux",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Mantiene vivas las sesiones de terminal" }
                mgr.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        private const val TAG = "LinuxService"
        private const val CHANNEL_ID = "linux_engine"
        private const val NOTIF_ID = 1001

        /** Canal aparte para los avisos del agente (B1): estos SÍ deben sonar. */
        private const val ALERT_CHANNEL_ID = "agent_alerts"
        const val ALERT_NOTIF_ID = 1002

        private const val EXTRA_AGENT = "extra_agent"
        private const val EXTRA_PORT = "extra_port"
        private const val EXTRA_WORKDIR = "extra_workdir"
        private const val EXTRA_SESSION = "extra_session"
        private const val EXTRA_PROJECT_NAME = "extra_project_name"
        private const val EXTRA_RESTART = "extra_restart"

        /**
         * Tema Catppuccin Mocha para xterm.js (a juego con la paleta de la app).
         * Se manda a ttyd con `-t theme=...` como un único argumento.
         */
        private const val XTERM_THEME =
            "{\"background\":\"#1E1E2E\",\"foreground\":\"#CDD6F4\"," +
            "\"cursor\":\"#F5E0DC\",\"cursorAccent\":\"#1E1E2E\"," +
            "\"selectionBackground\":\"#585B70\"," +
            "\"black\":\"#45475A\",\"red\":\"#F38BA8\",\"green\":\"#A6E3A1\"," +
            "\"yellow\":\"#F9E2AF\",\"blue\":\"#89B4FA\",\"magenta\":\"#F5C2E7\"," +
            "\"cyan\":\"#94E2D5\",\"white\":\"#BAC2DE\"," +
            "\"brightBlack\":\"#585B70\",\"brightRed\":\"#F38BA8\"," +
            "\"brightGreen\":\"#A6E3A1\",\"brightYellow\":\"#F9E2AF\"," +
            "\"brightBlue\":\"#89B4FA\",\"brightMagenta\":\"#F5C2E7\"," +
            "\"brightCyan\":\"#94E2D5\",\"brightWhite\":\"#A6ADC8\"}"

        /**
         * Arranca (o asegura) el ttyd de una sesión en [workdir] (ruta del guest).
         * [tmuxSession] es el nombre de la sesión tmux (en Modo Proyectos incluye
         * el hash del proyecto); por defecto = el nombre del agente.
         */
        fun start(
            context: Context,
            session: AgentSession,
            workdir: String = "/root",
            tmuxSession: String = session.bootstrapArg,
            projectName: String = "",
        ) {
            context.startForegroundService(
                buildIntent(context, session, workdir, tmuxSession, projectName, restart = false),
            )
        }

        /** Reinicia el ttyd de una sesión (mata el anterior) con nuevo workdir/sesión. */
        fun restart(
            context: Context,
            session: AgentSession,
            workdir: String,
            tmuxSession: String = session.bootstrapArg,
            projectName: String = "",
        ) {
            context.startForegroundService(
                buildIntent(context, session, workdir, tmuxSession, projectName, restart = true),
            )
        }

        private fun buildIntent(
            context: Context,
            session: AgentSession,
            workdir: String,
            tmuxSession: String,
            projectName: String,
            restart: Boolean,
        ): Intent = Intent(context, LinuxService::class.java).apply {
            putExtra(EXTRA_AGENT, session.bootstrapArg)
            putExtra(EXTRA_PORT, session.port)
            putExtra(EXTRA_WORKDIR, workdir)
            putExtra(EXTRA_SESSION, tmuxSession)
            if (projectName.isNotEmpty()) putExtra(EXTRA_PROJECT_NAME, projectName)
            if (restart) putExtra(EXTRA_RESTART, true)
        }
    }
}
