# Diseño: M4 — CLIs por pestaña + terminal usable

> Objetivo de M4: que cada pestaña (Claude / Codex / Gemini) abra su CLI
> **funcionando de verdad**, con una terminal cómoda y sesiones que sobreviven.
>
> Estado previo (M0–M3 ✅ verificados en Poco X6 Pro): la app arranca proot +
> Alpine embebidos, instala Node/ttyd por `apk`, lanza ttyd en un
> ForegroundService y el WebView muestra una terminal real (`localhost:~#`), sin
> Termux. Ver `docs/DESIGN-embedded-linux.md`.

---

## Fase 0 — Decisión fundacional: rootfs (bloquea todo lo demás)

El sistema base afecta a cada paso siguiente.

| Opción | Pros | Contras |
|---|---|---|
| **Alpine parcheado** (actual) | Ligero (~8 MB), ya funciona | musl rompe binarios glibc/x64 de los CLIs; parches frágiles (`gcompat`) |
| **Debian/Ubuntu (glibc)** ⭐ | Donde los CLIs están *realmente* probados; máxima compatibilidad | Rootfs mayor (~30–50 MB), `apt` más lento |

**Recomendación:** migrar a **Debian glibc**. La arquitectura (proot, ttyd,
servicio, WebView) es idéntica; solo cambia el tarball del rootfs y `apk`→`apt`.
Ideal: probar la instalación de Claude Code en Alpine primero — si va, ahorramos
la migración; si falla, migramos con la decisión ya tomada.

**Trabajo si migramos:** descargar rootfs Debian arm64, reempaquetar como asset,
ajustar `LinuxEngine` (gestor de paquetes) y revalidar M1–M3 (rápido).

---

## Fase 1 — Instalación de los CLIs

1. Extender `LinuxEngine` con `installCli(agent)` → `npm install -g <paquete>`.
   - `@anthropic-ai/claude-code`, `@openai/codex`, `@google/gemini-cli`.
2. **On-demand:** instalar cada CLI al abrir su pestaña por primera vez (arranque
   más rápido, menos disco). Estado por CLI: no-instalado / instalando / listo.
3. UI de progreso reutilizando la pantalla de instalación existente (log en vivo).
4. Marcadores de "CLI X instalado" para no reinstalar.

**Riesgo:** dependencias nativas (ripgrep, etc.). Mitigación: instalar
herramientas del sistema (`ripgrep`, `git`) por apt/apk y apuntar cada CLI a
ellas (p.ej. `USE_BUILTIN_RIPGREP=0` en Claude Code).

**Verificación:** en cada pestaña, `claude/codex/gemini --version` responden.

---

## Fase 2 — Lanzar el CLI correcto por sesión

1. Script en el guest (`/root/agent-session.sh`) que según el agente ejecute
   `claude`/`codex`/`gemini`, con **fallback a shell** si no está instalado o no
   autenticado.
2. `LinuxService` ya soporta un ttyd por puerto → pasar el agente y ejecutar ese
   script en vez de `/bin/sh`.
3. Cada pestaña = su puerto (7681/7682/7683) = su CLI, todos vivos en paralelo.

**Verificación:** la pestaña Claude abre la TUI de Claude Code; cambiar de
pestaña mantiene las otras sesiones vivas.

---

## Fase 3 — Autenticación de cada CLI (lo más delicado de UX)

1. **Almacén de claves** en la app (pantalla de Ajustes) con
   `EncryptedSharedPreferences`: `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`,
   `GEMINI_API_KEY`.
2. Inyectar esas variables en el entorno del guest al arrancar cada sesión.
3. Para CLIs con login OAuth interactivo: **fallback** dentro de la terminal (el
   CLI abre una URL → se abre en el navegador → se pega el token).

**Riesgo:** OAuth con callback a `localhost` puede ser incómodo en móvil; se
resuelve caso por caso.

---

## Fase 4 — "Todo lo de web": terminal usable

**4A. Teclado especial (imprescindible)**
- Fila de botones sobre el teclado: `Esc  Tab  Ctrl  ↑ ↓ ← →  |  ~  /  Ctrl+C`.
- Sin esto, las TUIs de los agentes son casi inusables.

**4B. Frontend propio de terminal (salto de calidad)**
- Sustituir el xterm.js por defecto de ttyd por **nuestra propia página** con
  xterm.js empaquetado en assets, conectada al WebSocket de ttyd.
- Da: tema/fuente a medida, copiar/pegar, resize correcto, enlaces clicables, e
  inyección limpia de las teclas especiales desde el botón Android.

**Decisión:** 4A es quick-win sobre el frontend actual; 4B es más trabajo pero es
"hacerlo bien". Empezar por 4A; 4B si merece la pena.

---

## Fase 5 — Robustez y persistencia de sesión

1. **tmux dentro del guest:** ttyd ejecuta `tmux new -A -s <agente> <cli>`. Si el
   WebView se recarga o Android mata la vista, el CLI sigue vivo en tmux y al
   reconectar retomas donde estabas. Resuelve reconexión + supervivencia.
2. **Wake-lock** en el ForegroundService para sesiones largas.
3. **Reconexión automática** del WebView al volver de segundo plano (`onResume`).
4. "Android mató el servicio" → re-arranque y re-attach a tmux.

**Verificación:** dejas Claude pensando, sales de la app, vuelves → sigue.

---

## Orden sugerido y esfuerzo

| Fase | Qué | Esfuerzo |
|---|---|---|
| 0 | Decidir/migrar rootfs | Bajo-medio |
| 1 | Instalar CLIs on-demand | Medio |
| 2 | Lanzar CLI por pestaña | Bajo |
| 3 | Auth (API keys + Ajustes) | Medio-alto |
| 4A | Teclado especial | Bajo-medio |
| 4B | Frontend web propio | Alto |
| 5 | tmux + robustez | Medio |

**Ruta mínima "usable de verdad":** 0 → 1 → 2 → 4A → 3 (API key) → 5. La 4B queda
como pulido.

---

## Decisiones pendientes antes de arrancar M4

1. **Rootfs:** ¿probar Claude Code en Alpine primero, o ir directos a Debian?
2. **Auth:** ¿API keys en pantalla de Ajustes, o login interactivo en la terminal?
3. **Frontend:** ¿basta ttyd + teclado especial (4A), o frontend propio (4B)?
