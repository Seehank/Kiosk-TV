#!/bin/zsh
# Adds Merk Kiosk and TV Bro to "Favourite apps" on the TV's own (Google) home screen, through the home
# screen itself: the "+" tile, then the app in the "Select app" list. Items are found by their names on
# screen (uiautomator), so it does not depend on positions. Needs the Google home switched on (run it before
# provision.sh switches it off). Runs by itself, no person needed.   ./favorites.sh <tv-ip>
cd "${0:A:h}"
export TV_IP=${1:?usage: ./favorites.sh <tv-ip>}
A=./adb.sh
WANT=("Merk Kiosk" "TV Bro")

# uiautomator cannot read the screen while another accessibility service runs: pause the helper meanwhile.
saved=$($A shell "settings get secure enabled_accessibility_services" | tr -d '\r')
$A shell "settings put secure enabled_accessibility_services ''" >/dev/null
restore() { [[ -n $saved && $saved != null ]] && $A shell "settings put secure enabled_accessibility_services $saved; settings put secure accessibility_enabled 1" >/dev/null; }
trap restore EXIT

screen() { $A shell "uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1; cat /data/local/tmp/ui.xml"; }
# Names in the Favourite apps row (tiles between the row title and the next row).
favourites() {
  screen | python3 -c '
import re, sys
nodes = [dict(re.findall(r"([\w-]+)=\"([^\"]*)\"", n)) for n in re.findall(r"<node [^>]*>", sys.stdin.read())]
row = False
for a in nodes:
    if a.get("resource-id", "").endswith("channel_title"):
        row = a.get("text") == "Favourite apps"; continue
    if row and a.get("resource-id", "").endswith("banner_image") and a.get("content-desc"):
        print(a["content-desc"])'
}
# Center of the first element with this text or description.
where() {
  screen | python3 -c '
import re, sys
want = sys.argv[1]
for n in re.findall(r"<node [^>]*>", sys.stdin.read()):
    a = dict(re.findall(r"([\w-]+)=\"([^\"]*)\"", n))
    if want in (a.get("text"), a.get("content-desc")):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", a["bounds"])); print((x1 + x2) // 2, (y1 + y2) // 2); break' "$1"
}
wait_for() { for i in $(seq 1 ${2:-15}); do pos=($(where "$1")); [[ -n $pos[1] ]] && return 0; sleep 1; done; return 1; }

echo "== Favourite apps on the TV's home screen: ${(j:, :)WANT}"
$A shell "am start -n com.google.android.tvlauncher/.MainActivity" >/dev/null; sleep 5
for app in $WANT; do
  if favourites | grep -qx "$app"; then echo "   already there: $app"; continue; fi
  wait_for "Add app to favourites" || { echo "   the + tile is not on screen" >&2; exit 1; }
  $A shell "input tap $pos[1] $pos[2]; sleep 1; input keyevent DPAD_CENTER" >/dev/null   # tap focuses it, OK opens the list
  wait_for "Select app" || { echo "   the app list did not open" >&2; exit 1; }
  pos=($(screen | python3 -c '
import re, sys
for n in re.findall(r"<node [^>]*>", sys.stdin.read()):
    a = dict(re.findall(r"([\w-]+)=\"([^\"]*)\"", n))
    if a.get("resource-id", "").endswith(":id/title") and a.get("text") == sys.argv[1]:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", a["bounds"])); print((x1 + x2) // 2, (y1 + y2) // 2); break' "$app"))
  [[ -n $pos[1] ]] || { echo "   $app is not in the Select app list" >&2; $A shell "input keyevent BACK" >/dev/null; continue; }
  $A shell "input tap $pos[1] $pos[2]" >/dev/null; sleep 3
done
got=$(favourites)
missing=0
for app in $WANT; do echo "$got" | grep -qx "$app" || { echo "   missing: $app" >&2; missing=1; }; done
[[ $missing == 0 ]] && echo "   done: $(echo $got | paste -sd, - | sed 's/,/, /g')"
exit $missing
