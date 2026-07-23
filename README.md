# Agent Terminal (v0)

Cliente Android nativo (Kotlin) que actúa como **terminal para CLIs de agentes
de código** — Claude Code, Codex CLI, Gemini CLI — que corren dentro de
**Termux** en el mismo dispositivo.

La app **no ejecuta los CLIs** (Android sandboxea cada app). En su lugar,
Termux hospeda un servidor de terminal (`ttyd`) y la app es un **WebView** que
renderiza esa terminal (xterm.js) apuntando a `localhost`.

---

## Arquitectura

```
┌──────────────────────────── Dispositivo Android ────────────────────────────┐
│                                                                              │
│   ┌───────────────────────────┐            ┌──────────────────────────────┐  │
│   │  App "Agent Terminal"     │            │  Termux (entorno Linux real) │  │
│   │  (este repo)              │            │                              │  │
│   │                           │            │  ┌────────────────────────┐  │  │
│   │  ┌─────────────────────┐  │  WebSocket │  │ ttyd :7681  → claude    │  │  │
│   │  │ WebView (xterm.js)  │◀─┼────────────┼─▶│ ttyd :7682  → codex     │  │  │
│   │  │ http://127.0.0.1:P  │  │  (HTTP/WS  │  │ ttyd :7683  → gemini    │  │  │
│   │  └─────────────────────┘  │  localhost)│  └────────────────────────┘  │  │
│   │                           │            │            ▲                 │  │
│   │  ┌─────────────────────┐  │  RUN_COMMAND (bootstrap) │                 │  │
│   │  │ TermuxIntegration   │──┼────────────┼────────────┘                 │  │
│   │  │ (Termux:API intent) │  │  solo para arrancar ttyd / checks         │  │
│   │  └─────────────────────┘  │            │  (NO para la sesión interactiva)│
│   └───────────────────────────┘            └──────────────────────────────┘  │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

- **Sesiones interactivas** (streaming, colores, confirmaciones, diffs): van por
  el WebView ↔ ttyd (WebSocket). Cada agente = un ttyd en su propio puerto, así
  las tres sesiones viven en paralelo.
- **Termux:API `RUN_COMMAND`**: solo para bootstrap puntual (arrancar el ttyd si
  no está corriendo, verificar instalaciones). Nunca para la sesión en vivo.

---

## Requisitos

### En el dispositivo

1. **Termux** y **Termux:API** instalados **desde F-Droid** (no Play Store; la
   versión de Play está obsoleta y es incompatible).
   - Termux: https://f-droid.org/packages/com.termux/
   - Termux:API: https://f-droid.org/packages/com.termux.api/
2. Para que la app pueda usar `RUN_COMMAND`, en Termux:
   ```bash
   mkdir -p ~/.termux
   echo 'allow-external-apps = true' >> ~/.termux/termux.properties
   termux-reload-settings
   ```

### Permisos que declara la app (`AndroidManifest.xml`)

| Permiso | Para qué |
|---|---|
| `android.permission.INTERNET` | Cargar `http://127.0.0.1:PUERTO` en el WebView |
| `com.termux.permission.RUN_COMMAND` | Lanzar el intent de bootstrap en Termux |

El tráfico HTTP en claro se limita a `127.0.0.1`/`localhost` vía
`res/xml/network_security_config.xml` (el resto de la red sigue exigiendo TLS).

---

## Termux setup

Los scripts viven en `termux/` (no forman parte del APK). Cópialos a Termux
(por ejemplo con `git clone`, `scp`, o compartiéndolos al almacenamiento y
`termux-setup-storage`).

```bash
# Dentro de Termux, en la carpeta con los scripts:
bash setup.sh
```

`setup.sh`:
- Instala **Node.js** y **ttyd** vía `pkg` si faltan.
- Instala los CLIs de agentes vía `npm -g` (best-effort; ajusta los nombres de
  paquete a la doc oficial de cada uno).
- Copia `agent-bootstrap.sh` y `agent-session.sh` a `~` y los hace ejecutables.

Arrancar una sesión manualmente:

```bash
~/agent-bootstrap.sh claude 7681   # Claude Code en el puerto 7681
~/agent-bootstrap.sh codex  7682   # Codex CLI   en el puerto 7682
~/agent-bootstrap.sh gemini 7683   # Gemini CLI  en el puerto 7683
```

Los puertos deben coincidir con los de `AgentSession` en la app
(`app/src/main/java/com/agentcli/terminal/AgentSession.kt`).

- `agent-bootstrap.sh <agente> <puerto>`: levanta `ttyd` en `127.0.0.1:<puerto>`
  (idempotente: no duplica si el puerto ya está ocupado).
- `agent-session.sh <agente>`: es el "comando" que ejecuta ttyd; lanza el CLI
  correspondiente y cae a una shell si el CLI termina.

---

## Estructura del proyecto

```
.
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/agentcli/terminal/
│       │   ├── MainActivity.kt        # WebView + navegación + estados
│       │   ├── AgentSession.kt        # modelo de sesión + catálogo (puertos)
│       │   └── TermuxIntegration.kt   # intent RUN_COMMAND de Termux:API
│       └── res/
│           ├── layout/                # activity_main + view_error_state
│           ├── menu/bottom_nav_menu.xml
│           ├── xml/network_security_config.xml
│           └── values/ + mipmap/drawable (icono)
└── termux/
    ├── setup.sh            # instalador (correr en Termux)
    ├── agent-bootstrap.sh  # arranca ttyd por agente/puerto
    └── agent-session.sh    # lanza el CLI dentro de ttyd
```

---

## Levantar el entorno de desarrollo

Abre la carpeta en Android Studio y sincroniza Gradle. En la primera
sincronización, Android Studio regenera el `gradle-wrapper.jar` si falta.
Luego *Run* sobre el dispositivo/emulador.

> El WebView mostrará la pantalla de error hasta que haya un `ttyd`
> escuchando en el puerto de la sesión activa (ver *Termux setup*).

---

## Roadmap (siguientes pasos)

1. **Bootstrap automático fiable**: en vez del delay fijo tras `RUN_COMMAND`,
   hacer *polling* del puerto (o un check vía Termux:API) y cargar el WebView
   solo cuando ttyd responda. Detectar Termux/Termux:API ausentes y enlazar a
   F-Droid.
2. **Autenticación de cada CLI**: flujo para API keys / login de Claude, Codex
   y Gemini (probablemente config en Termux + variables de entorno; **fuera de
   v0**).
3. **Persistencia de sesión**: Android puede matar Termux en segundo plano.
   Estrategias: `termux-wake-lock`, correr ttyd bajo `tmux`/`nohup`, y que la
   app reintente reconectar al volver a foreground.
4. **Gestión de puertos/sesiones dinámica**: mover el catálogo hardcodeado a
   preferencias editables (añadir/quitar agentes, elegir puertos, estado
   "corriendo/parado" por sesión).
5. **Calidad de terminal**: teclado con teclas especiales (Esc, Tab, flechas,
   Ctrl), gestión del *back* y del ciclo de vida del WebView, y quizás empaquetar
   xterm.js propio en vez de depender del frontend de ttyd.
