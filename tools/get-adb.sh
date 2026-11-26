#!/bin/zsh
# Downloads Google's newest Android platform-tools (adb) for macOS into tools/platform-tools.
# Only downloads when Google has a newer zip than the one here (If-Modified-Since), so it is cheap to run often.
set -e
DIR=${0:A:h}
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
