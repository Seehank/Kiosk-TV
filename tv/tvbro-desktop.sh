#!/bin/zsh
# NOTE: not verified to change the layout; not called by provision.sh.
# Sets TV Bro's "Site view" for merk.bannet.sk to Desktop, so the menu pages use their desktop layout.
# Drives TV Bro's own menus with taps (1280x720 screen, density 213: the same on every UnionTV).
# TV Bro keeps this per site, so it is done once per TV.   Usage: ./tvbro-desktop.sh [tv-ip]
cd "${0:A:h}"
[[ -n "$1" ]] && export TV_IP=$1
A=./adb.sh
URL=${URL:-https://merk.bannet.sk/tv-menu/}
$A shell "am force-stop com.phlox.tvwebbrowser"
$A shell "am start -a android.intent.action.VIEW -d $URL -p com.phlox.tvwebbrowser" >/dev/null
$A shell "sleep 14; input keyevent BACK; sleep 2; input tap 396 52; sleep 2; input tap 577 359; sleep 2; input tap 357 327; sleep 2; input keyevent BACK; sleep 1"
$A shell "am force-stop com.phlox.tvwebbrowser"
echo "TV Bro: Site view for merk.bannet.sk set to Desktop (check once on the first TV: open the menu in TV Bro)."
