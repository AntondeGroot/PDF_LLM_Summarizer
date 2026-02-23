#!/usr/bin/env bash
set -euo pipefail

# -------- resolve script directory --------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Project root = 7 levels up from this script:
# llm -> pdfsummarizer -> adgroot -> nl -> java -> main -> src
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../../../../../.." && pwd)"

DEFAULT_CONFIG="$PROJECT_ROOT/src/main/resources/config.json"

CONFIG_FILE="${CONFIG_FILE:-$DEFAULT_CONFIG}"
STOP_SCRIPT="${STOP_SCRIPT:-$SCRIPT_DIR/StopOllama.sh}"

HOST="${HOST:-127.0.0.1}"
BASE_PORT="${BASE_PORT:-11434}"
OLLAMA_BIN="${OLLAMA_BIN:-ollama}"

SERVERS="${SERVERS:-}"
NUM_PARALLEL_OVERRIDE="${OLLAMA_NUM_PARALLEL:-}"
MAX_LOADED_OVERRIDE="${OLLAMA_MAX_LOADED_MODELS:-}"

usage() {
  cat <<EOF
Usage:
  $(basename "$0") [-C config.json] [-S servers] [-H host] [-p base_port] [-P num_parallel] [-M max_loaded_models]

Defaults:
  -C ./config.json
  -H 127.0.0.1
  -p 11434

Config mapping:
  .ollama.servers         -> servers
  .ollama.concurrency     -> OLLAMA_NUM_PARALLEL
  len(.ollama.modelsPerServer) -> OLLAMA_MAX_LOADED_MODELS

Overrides:
  -S or env SERVERS
  -P or env OLLAMA_NUM_PARALLEL
  -M or env OLLAMA_MAX_LOADED_MODELS
EOF
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Error: required command not found: $1" >&2
    exit 1
  }
}

read_config_int() {
  local jq_expr="$1"
  jq -r "$jq_expr // empty" "$CONFIG_FILE"
}

read_config_len() {
  local jq_expr="$1"
  jq -r "($jq_expr // []) | length" "$CONFIG_FILE"
}

detect_terminal() {
  if command -v gnome-terminal >/dev/null 2>&1; then
    echo "gnome-terminal"
  elif command -v konsole >/dev/null 2>&1; then
    echo "konsole"
  elif command -v xfce4-terminal >/dev/null 2>&1; then
    echo "xfce4-terminal"
  elif command -v xterm >/dev/null 2>&1; then
    echo "xterm"
  elif [[ "${OSTYPE:-}" == "darwin"* ]]; then
    echo "macos-terminal"
  else
    echo ""
  fi
}

launch_terminal() {
  local title="$1"
  local cmd="$2"
  local term
  term="$(detect_terminal)"

  case "$term" in
    gnome-terminal)
      gnome-terminal --title="$title" -- bash -lc "$cmd"
      ;;
    konsole)
      konsole --new-tab -p tabtitle="$title" -e bash -lc "$cmd"
      ;;
    xfce4-terminal)
      xfce4-terminal --title="$title" --command "bash -lc '$cmd'"
      ;;
    xterm)
      xterm -T "$title" -e bash -lc "$cmd"
      ;;
    macos-terminal)
      osascript <<OSA
tell application "Terminal"
  activate
  do script "$cmd"
end tell
OSA
      ;;
    *)
      echo "No supported terminal found; running in background."
      bash -lc "$cmd" &
      ;;
  esac
}

start_servers() {
  local servers="$1"
  local num_parallel="$2"
  local max_loaded="$3"

  if ! [[ "$servers" =~ ^[0-9]+$ ]] || [[ "$servers" -lt 1 ]]; then
    echo "Error: servers must be a positive integer (got: $servers)" >&2
    exit 2
  fi

  echo
  echo "Starting $servers Ollama server(s)"
  echo "Host: $HOST"
  echo "Ports: $BASE_PORT..$((BASE_PORT + servers - 1))"
  echo "OLLAMA_NUM_PARALLEL=$num_parallel"
  echo "OLLAMA_MAX_LOADED_MODELS=$max_loaded"
  echo "Config: $CONFIG_FILE"
  echo

  for ((i=0; i<servers; i++)); do
    port=$((BASE_PORT + i))
    url="http://${HOST}:${port}"
    title="ollama@${HOST}:${port}"

    cmd="export OLLAMA_HOST='${url}'; \
export OLLAMA_NUM_PARALLEL='${num_parallel}'; \
export OLLAMA_MAX_LOADED_MODELS='${max_loaded}'; \
echo 'Starting ${title}'; \
exec '${OLLAMA_BIN}' serve"

    launch_terminal "$title" "$cmd"
  done

  echo "Servers started."
}

# ---------------- parse args ----------------
while getopts ":C:S:H:p:P:M:h" opt; do
  case "$opt" in
    C) CONFIG_FILE="$OPTARG" ;;
    S) SERVERS="$OPTARG" ;;
    H) HOST="$OPTARG" ;;
    p) BASE_PORT="$OPTARG" ;;
    P) NUM_PARALLEL_OVERRIDE="$OPTARG" ;;
    M) MAX_LOADED_OVERRIDE="$OPTARG" ;;
    h) usage; exit 0 ;;
    \?) usage; exit 2 ;;
    :)  echo "Missing argument for -$OPTARG" >&2; usage; exit 2 ;;
  esac
done

# ---------------- validation ----------------
require_cmd jq
require_cmd "$OLLAMA_BIN"

if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "Error: config file not found: $CONFIG_FILE" >&2
  exit 1
fi

if [[ ! -x "$STOP_SCRIPT" ]]; then
  echo "Error: Stop script not found or not executable: $STOP_SCRIPT" >&2
  echo "Run: chmod +x StopOllama.sh" >&2
  exit 1
fi

# ---------------- read config ----------------
config_servers="$(read_config_int '.ollama.servers')"
config_concurrency="$(read_config_int '.ollama.concurrency')"
config_models_len="$(read_config_len '.ollama.modelsPerServer')"

# Apply overrides (flags/env) with fallback to config, then fallback to sane defaults.
servers="${SERVERS:-${config_servers:-1}}"
num_parallel="${NUM_PARALLEL_OVERRIDE:-${config_concurrency:-1}}"
max_loaded="${MAX_LOADED_OVERRIDE:-${config_models_len:-2}}"

# If modelsPerServer is empty (len=0), keep a minimum of 1.
if [[ "$max_loaded" =~ ^[0-9]+$ ]] && [[ "$max_loaded" -lt 1 ]]; then
  max_loaded=1
fi

echo "Stopping Ollama via $STOP_SCRIPT ..."
"$STOP_SCRIPT"

echo "Waiting 2 seconds..."
sleep 2

start_servers "$servers" "$num_parallel" "$max_loaded"