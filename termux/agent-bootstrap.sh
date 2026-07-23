#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
#  agent-bootstrap.sh  —  Levanta un ttyd para un agente en un puerto dado
# =============================================================================
#
#  Uso:
#      ~/agent-bootstrap.sh <agente> <puerto>
#
#      agente : claude | codex | gemini   (se pasa a agent-session.sh)
#      puerto : puerto local donde escuchará ttyd (debe coincidir con
#               AgentSession.port en la app: 7681/7682/7683)
#
#  Este es el script que dispara la app vía Termux:API RUN_COMMAND, pero
#  también se puede correr a mano desde Termux.
#
#  Es idempotente: si ya hay un ttyd escuchando en ese puerto, no arranca otro.
# =============================================================================

set -euo pipefail

AGENT="${1:-claude}"
PORT="${2:-7681}"

SESSION_SCRIPT="$HOME/agent-session.sh"

log() { printf '\033[1;36m[bootstrap]\033[0m %s\n' "$*"; }

# ¿Ya hay algo escuchando en ese puerto? Evitamos duplicados.
if command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | grep -q ":$PORT "; then
  log "Ya hay un servidor escuchando en el puerto $PORT. No arranco otro."
  exit 0
fi

if [ ! -x "$SESSION_SCRIPT" ]; then
  log "ERROR: no encuentro $SESSION_SCRIPT (ejecutable). Corre setup.sh primero."
  exit 1
fi

log "Arrancando ttyd para '$AGENT' en http://127.0.0.1:$PORT ..."

# Opciones de ttyd:
#   -p PORT           puerto de escucha
#   -i 127.0.0.1      escuchar SOLO en localhost (no exponer a la red)
#   -W                permitir escritura del cliente (terminal interactiva)
#   -t ...            opciones del frontend xterm.js (fuente/scrollback)
# El último argumento es el comando a ejecutar: nuestro wrapper por agente.
exec ttyd \
  -p "$PORT" \
  -i 127.0.0.1 \
  -W \
  -t fontSize=15 \
  -t 'theme={"background":"#1e1e2e"}' \
  -t scrollback=5000 \
  "$SESSION_SCRIPT" "$AGENT"
