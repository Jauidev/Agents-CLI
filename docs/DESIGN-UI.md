# Diseño: mejora de interfaz (UI/UX)

> Estado actual: la app es funcional (M0–M4 completos) pero la interfaz es "v0":
> iconos placeholder, botones sueltos, sin identidad visual. Este documento es el
> plan de la pasada de estética. **La lógica/motor NO se toca** — todo esto es
> presentación.
>
> Estrategia: primero **quick wins con Views** (rápidos, no rompen nada); dejar
> **Jetpack Compose** como salto grande opcional al final.

---

## Diagnóstico (qué se ve pocho hoy)

- **Iconos de pestañas**: brújula genérica de Android (`ic_menu_compass`), los 3 iguales.
- **Barra de teclas** (`key_bar`): botones planos, apretados, sin estilo ni agrupación.
- **Sin cabecera**: no se ve de un vistazo el agente activo ni la carpeta.
- **Terminal**: tema gris por defecto de ttyd, fuente pequeña.
- **Sin paleta propia**: todo Material por defecto, sin marca.
- **Pantalla de carga/error/instalación**: texto pelado, sin logo ni feedback bonito.
- **Icono de la app**: vector simple `>_` provisional.

---

## Fase 1 — Quick wins (Views, alto impacto / bajo esfuerzo)

### 1.1 Paleta + tema propio
- Definir paleta "editor de código" (dark-first): fondos `#1E1E2E`/`#181825`, acentos
  (p.ej. verde `#A6E3A1` + violeta `#CBA6F7`), texto `#CDD6F4`. (Base tipo Catppuccin.)
- `res/values/colors.xml` + `themes.xml`: tema Material3 con `colorPrimary`,
  `colorSurface`, `colorBackground`, statusbar/navbar a juego.
- Archivos: `app/src/main/res/values/{colors,themes}.xml`.

### 1.2 Iconos reales por CLI en la barra inferior
- Un icono/color distinto para Claude / Codex / Gemini (vectores propios en
  `res/drawable/`), estado seleccionado con tint del acento.
- Archivos: `res/menu/bottom_nav_menu.xml`, nuevos `res/drawable/ic_*.xml`,
  `bottom_nav` en `activity_main.xml` (colores de item).

### 1.3 Barra de teclas con estilo
- Convertir los botones planos en **chips redondeados** con fondo sutil, padding
  cómodo, y separadores/grupos (navegación | control | símbolos).
- Considerar un `background` con esquinas y `ripple`.
- Código: `MainActivity.setupKeyBar()` (estilo de los `Button`), quizá un
  `res/drawable/key_chip_bg.xml`.

### 1.4 Cabecera fina (context bar)
- Barra superior compacta: `● Claude Code   ·   📁 Test Ia` (agente + carpeta),
  con el punto de color del agente. Sustituye/complementa al `title` de la Activity.
- Código: nuevo view en `activity_main.xml` encima de `content_container`;
  actualizar al cambiar de pestaña / carpeta en `MainActivity`.

### 1.5 Tema de xterm.js a juego
- Pasar más opciones a ttyd (`-t theme={...}`, `fontFamily`, `fontSize`) para que
  el terminal use la misma paleta y una fuente monospace mayor/legible.
- Código: `LinuxService.startTtyd()` (flags `-t` de ttyd).

### 1.6 Pantalla de carga / instalación decente
- Logo + spinner + mensaje ("Preparando la terminal…") con el estilo nuevo, en
  vez del `TextView` pelado. Barra de progreso indeterminada centrada.
- Código: `res/layout/view_error_state.xml` (rehacer), textos en `strings.xml`.

### 1.7 Icono de app + splash
- Icono de launcher propio (no el `>_` provisional) y splash con la marca.
- Archivos: `res/mipmap-*`, `res/drawable/ic_launcher_foreground.xml`,
  opcional `SplashScreen` API.

---

## Fase 2 — Pulido de interacción (medio esfuerzo)

- **Copiar/pegar** cómodo desde el terminal (botón "pegar" que inyecte el
  portapapeles en xterm.js; hoy solo se puede escribir).
- **Reconexión al volver de segundo plano** (`onResume` recarga el WebView; como
  hay tmux no se pierde nada). — pendiente también en la lista técnica.
- **Feedback al cambiar de carpeta** (toast/snackbar consistente, ya hay toast).
- **Indicador de sesión viva** por pestaña (punto verde si el ttyd/CLI corre).
- **Gestión de errores visual**: si el CLI cae, tarjeta con "Reintentar" en vez de
  texto crudo.

---

## Fase 3 — Salto grande (opcional): Jetpack Compose

Rehacer la capa de UI en Compose cuando queramos navegación/ajustes/animaciones
de nivel:
- Pantalla de **Ajustes** (fuente/tamaño del terminal, tema, gestión de carpetas
  recientes por agente, re-login de CLIs, "re-provisionar entorno").
- **Gestor de sesiones/proyectos** (lista de carpetas recientes, abrir en pestaña).
- Animaciones de transición entre pestañas y estados.
- Mantener el WebView vía `AndroidView` dentro de Compose.

> Nota: Compose añade dependencia y curva; hacerlo solo cuando la Fase 1–2 se
> quede corta. El WebView (terminal) sigue siendo el mismo, solo cambia el marco.

---

## Fase 4 — Modo Proyectos (rediseño estructural del flujo)

> Cambia el modelo de "CLI-céntrico" (3 pestañas = 3 CLIs, carpeta enterrada en el
> botón 📁) a **"proyecto-céntrico"**: abres un proyecto y dentro eliges el agente.
> Es como funcionan los IDEs. Probablemente se hace ya en Compose (Fase 3).

### Flujo objetivo

```
Pantalla 1: PROYECTOS
  ┌────────────────────────────┐
  │  📁 Test Ia      hace 2h    │  ← proyectos recientes (tarjetas)
  │  📁 mi-web       ayer       │     (nombre + ruta + fecha + último CLI)
  │  📁 bot-discord  3 días     │
  │  ─────────────────────────  │
  │  ➕ Abrir carpeta…          │  ← selector SAF (como ahora)
  └────────────────────────────┘
            ↓ tocas un proyecto
Pantalla 2: TERMINAL (dentro del proyecto)
  Cabecera: 📁 Test Ia            [← Proyectos]
  … terminal (WebView) …
  [Claude Code] [Codex] [Gemini]  ← las pestañas = "qué CLI" en ESTE proyecto
```

**Decisión de diseño:** NO hacer una pantalla aparte de "elige CLI". El CLI son las
pestañas dentro del proyecto ya abierto → saltas de Claude a Gemini sin salir.
Recordar el último CLI usado por proyecto para abrir en esa pestaña.

### Modelo de datos
- Lista de **proyectos recientes** persistida (JSON en filesDir o prefs):
  `{ id, name, guestPath (/sdcard/...), lastUsedEpoch, lastAgent }`.
- `name` = nombre de la carpeta; ordenar por `lastUsedEpoch` desc.
- Añadir proyecto = flujo SAF actual (`OpenDocumentTree` → `/sdcard/sub`).
- Quitar proyecto de la lista (no borra la carpeta).

### Cambios técnicos (motor mínimo, casi todo UI)
- **tmux por proyecto+CLI**: hoy el nombre de sesión tmux es solo el agente
  (`claude`). Debe incluir el proyecto para no compartir sesión entre proyectos:
  `sess = <agente>_<hashProyecto>`. Sanitizar (tmux no admite `.`/`:`).
  - Tocar: `LinuxEngine.ensureSessionScript` (o pasar el nombre de sesión como
    3er arg a `agent-session.sh`), y `killTmuxSession` para aceptar ese nombre.
- **Workdir**: ya se pasa a `agent-session.sh` como arg y a proot; reutilizable.
  Deja de ser "por agente global" (`workdir_<agente>`) y pasa a ser propiedad del
  proyecto activo.
- **Puertos**: mantener los 3 (7681/7682/7683) para los 3 CLIs; al abrir un
  proyecto, `LinuxService.restart` de la pestaña activa con el workdir del
  proyecto. (Un ttyd por CLI sigue valiendo; solo cambia el cwd/sesión tmux.)
- **Navegación**: Activity de Proyectos → Activity/Screen de Terminal, o (mejor)
  una sola Activity con navegación Compose (Projects screen ↔ Terminal screen),
  manteniendo el WebView vivo vía `AndroidView`.

### Beneficios
- "Proyectos recientes" >> volver a buscar la carpeta cada vez.
- Separa *en qué trabajo* de *qué agente uso*.
- Cada proyecto recuerda su estado (carpeta + último CLI + sesiones tmux vivas).

### Riesgos / cuidado
- No re-extraer el rootfs por error al reestructurar (ver recordatorios).
- Nombres de sesión tmux: sanitizar y mantener < límite; colisiones si dos
  proyectos tienen el mismo nombre → usar hash de la ruta, no el nombre.
- El selector SAF solo da almacenamiento principal (`primary`) → sigue montado
  como `/sdcard` (igual que hoy).

---

## Orden sugerido para mañana

1. **1.1 Paleta + tema** (da identidad al instante, base de todo lo demás)
2. **1.5 Tema de xterm.js** (el terminal ocupa la mayor parte de la pantalla)
3. **1.3 Barra de teclas** con chips
4. **1.2 Iconos por CLI** en la barra inferior
5. **1.4 Cabecera** con agente + carpeta
6. **1.6 Pantalla de carga** + **1.7 icono/splash**

Con 1–3 ya se nota un cambio enorme. El resto es rematar.

Después de la estética (o en paralelo si te animas con Compose): **Fase 4 — Modo
Proyectos**, que es el cambio de flujo más importante (pantalla de proyectos
recientes → terminal con pestañas de CLI dentro del proyecto).

---

## Recordatorios técnicos (no romper nada)

- Todo esto es **UI**: no tocar `LinuxEngine`, `proot`, el rootfs ni
  `ROOTFS_VERSION` (evita re-extracciones de 350 MB por error).
- Sigue siendo **Views + ViewBinding**, sin Compose, hasta la Fase 3.
- Build sin Android Studio abierto: ver `CLAUDE.md` (JDK de Studio + gradle cacheado).
- El teclado especial inyecta teclas por `evaluateJavascript` al
  `.xterm-helper-textarea` — si se cambia el frontend de terminal, revisar eso.
