#!/bin/zsh
# Runs ADB commands on the TV.   TV_IP=<address> ./adb.sh shell "<command>" | install <apk> | shot <png> | keys KEY ...
# Uses Google's adb from tools/platform-tools (tools/get-adb.sh downloads the newest one). If it is missing,
# or macOS blocks it from the local network, it falls back to the bundled pure-Python client (tools/tvadb.py).
export TV_IP=${TV_IP:?set TV_IP or pass the TV address}
ROOT=${0:A:h:h}
ADB=$ROOT/tools/platform-tools/adb
DEV=$TV_IP:${ADB_PORT:-5555}

python_client() {
  [[ -x $ROOT/.venv/bin/python ]] || { python3 -m venv $ROOT/.venv && $ROOT/.venv/bin/pip install -q -r $ROOT/tools/requirements.txt; } >&2
  exec $ROOT/.venv/bin/python $ROOT/tools/tvadb.py "$@"
}

if [[ -x $ADB && -z $USE_PYTHON_ADB ]]; then
  if ! $ADB devices | grep -q "^${DEV}[[:space:]]*device"; then
    for attempt in 1 2; do
      $ADB connect $DEV >/dev/null 2>&1
      for i in {1..20}; do $ADB devices | grep -q "^${DEV}[[:space:]]*device" && break 2; sleep 0.5; done
      $ADB kill-server >/dev/null 2>&1; $ADB start-server >/dev/null 2>&1   # an older adb server may be in the way
    done
  fi
  if $ADB devices | grep -q "^${DEV}[[:space:]]*device"; then
    cmd=$1; shift
    case $cmd in
      ping)    echo "connected to $DEV" ;;
      shell)   exec $ADB -s $DEV shell "$@" ;;
      install) exec $ADB -s $DEV install -r "$@" ;;
      shot)    exec $ADB -s $DEV exec-out screencap -p > "$1" ;;
      keys)    for k in "$@"; do $ADB -s $DEV shell input keyevent "$k"; sleep 0.4; done ;;
      *)       echo "adb.sh: unknown command $cmd" >&2; exit 2 ;;
    esac
    exit 0
  fi
  echo "adb.sh: Google adb cannot reach $DEV (TV off, not allowed yet, or macOS Local Network blocked): using the Python client" >&2
fi
python_client "$@"
