#!/bin/zsh
# Installs the kiosk, makes it the home screen, switches off the listed apps and the sleep timers.
# Everything it does is undone by undo.sh. Before-state is saved to state/.
set -e
cd "${0:A:h}"
A=./adb.sh
mkdir -p state
APK=${APK:-../app/build/outputs/apk/release/app-release.apk}

echo "Saving the TV's current state…"
$A shell "pm list packages -d" > state/disabled-before.txt
$A shell "settings get secure screensaver_enabled; settings get secure sleep_timeout; settings get system screen_off_timeout" > state/settings-before.txt

echo "Installing the kiosk app…"
$A install "$APK"

echo "Making it the home screen…"
$A shell "cmd package set-home-activity sk.merk.kiosk/.MainActivity"
$A shell "appops set sk.merk.kiosk AUTO_START allow" || true
$A shell "appops set sk.merk.kiosk REQUEST_INSTALL_PACKAGES allow" || true

echo "Switching off apps…"
for p in $(grep -v '^#' debloat-list.txt | grep -v '^$'); do
  if $A shell "pm list packages $p" | grep -qx "package:$p"; then
    out=$($A shell "pm disable-user --user 0 $p" 2>&1 || true)
    echo "  $p: ${out:-done}"
  fi
done

echo "Kiosk settings: no screensaver, no sleep…"
$A shell "settings put secure screensaver_enabled 0"
$A shell "settings put secure sleep_timeout 0"
$A shell "settings put system screen_off_timeout 2147483647"

echo "Opening the kiosk…"
$A shell "am start -a android.intent.action.MAIN -c android.intent.category.HOME"
echo "Done."
