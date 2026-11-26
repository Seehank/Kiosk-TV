#!/bin/zsh
# Runs ADB commands on the TV with the bundled pure-Python client (tools/tvadb.py), no Google adb needed.
#   TV_IP=<address> ./adb.sh shell "<command>" | install <apk> | shot <png> | keys KEY ...
export TV_IP=${TV_IP:?set TV_IP or pass the TV address}
ROOT=${0:A:h:h}
[[ -x $ROOT/.venv/bin/python ]] || { python3 -m venv $ROOT/.venv && $ROOT/.venv/bin/pip install -q -r $ROOT/tools/requirements.txt; } >&2
exec $ROOT/.venv/bin/python $ROOT/tools/tvadb.py "$@"
