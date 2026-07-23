# Ideas / backlog de features

> Lluvia de ideas de posibles features para Agent Terminal, con prioridad y
> esfuerzo estimado. NO incluye lo ya planificado en otros docs (mejora de
> interfaz → `DESIGN-UI.md`; modo proyectos → `DESIGN-UI.md` §Fase 4; selector de
> carpeta, tmux, OAuth navegador → ya implementados, ver `agent-terminal-project`).
>
> Filosofía: priorizar lo que diferencia a una app **móvil** (donde escribir es
> tedioso y sales de la app a menudo) frente a un terminal de PC.
>
> Esfuerzo: 🟢 bajo · 🟡 medio · 🔴 alto. Prioridad: ⭐⭐⭐ alta / ⭐⭐ media / ⭐ baja.

---

## A. Entrada / reducir escritura (lo más valioso en móvil)

### A1. Dictado por voz ⭐⭐⭐ · 🟢
Botón de micrófono que convierte voz → texto e inyecta el prompt en el terminal
(`SpeechRecognizer` de Android). Escribir prompts largos en móvil es lo peor;
dictarlos es un cambio radical. Inyección igual que el teclado especial
(`evaluateJavascript` al `.xterm-helper-textarea`).

### A2. Snippets / prompts rápidos ⭐⭐⭐ · 🟢
Chips/menú con prompts frecuentes ("explica esto", "arregla el bug", "escribe
tests", "haz commit y push") que se inyectan de un toque. Editables y persistidos
en prefs. Enorme ahorro de tecleo.

### A3. Adjuntar imágenes al prompt ⭐⭐⭐ · 🟡
Elegir una foto de galería o una captura y pasársela a Claude Code (es
multimodal). Copiar la imagen a la carpeta del proyecto (o a /tmp del guest) y
referenciar la ruta en el prompt. Caso estrella: "haz esta UI a partir de este
diseño".

### A4. Recibir "Compartir" de otras apps (share target) ⭐⭐⭐ · 🟡
Registrar un `intent-filter` `ACTION_SEND` para que Agent Terminal sea destino de
compartir: mandas texto/enlace/imagen/archivo desde cualquier app → aterriza en
el prompt del agente (o se copia al proyecto activo).

---

## B. Notificaciones / segundo plano

### B1. Notificaciones del agente ⭐⭐⭐ · 🟡
Avisar cuando el CLI termina una tarea larga o **pide confirmación** con la app en
segundo plano ("Claude necesita tu OK"). Clave en móvil. Detección: vigilar la
salida del PTY/ttyd por patrones, o un hook en el guest que escriba un flag (como
el truco de `xdg-open` → `FileObserver`). El `LinuxService` (foreground) ya existe
para emitir la notificación.

### B2. Portapapeles y salida ⭐⭐ · 🟢
Copiar la salida/selección del terminal, pegar cómodo desde el portapapeles de
Android, y compartir un archivo o el resultado a otra app (share sheet).

---

## C. Ver el resultado

### C1. Preview de dev server ⭐⭐⭐ · 🟡
Si el agente levanta algo (`npm run dev` en `:3000`), un botón/pestaña que abre esa
URL en un WebView → ves la web que construye **sin salir de la app**. Detectar
puertos escuchando en el guest (`ss -ltn`) y ofrecer abrirlos.

### C2. Editor / visor de archivos ligero ⭐⭐ · 🔴
Abrir y ojear (o editar rápido) archivos del proyecto fuera del CLI, con resaltado
de sintaxis. Complementa al agente (revisar lo que hizo). Bastante trabajo si se
quiere decente.

---

## D. Entorno y cuentas

### D1. Pantalla de cuentas / auth ⭐⭐ · 🟡
Gestionar el login de cada CLI: cerrar sesión, cambiar de cuenta, pegar API keys,
ver cuál está conectado. Hoy el login es interactivo en la terminal; esto lo hace
visible y gestionable.

### D2. Gestor del entorno ⭐⭐ · 🟡
Instalar extras (python, go…), ver/limpiar uso de disco, actualizar los CLIs
(`npm update -g`), y "re-provisionar" con un botón (subir `ROOTFS_VERSION` /
`provision(force=true)`, preservando `/root`).

### D3. Claves SSH / Git ⭐⭐ · 🟡
Generar/guardar una clave SSH y configurar git para clonar y hacer push a repos
privados desde el móvil. UI para pegar la clave pública en GitHub.

### D4. Backup / restore ⭐ · 🟡
Exportar los proyectos o el `/root` (logins/config) a un archivo, y restaurarlo
tras reinstalar o al cambiar de móvil.

---

## E. Sesiones

### E1. Multi-ventana por proyecto ⭐⭐ · 🟡
Varias pestañas/paneles del mismo CLI aprovechando **tmux windows**, para tener
p.ej. el agente + una shell libre a la vez dentro del mismo proyecto.

### E2. Historial de conversaciones ⭐ · 🟡
Guardar y buscar transcripciones de lo hecho con el agente (los CLIs ya guardan
sesiones en `~/.claude` etc.; exponerlas en una UI).

---

## Top 5 recomendado (máximo impacto móvil)

1. **A1 Dictado por voz** — 🟢, killer para móvil.
2. **B1 Notificaciones del agente** — sales de la app constantemente.
3. **A3 Adjuntar imágenes** — desbloquea el uso multimodal de Claude.
4. **A4 Recibir "Compartir"** — integra la app con todo el sistema.
5. **C1 Preview de dev server** — cierra el ciclo "construir y ver" en el móvil.

> Sugerencia de secuencia: los 🟢 primero (A1, A2, B2) porque se ven rápido; luego
> los 🟡 de más impacto (B1, A3, A4, C1).
