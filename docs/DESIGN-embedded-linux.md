# Diseño: entorno Linux embebido (sin Termux)

> Objetivo: que la app **arranque el entorno Linux por debajo**, sin depender de
> Termux ni Termux:API. La UI actual (WebView + pestañas) se conserva; solo
> cambia el *motor* que levanta `ttyd`.
>
> Modo elegido: **híbrido** — la APK trae `proot` + un rootfs Alpine base
> pequeño; Node, ttyd y los CLIs se instalan en el primer arranque (requiere red
> esa primera vez; luego funciona offline).

---

## 1. Arquitectura objetivo

```
┌─────────────────────── App (nuestra APK) ────────────────────────┐
│                                                                   │
│  nativeLibraryDir/ (solo-lectura, EJECUTABLE)                     │
│    ├─ libproot.so          ← binario proot (arm64)               │
│    └─ libproot_loader.so   ← loader que proot inyecta            │
│                                                                   │
│  assets/ (dentro de la APK)                                       │
│    └─ alpine-arm64.tar.gz  ← rootfs base mínimo (~3 MB)          │
│                                                                   │
│  filesDir/ (privado, escribible)                                  │
│    └─ rootfs/              ← Alpine extraído en 1er arranque      │
│         ├─ usr/bin/node    (instalado vía apk en 1er arranque)   │
│         ├─ usr/bin/ttyd                                           │
│         └─ ...  claude / codex / gemini (npm -g)                 │
│                                                                   │
│  Proceso:                                                         │
│    ForegroundService                                              │
│      └─ exec libproot.so  --rootfs=filesDir/rootfs  \             │
│                            ttyd -p 7681 -i 127.0.0.1 -W  \        │
│                            /bin/sh agent-session.sh claude        │
│            └─ ttyd escucha en 127.0.0.1:7681                      │
│                                                                   │
│  MainActivity → WebView → http://127.0.0.1:7681  (SIN CAMBIOS)   │
└───────────────────────────────────────────────────────────────────┘
```

**Lo que se reaprovecha:** `MainActivity`, WebView, `AgentSession`, la barra de
pestañas, la lógica de estados. La pantalla de error pasa a ser también la
pantalla de *aprovisionamiento* (progreso del primer arranque).

**Lo que se elimina:** `TermuxIntegration.kt` (el intent RUN_COMMAND) y toda la
dependencia de Termux/Termux:API. Los scripts `termux/*.sh` se adaptan para
correr **dentro del rootfs propio**, no en Termux.

---

## 2. Los tres problemas difíciles (y su solución)

### 2.1 Android bloquea ejecutar binarios desde la carpeta de datos (W^X)

Desde Android 10, una app con `targetSdk ≥ 29` **no puede ejecutar** binarios
ELF que estén en su carpeta escribible (`filesDir`). El rootfs (node, busybox,
ttyd…) vive justo ahí → los ejecutaría el kernel y SELinux lo deniega.

**Solución (la misma que usa Termux):** bajar el `targetSdk` de la app a **28**.
Con `targetSdk ≤ 28`, Android concede permiso de ejecución sobre los archivos de
datos de la app. `minSdk` se queda en 26.

- **Consecuencia:** la app **no se puede publicar en Google Play** (exige
  targetSdk alto). Pero ya la distribuimos por **sideload / APK**, así que no nos
  afecta. Es exactamente por esto que Termux es solo de F-Droid.
- `proot` y su `loader` NO están en `filesDir`: van en `nativeLibraryDir`
  (empaquetados como `lib*.so`), que siempre es ejecutable y de solo-lectura.

> Alternativa descartada: `nodejs-mobile` (Node como `.so` en-proceso). No sirve
> porque los CLIs lanzan subprocesos reales (git, ripgrep, sh) que también hay
> que ejecutar desde el rootfs → mismo problema. proot + rootfs lo resuelve de
> raíz.

### 2.2 proot necesita su *loader*

`proot` funciona con ptrace e inyecta un pequeño *loader* en cada proceso del
guest. Hay que empaquetar **ambos** (`libproot.so` + `libproot_loader.so`) y
apuntarle con la variable `PROOT_LOADER=<nativeLibraryDir>/libproot_loader.so`.
Si falta el loader o no casa la versión, proot no arranca. Riesgo real → se
valida en el Hito 1.

### 2.3 Red / DNS dentro del guest

Dentro de proot no hay `/etc/resolv.conf` válido. Antes de `apk add` hay que
escribir `nameserver 8.8.8.8` (o el del sistema) en el rootfs. Trivial, pero si
se olvida, el primer arranque falla "sin internet" aunque haya wifi.

### 2.4 PTY / interactividad

`ttyd` crea su propio PTY (forkpty) dentro del guest. Bajo proot suele funcionar
mapeando `/dev`. Riesgo bajo-medio; se confirma en el Hito 3 (que la terminal
acepte input y muestre colores/TUI).

---

## 3. Tamaño y rendimiento (expectativas honestas)

| Concepto | Estimación |
|---|---|
| APK (proot + loader + Alpine base) | **~15–25 MB** |
| Descarga en 1er arranque (node, npm, ttyd, deps) | ~40–80 MB |
| Instalación de los 3 CLIs vía `npm -g` | **~150–400 MB** en disco (árboles npm grandes) |
| Espacio total en el dispositivo tras provisionar | ~250–500 MB |
| Overhead de proot en ejecución | Notable en I/O de disco, aceptable para CLIs interactivos |

- Solo **arm64-v8a** (el Poco X6 Pro lo es). No se empaqueta x86/armv7.
- El primer arranque puede tardar **varios minutos** (descarga + npm). Necesita
  una pantalla de progreso decente.

---

## 4. Riesgos y mitigaciones

| Riesgo | Prob. | Mitigación |
|---|---|---|
| proot/loader arm64 incompatible con el kernel del dispositivo | Media | Usar builds probados (proot-distro / Termux). Validar en Hito 1 antes de seguir. |
| targetSdk 28 rechazado por alguna tienda / advertencias | Baja | Distribución por sideload asumida desde el inicio. |
| npm install de los CLIs muy pesado o falla | Media | Instalación best-effort por CLI; reintentos; permitir instalar solo el que se use. |
| PTY/TUI no renderiza bien bajo proot | Baja-Media | Confirmar en Hito 3; fallback a `bash` si un CLI no arranca. |
| Android mata el ForegroundService (ttyd) en segundo plano | Media | Foreground service + `wake-lock`; reconexión del WebView en `onResume`. |
| Primer arranque lento percibido como "colgado" | Alta | Pantalla de progreso con logs en vivo del aprovisionamiento. |

---

## 5. Plan por hitos

Cada hito es verificable en el dispositivo antes de pasar al siguiente.

- **M0 — Pivote de configuración.**
  `targetSdk = 28`, `ndk { abiFilters "arm64-v8a" }`, esqueleto de
  `ForegroundService` + notificación. Criterio: la app sigue compilando e
  instalando; se ve la pantalla actual.

- **M1 — proot + rootfs vivo.**
  Empaquetar `libproot.so` + `libproot_loader.so` en `jniLibs`. Extraer el
  Alpine base de `assets` a `filesDir/rootfs` en el primer arranque. Wrapper
  Kotlin/JNI para lanzar proot. **Criterio:** ejecutar
  `proot … /bin/busybox uname -a` y ver `Linux … aarch64` en la UI/logcat.

- **M2 — Node dentro del guest.**
  Escribir `resolv.conf`; `apk update && apk add nodejs npm`. **Criterio:**
  `node -v` responde dentro de proot.

- **M3 — ttyd auto-arrancado + terminal real.**
  `apk add ttyd`; lanzar ttyd en `127.0.0.1:7681` desde el ForegroundService;
  el WebView existente conecta. **Criterio:** terminal interactiva (shell)
  dentro de la app, **sin Termux**, aceptando input y colores.

- **M4 — Los CLIs y las 3 sesiones.**
  `npm i -g` claude/codex/gemini; adaptar `agent-session.sh` dentro del rootfs;
  cada pestaña/puerto lanza su CLI. Pantalla de aprovisionamiento con progreso.
  **Criterio:** las 3 pestañas abren su CLI (o shell si aún no autenticado).

- **M5 — Robustez.**
  Wake-lock, reconexión, manejo de errores, re-aprovisionar con un toque,
  persistencia del rootfs entre actualizaciones de la app.

---

## 6. Cambios de código previstos (resumen)

| Archivo | Acción |
|---|---|
| `app/build.gradle.kts` | `targetSdk 28`, `abiFilters arm64-v8a`, `sourceSets jniLibs` |
| `AndroidManifest.xml` | Quitar permiso `RUN_COMMAND`; añadir `FOREGROUND_SERVICE`, `WAKE_LOCK` |
| `TermuxIntegration.kt` | **Eliminar** |
| `RootfsManager.kt` (nuevo) | Extraer/gestionar el rootfs, escribir resolv.conf |
| `ProotRunner.kt` (nuevo) | Construir la línea de comandos de proot y ejecutar |
| `LinuxService.kt` (nuevo) | ForegroundService que mantiene ttyd vivo |
| `ProvisionActivity`/estado | Primer arranque con progreso (reemplaza el uso actual de la pantalla de error) |
| `termux/*.sh` | Renombrar/adaptar a `rootfs/*.sh` (corren dentro del guest) |
| `jniLibs/arm64-v8a/` | `libproot.so`, `libproot_loader.so` |
| `assets/` | `alpine-arm64.tar.gz` |

---

## 7. Decisión pendiente antes de empezar M0

- **¿De dónde saco `proot`/loader y el rootfs Alpine?** Opciones: extraerlos de
  los paquetes de Termux (`proot`, `proot-distro`) o de builds estáticos
  públicos. Hay que descargar esos artefactos una vez y meterlos al repo. Lo
  confirmamos al arrancar M1.
