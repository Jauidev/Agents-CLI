#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
#  setup.sh  —  Prepara el entorno de Termux para Agent Terminal (v0)
# =============================================================================
#
#  Se ejecuta UNA vez dentro de Termux (no forma parte del APK):
#
#      bash setup.sh
#
#  Qué hace:
#    1. Actualiza paquetes e instala Node.js si falta.
#    2. Instala ttyd (servidor de terminal por WebSocket) vía pkg.
#    3. Instala los CLIs de agentes vía npm (claude-code, codex, gemini).
#    4. Copia agent-bootstrap.sh y agent-session.sh a ~ y los hace ejecutables.
#    5. Recuerda habilitar allow-external-apps para Termux:API.
#
#  Idempotente: se puede correr varias veces sin romper nada.
# =============================================================================

set -euo pipefail

log()  { printf '\033[1;32m[setup]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[setup]\033[0m %s\n' "$*"; }

# --- 1. Paquetes base --------------------------------------------------------
log "Actualizando índices de paquetes..."
pkg update -y && pkg upgrade -y

need_pkg() {
  # Instala un paquete solo si el comando no existe.
  local cmd="$1" pkgname="${2:-$1}"
  if command -v "$cmd" >/dev/null 2>&1; then
    log "$cmd ya está instalado."
  else
    log "Instalando $pkgname..."
    pkg install -y "$pkgname"
  fi
}

need_pkg node nodejs         # Node.js + npm
need_pkg ttyd ttyd           # Servidor de terminal por WebSocket
need_pkg git git             # Muchos CLIs lo necesitan

# --- 2. CLIs de agentes (vía npm global) -------------------------------------
# NOTA: los nombres de paquete pueden cambiar; ajusta según la doc oficial de
# cada CLI. Se instalan best-effort: si uno falla, seguimos con los demás.
install_npm() {
  local bin="$1" pkgspec="$2"
  if command -v "$bin" >/dev/null 2>&1; then
    log "$bin ya está instalado."
  else
    log "Instalando $pkgspec (npm -g)..."
    npm install -g "$pkgspec" || warn "No se pudo instalar $pkgspec (revísalo a mano)."
  fi
}

install_npm claude "@anthropic-ai/claude-code"
install_npm codex  "@openai/codex"
install_npm gemini "@google/gemini-cli"

# --- 3. Copiar scripts de arranque a ~ ---------------------------------------
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
for f in agent-bootstrap.sh agent-session.sh; do
  if [ -f "$SCRIPT_DIR/$f" ]; then
    cp "$SCRIPT_DIR/$f" "$HOME/$f"
    chmod +x "$HOME/$f"
    log "Instalado ~/$f"
  else
    warn "No encontré $f junto a setup.sh; cópialo manualmente a ~."
  fi
done

# --- 4. Recordatorio Termux:API ----------------------------------------------
PROP="$HOME/.termux/termux.properties"
if ! grep -qs '^allow-external-apps *= *true' "$PROP" 2>/dev/null; then
  warn "Para que la app pueda lanzar comandos vía Termux:API, añade a"
  warn "  $PROP :"
  warn "      allow-external-apps = true"
  warn "y luego ejecuta: termux-reload-settings"
fi

log "Listo. Arranca una sesión con, por ejemplo:"
log "    ~/agent-bootstrap.sh claude 7681"
