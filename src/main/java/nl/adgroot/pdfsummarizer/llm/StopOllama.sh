#!/usr/bin/env bash
set -euo pipefail

# StopOllama.sh (macOS/Linux)
# - Kills Ollama processes (TERM -> KILL)
# - Optional pkill sweep
# - Stops brew service (macOS)
# - Closes ONLY terminal windows whose title contains "ollama@"

STOP_TIMEOUT="${STOP_TIMEOUT:-3}"
AGGRESSIVE="${AGGRESSIVE:-1}"
CLOSE_TERMINALS="${CLOSE_TERMINALS:-1}"

print_ollama_ps() {
  echo "ps -e | grep -i ollama:"
  ps -e -o pid=,comm=,args= | grep -i ollama || true
}

ollama_pids() {
  if command -v pgrep >/dev/null 2>&1; then
    pgrep -fi '(^|/)(ollama|Ollama)(\s|$)' || true
  else
    ps -e -o pid=,comm=,args= \
      | grep -Ei '(^|/)(ollama|Ollama)(\s|$)' \
      | grep -v grep \
      | awk '{print $1}' || true
  fi
}

kill_pids() {
  local sig="$1"
  local pids
  pids="$(ollama_pids | tr '\n' ' ')"

  if [[ -z "${pids// }" ]]; then
    echo "No Ollama PIDs found."
    return
  fi

  echo "kill -$sig $pids"
  # shellcheck disable=SC2086
  kill "-$sig" $pids 2>/dev/null || true
}

stop_brew_service_if_present() {
  if command -v brew >/dev/null 2>&1; then
    # Only attempt if 'brew services' works and ollama exists
    if brew services list >/dev/null 2>&1; then
      if brew services list | grep -qi '^ollama'; then
        if brew services list | grep -qi '^ollama.*started'; then
          echo "brew services stop ollama"
          brew services stop ollama || true
        fi
      fi
    fi
  fi
}

close_macos_terminals() {
  echo "Closing macOS Terminal windows with title containing 'ollama@'..."
  osascript <<'OSA'
tell application "Terminal"
  repeat with w in windows
    try
      if name of w contains "ollama@" then
        close w
      end if
    end try
  end repeat
end tell
OSA
}

close_linux_terminals() {
  echo "Closing Linux terminal windows with title containing 'ollama@'..."
  if command -v wmctrl >/dev/null 2>&1; then
    wmctrl -l | grep "ollama@" | awk '{print $1}' | while read -r id; do
      wmctrl -ic "$id" || true
    done
  else
    echo "wmctrl not found; skipping window close (install wmctrl if you want this on Linux)."
  fi
}

echo "Stopping Ollama..."
print_ollama_ps

if [[ -n "$(ollama_pids)" ]]; then
  echo "Attempting graceful stop (TERM)..."
  kill_pids TERM
  sleep "$STOP_TIMEOUT"
fi

if [[ -n "$(ollama_pids)" ]]; then
  echo "Still running -> kill -9 ..."
  kill_pids KILL
  sleep 1
fi

if [[ "$AGGRESSIVE" == "1" ]] && command -v pkill >/dev/null 2>&1; then
  echo "Aggressive sweep: pkill -f ollama"
  pkill -f 'ollama' 2>/dev/null || true
  pkill -f 'Ollama' 2>/dev/null || true
  sleep 1
fi

stop_brew_service_if_present

if [[ "$CLOSE_TERMINALS" == "1" ]]; then
  if [[ "${OSTYPE:-}" == "darwin"* ]]; then
    close_macos_terminals
  elif [[ "${OSTYPE:-}" == "linux-gnu"* ]] || [[ "${OSTYPE:-}" == "linux"* ]]; then
    close_linux_terminals
  fi
fi

echo "Stop complete."