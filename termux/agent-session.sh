#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
#  agent-session.sh  —  Lanza el CLI del agente indicado
# =============================================================================
#
#  ttyd ejecuta este script como el "comando" de la terminal. Cada conexión
#  del WebView abre una shell que corre esto, iniciando el CLI correspondiente.
#
#  Uso (lo llama agent-bootstrap.sh, no tú directamente):
#      agent-session.sh <agente>
#
#  Si el CLI termina, caemos a una shell interactiva para no perder la sesión.
# =============================================================================

AGENT="${1:-claude}"

echo "== Agent Terminal :: sesión '$AGENT' =="
echo

case "$AGENT" in
  claude)
    # Claude Code CLI.
    exec claude
    ;;
  codex)
    # OpenAI Codex CLI.
    exec codex
    ;;
  gemini)
    # Google Gemini CLI.
    exec gemini
    ;;
  *)
    echo "Agente desconocido: '$AGENT'. Abriendo shell."
    ;;
esac

# Fallback: si el CLI no existe o termina, dejamos una shell viva.
echo
echo "(El CLI terminó o no está instalado. Shell interactiva:)"
exec bash -l
