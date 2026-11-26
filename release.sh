#!/bin/zsh
# Builds a new kiosk version and prepares the two files the TVs read for self-update.
#   ./release.sh            bump versionCode, build, write dist/update.json + dist/merk-kiosk-<code>.apk
# Then upload both files to https://merk.bannet.sk/wp-content/uploads/kiosk/ (the TVs check every 6 h).
set -e
cd "${0:A:h}"
export JAVA_HOME=$PWD/.toolchain/jdk/Contents/Home
code=$(( $(grep versionCode version.properties | cut -d= -f2) + 1 ))
name=${1:-1.$((code-1))}
printf 'versionCode=%s\nversionName=%s\n' $code $name > version.properties
.toolchain/gradle-8.10.2/bin/gradle -q assembleRelease --console=plain
mkdir -p dist
apk=dist/merk-kiosk-$code.apk
cp app/build/outputs/apk/release/app-release.apk $apk
cp $apk dist/latest.apk   
sha=$(shasum -a 256 $apk | cut -d' ' -f1)
start=$(cat start-url.txt 2>/dev/null || true)
poll=$(cat poll-seconds.txt 2>/dev/null || echo 15)   # how often TVs look for page changes (15 to 600 s)
cat > dist/update.json <<JSON
{"versionCode": $code, "versionName": "$name", "apk": "https://merk.bannet.sk/wp-content/uploads/kiosk/merk-kiosk-$code.apk", "sha256": "$sha", "startUrl": "$start", "pollSeconds": $poll}
JSON
echo "Built $apk ($name, code $code). Upload it and dist/update.json to wp-content/uploads/kiosk/."
