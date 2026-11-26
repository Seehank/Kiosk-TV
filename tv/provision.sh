#!/bin/zsh
# One command to turn a TCL Android TV into a Merk menu kiosk:
#   ./provision.sh <tv-ip>            (add --apk <file> to use a specific build)
#
# On the TV, once, with the remote:
#   Settings > Device Preferences > About > press "Build" 7 times      (Developer options on)
#   Settings > Device Preferences > Developer options > USB debugging ON
# The TV then asks "Allow USB debugging?" when this script connects: tick Always allow, press OK.
#
# What it does (everything is undone by ./undo.sh):
#   1. installs the kiosk app          2. switches on its home watch (Accessibility)
#   3. installs TV Bro as fallback browser with the menu as its home page, switches off the apps in debloat-list.txt (nothing is deleted)
#   4. no screensaver, no auto power off   5. disables the Google home, reboots, checks it
# After that the TV needs no more attention: it boots into the menu and asks for the screen once.
set -e
cd "${0:A:h}"
export TV_IP=${1:?usage: ./provision.sh <tv-ip> [--apk file]}
APK=../dist/latest.apk
if [[ "$2" == "--apk" ]]; then
  APK=$3
else
  # Always the newest version: the latest release on GitHub (public repo, plain HTTPS, no login needed),
  # else update.json on the WordPress server. Downloaded into dist/server and installed from there.
  echo "== Getting the newest kiosk version"
  mkdir -p ../dist/server
  if info=$(curl -sfL --max-time 20 -A "MerkKiosk-installer" https://github.com/Seehank/Kiosk-TV/releases/latest/download/update.json); then
    echo "   from GitHub (github.com/Seehank/Kiosk-TV)"
  else
    info=$(curl -sf --max-time 15 -A "MerkKiosk-installer" https://merk.bannet.sk/wp-content/uploads/kiosk/update.json) && echo "   from the WordPress server"
  fi
  if [[ -n "$info" ]]; then
    read -r url sha ver <<<"$(python3 -c 'import json,sys; d=json.loads(sys.argv[1]); print(d["apk"], d.get("sha256",""), d["versionName"])' "$info")"
    out=../dist/server/${url:t}
    if [[ ! -f $out ]] || [[ $(shasum -a 256 $out | cut -d' ' -f1) != $sha ]]; then
      curl -sfL --max-time 120 -A "MerkKiosk-installer" -o $out.part "$url" && mv $out.part $out
    fi
    if [[ -f $out && ( -z $sha || $(shasum -a 256 $out | cut -d' ' -f1) == $sha ) ]]; then
      APK=$out; echo "   version $ver from the server (checksum OK)"
    else
      echo "   download failed or checksum wrong: using the local copy $APK"
    fi
  else
    echo "   server not reachable: using the local copy $APK"
  fi
fi
[[ -f $APK ]] || APK=../app/build/outputs/apk/release/app-release.apk
A=./adb.sh
STATE=state/$TV_IP; mkdir -p $STATE

echo "== Connecting to $TV_IP (press Always allow on the TV if it asks)"
model=$($A shell "getprop ro.product.model" | tr -d '\r'); android=$($A shell "getprop ro.build.version.release" | tr -d '\r')
echo "$model, Android $android"
if [[ "$model" != UnionTV || "$android" != 9 ]] && [[ "$FORCE" != 1 ]]; then
  echo "STOP: this setup was made and tested on UnionTV with Android 9. Run with FORCE=1 to install anyway."
  exit 2
fi

echo "== Saving the TV's current state to $STATE"
$A shell "pm list packages -d" > $STATE/disabled-before.txt
$A shell "settings get secure screensaver_enabled; settings get secure sleep_timeout; settings get system screen_off_timeout; settings get secure enabled_accessibility_services" > $STATE/settings-before.txt

echo "== Installing the kiosk ($APK)"
$A install "$APK"

echo "== Installing the fallback browser (TV Bro, open source, GitHub release 3.0.7)"
$A install ../dist/tvbro-3.0.7.apk
./tvbro-homepage.sh $TV_IP || echo "   (TV Bro home page not set: set it by hand in TV Bro, Settings, Home page)"

echo "== Switching off listed apps"
for p in $(grep -v '^#' debloat-list.txt | grep -v '^$'); do
  if $A shell "pm list packages $p" | grep -qx "package:$p"; then
    $A shell "pm disable-user --user 0 $p" >/dev/null 2>&1 && echo "  off: $p"
  fi
done

echo "== Kiosk settings"
$A shell "wm density 106"   # about 1920 px wide like the other TVs (panel 1280x720 at 213); undo.sh resets it
$A shell "settings put secure screensaver_enabled 0"
$A shell "settings put secure sleep_timeout 0"
$A shell "settings put system screen_off_timeout 2147483647"
$A shell "cmd package set-home-activity sk.merk.kiosk/.MainActivity"

echo "== Letting the kiosk switch its home watch on by itself after updates"
$A shell "pm grant sk.merk.kiosk android.permission.WRITE_SECURE_SETTINGS"

echo "== Switching on the home watch (keeps the menu in front after power on)"
old=$(sed -n '4p' $STATE/settings-before.txt)
svc=sk.merk.kiosk/sk.merk.kiosk.HomeWatch
[[ "$old" == null || -z "$old" ]] && new=$svc || new="$old:$svc"
$A shell "settings put secure enabled_accessibility_services $new"
$A shell "settings put secure accessibility_enabled 1"

echo "== Switching off the Google home screen, rebooting"
$A shell "pm disable-user --user 0 com.google.android.tvlauncher"
$A shell "reboot" || true

echo "== Waiting for the TV to come back"
sleep 20
for i in $(seq 1 40); do nc -z -G 2 $TV_IP 5555 2>/dev/null && break; sleep 3; done
sleep 25
front=$($A shell "dumpsys activity activities | grep -m1 mResumedActivity" 2>/dev/null || true)
echo "$front"
if [[ "$front" == *sk.merk.kiosk* ]]; then echo "OK: $TV_IP boots into the menu."; else echo "CHECK: kiosk not in front yet. Press Home on the TV, or run ./undo.sh $TV_IP"; fi
