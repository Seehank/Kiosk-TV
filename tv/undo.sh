#!/bin/zsh
# Puts the TV back to its own home screen. The switched-off apps (bloatware) STAY OFF unless you add --apps.
#   ./undo.sh [tv-ip]          Google home back, kiosk no longer home, sleep/screensaver as before
#   ./undo.sh --apps           ... and switch every app from debloat-list.txt back on
#   ./undo.sh --remove-app     ... and uninstall the kiosk app and TV Bro (ads and telemetry stay off)
cd "${0:A:h}"
[[ -n "$1" && "$1" != --* ]] && { export TV_IP=$1; shift; }
A=./adb.sh
ST=state; [[ -n "$TV_IP" && -d state/$TV_IP ]] && ST=state/$TV_IP
# Ads and telemetry stay off even with --apps (the "obvious bloatware").
KEEP_OFF=" com.tcl.bi com.tcl.common.terminalmanagerplus com.tcl.tvweishi com.tcl.esticker com.google.android.feedback com.google.android.tv.bugreportsender com.google.android.tvrecommendations com.google.android.leanbacklauncher.recommendations com.seraphic.openinet.pre "
if [[ " $* " == *" --apps "* ]]; then
  for p in $(grep -v '^#' debloat-list.txt | grep -v '^$'); do
    [[ "$KEEP_OFF" == *" $p "* ]] && continue
    grep -qx "package:$p" $ST/disabled-before.txt 2>/dev/null && continue  # was off before us
    $A shell "pm enable $p" >/dev/null 2>&1 && echo "  on: $p"
  done
fi
if [[ -f $ST/settings-before.txt ]]; then
  s=("${(@f)$(cat $ST/settings-before.txt)}")
  $A shell "settings put secure screensaver_enabled ${s[1]}"
  [[ ${s[2]} != null ]] && $A shell "settings put secure sleep_timeout ${s[2]}"
  if [[ ${s[3]} == null || ${s[3]} == 2147483647 ]]; then $A shell "settings delete system screen_off_timeout"; else $A shell "settings put system screen_off_timeout ${s[3]}"; fi
fi
$A shell "am broadcast -n 'sk.merk.kiosk/.Admin\$Release'" >/dev/null 2>&1 || true   # gives up device owner, if set
$A shell "settings put secure enabled_accessibility_services ''" >/dev/null
$A shell "pm enable com.google.android.tvlauncher" >/dev/null
$A shell "cmd package set-home-activity com.google.android.tvlauncher/.MainActivity"
$A shell "wm density reset" >/dev/null
if [[ " $* " == *" --remove-app "* ]]; then
  $A shell "pm uninstall sk.merk.kiosk; pm uninstall com.phlox.tvwebbrowser"
fi
$A shell "rm -f /data/local/tmp/ui.xml /data/local/tmp/s.png; rm -rf /data/local/tmp/tvhome" >/dev/null 2>&1   # files left by the installer
$A shell "input keyevent HOME"
echo "Back to the TV's own home screen."
echo "Still on (switch off by hand if wanted): Developer options and USB debugging; ads and telemetry apps stay off."
