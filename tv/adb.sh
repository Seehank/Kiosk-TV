#!/bin/zsh
# Runs one ADB shell command on the TV through couch-tv's pure-Python client.
export TV_IP=${TV_IP:-192.168.50.6}
exec ~/Sites/couch-tv/.venv/bin/python ~/Sites/couch-tv/tv-home/tools/tvadb.py "$@"
