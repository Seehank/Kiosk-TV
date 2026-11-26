#!/bin/zsh
# Builds "Merk Kiosk Installer.app" (double-click on the Mac) next to this repo, with the carrot icon.
# The app runs tv/installer.py from this folder and opens it in the browser; opened again, it just shows the window.
set -e
ROOT=${0:A:h:h}
APP="$ROOT/Merk Kiosk Installer.app"
TMP=$(mktemp -d)
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

# Icon: the carrot TV drawn from tools/icon.svg (Chrome renders it, iconutil packs it)
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
sed -e 's/width="432" height="432"/width="1024" height="1024"/' "$ROOT/tools/icon.svg" > "$TMP/icon.svg"
"$CHROME" --headless=new --disable-gpu --hide-scrollbars --window-size=1024,1024 \
  --screenshot="$TMP/icon.png" "file://$TMP/icon.svg" >/dev/null 2>&1
mkdir -p "$TMP/icon.iconset"
for s in 16 32 128 256 512; do
  sips -z $s $s "$TMP/icon.png" --out "$TMP/icon.iconset/icon_${s}x${s}.png" >/dev/null
  sips -z $((s*2)) $((s*2)) "$TMP/icon.png" --out "$TMP/icon.iconset/icon_${s}x${s}@2x.png" >/dev/null
done
iconutil -c icns "$TMP/icon.iconset" -o "$APP/Contents/Resources/icon.icns"

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>Merk Kiosk Installer</string>
  <key>CFBundleIdentifier</key><string>sk.merk.kiosk.installer</string>
  <key>CFBundleExecutable</key><string>launch</string>
  <key>CFBundleIconFile</key><string>icon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>LSUIElement</key><true/>
</dict></plist>
PLIST

cat > "$APP/Contents/MacOS/launch" <<LAUNCH
#!/bin/zsh
# Starts the installer (if it is not running yet) and opens its window in the browser.
ROOT="$ROOT"
if ! nc -z 127.0.0.1 8765 2>/dev/null; then
  [[ -x "\$ROOT/.venv/bin/python" ]] || { python3 -m venv "\$ROOT/.venv" && "\$ROOT/.venv/bin/pip" install -q -r "\$ROOT/tools/requirements.txt"; }
  cd "\$ROOT/tv" && nohup "\$ROOT/.venv/bin/python" installer.py --no-browser >"\$ROOT/tv/state/installer.log" 2>&1 &
  for i in {1..40}; do nc -z 127.0.0.1 8765 2>/dev/null && break; sleep 0.25; done
fi
open "http://127.0.0.1:8765/"
LAUNCH
chmod +x "$APP/Contents/MacOS/launch"
mkdir -p "$ROOT/tv/state"
echo "Built $APP"
