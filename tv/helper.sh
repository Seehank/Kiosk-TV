#!/bin/zsh
# Switches the kiosk's home watch ("pomocník") on or off from the computer.
#   ./helper.sh on [tv-ip]     the menu comes back to the front whenever the TV's own home opens
#   ./helper.sh off [tv-ip]    nothing jumps in; work in Settings or the browser (kiosk Back x5 does this for 30 min)
# Installing a new kiosk version can switch the service off: run "./helper.sh on" after ./adb.sh install.
cd "${0:A:h}"
[[ -n "$2" ]] && export TV_IP=$2
case "$1" in
  on)  ./adb.sh shell "settings put secure enabled_accessibility_services sk.merk.kiosk/sk.merk.kiosk.HomeWatch; settings put secure accessibility_enabled 1" ;;
  off) ./adb.sh shell "settings put secure enabled_accessibility_services ''" ;;
  *)   echo "usage: ./helper.sh on|off [tv-ip]"; exit 1 ;;
esac
./adb.sh shell "settings get secure enabled_accessibility_services"
