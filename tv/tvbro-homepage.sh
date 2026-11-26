#!/bin/zsh
# Sets TV Bro's home page to the menu landing page by going through TV Bro's own settings on the TV:
# More actions → Settings → Home page → Custom → address. Buttons are found by their names on screen
# (uiautomator), not by fixed positions. Runs by itself, no person needed. Takes about a minute.
#   ./tvbro-homepage.sh <tv-ip> [address]
cd "${0:A:h}"
export TV_IP=${1:?usage: ./tvbro-homepage.sh <tv-ip> [address]}
URL=${2:-https://merk.bannet.sk/tv-menu/}
A=./adb.sh
BRO=com.phlox.tvwebbrowser

# The screen reader of uiautomator does not work while another accessibility service runs: pause the helper.
saved=$($A shell "settings get secure enabled_accessibility_services" | tr -d '\r')
$A shell "settings put secure enabled_accessibility_services ''" >/dev/null
restore() { [[ -n $saved && $saved != null ]] && $A shell "settings put secure enabled_accessibility_services $saved; settings put secure accessibility_enabled 1" >/dev/null; }
trap restore EXIT

# Center of the first element whose resource-id, text or description matches ($1 = id|text|desc, $2 = value).
find() {
  $A shell "for t in 1 2 3; do rm -f /data/local/tmp/ui.xml; uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1 && break; sleep 1; done; cat /data/local/tmp/ui.xml 2>/dev/null" | python3 -c '
import re, sys
kind, want = sys.argv[1], sys.argv[2]
for n in re.findall(r"<node [^>]*>", sys.stdin.read()):
    a = dict(re.findall(r"([\w-]+)=\"([^\"]*)\"", n))
    v = {"id": a.get("resource-id", "").split("/")[-1], "text": a.get("text", ""), "desc": a.get("content-desc", "")}[kind]
    if (v.startswith(want) if kind == "desc" else v == want):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", a["bounds"]))
        print((x1 + x2) // 2, (y1 + y2) // 2, a.get("text", "")); break' "$1" "$2"
}
# Wait (up to $3 s) for an element and tap it.
tap() {
  for i in $(seq 1 ${3:-15}); do
    pos=($(find $1 "$2"))
    if [[ -n $pos[1] ]]; then $A shell "input tap $pos[1] $pos[2]" >/dev/null; sleep 1.5; return 0; fi
    sleep 1
  done
  echo "TV Bro: '$2' not found on screen, home page not set." >&2; exit 1
}

echo "== TV Bro: setting the home page to $URL"
$A shell "am force-stop $BRO; am start -a android.intent.action.VIEW -d $URL -p $BRO" >/dev/null
sleep 12
$A shell "input keyevent BACK" >/dev/null; sleep 2           # shows TV Bro's toolbar
tap id ibMore 10                                             # ⋮ More actions
tap text Settings
tap desc "Home page:"
tap text Custom
tap id etValue                                               # the address field
# The kiosk app puts the address into the clipboard, then one Paste fills the field (typing loses letters).
$A shell "am broadcast -n sk.merk.kiosk/.Clip --es text '$URL'" | grep -q 'data="ok"' || { echo "   the kiosk could not set the clipboard (kiosk 1.16+ needed)" >&2; exit 1; }
typing="input keyevent KEYCODE_MOVE_END $(printf '67 %.0s' {1..80}); input keyevent KEYCODE_PASTE; sleep 1; "   # 67 = Delete, all in one command
if [[ -n $STOP_BEFORE_ENTER ]]; then          # testing: paste, then stop and show where we are
  $A shell "$typing" >/dev/null
  $A shell "screencap -p /data/local/tmp/s.png" >/dev/null
  echo "   stopped before Enter (screenshot /data/local/tmp/s.png)"; exit 0
fi
# Confirm with the on-screen keyboard's own Enter key: its focus starts on the settings key (bottom right),
# the Enter key is right above it. (A plain Enter key event moved the selection to the item below.)
$A shell "$typing input keyevent DPAD_UP; sleep 0.5; input keyevent DPAD_CENTER" >/dev/null
sleep 1
got=($(find id etValue)); got=$got[3]
$A shell "input keyevent BACK; sleep 1; input keyevent BACK; am force-stop $BRO" >/dev/null
if [[ $got == $URL ]]; then echo "   done: TV Bro opens $URL"; else echo "   check: TV Bro shows '$got'" >&2; exit 1; fi
