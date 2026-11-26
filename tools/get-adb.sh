#!/bin/zsh
# Makes sure Google's Android platform-tools (adb) for macOS are in tools/platform-tools.
# If adb is already here, nothing is downloaded. If it is missing, the newest one is downloaded.
# ./get-adb.sh --update  also checks Google for a newer one (downloads only if newer).
set -e
DIR=${0:A:h}
if [[ -x $DIR/platform-tools/adb && "$1" != --update ]]; then
  "$DIR/platform-tools/adb" version | head -2
  exit 0
fi
ZIP=$DIR/platform-tools-latest-darwin.zip
URL=https://dl.google.com/android/repository/platform-tools-latest-darwin.zip
before=$(stat -f %m "$ZIP" 2>/dev/null || echo 0)
if [[ -f $ZIP ]]; then
  curl -sfL --max-time 120 -z "$ZIP" -o "$ZIP.part" "$URL" && [[ -s $ZIP.part ]] && mv "$ZIP.part" "$ZIP" || true
else
  curl -sfL --max-time 120 -o "$ZIP.part" "$URL" && mv "$ZIP.part" "$ZIP"
fi
after=$(stat -f %m "$ZIP" 2>/dev/null || echo 0)
if [[ ! -x $DIR/platform-tools/adb || $after != $before ]]; then
  unzip -qo "$ZIP" -d "$DIR"
fi
"$DIR/platform-tools/adb" version | head -2
