#!/usr/bin/env bash
# Builds the signed release APK and publishes it on the server: the download page offers it, and the phones' update
# check finds it (when Fieldmaxx starts, and once a day).
#   tools/release.sh <versionCode> <versionName> <server ssh>
#   for example: tools/release.sh 2 1.1 root@fieldmaxx.example.com
# versionCode is a whole number, higher than the last release's (phones only update to a higher one); versionName is
# what people see. It needs the release key (tools/make-release-key.sh, once) and ssh access to the server that
# deploy/setup.sh set up. The app it builds starts with that server's address on its sign-in screen.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
CONTAINER=fieldmaxx-app # container_name in docker-compose.yml

die() {
  echo "release: $*" >&2
  exit 1
}

[ $# -eq 3 ] || die "usage: tools/release.sh <versionCode> <versionName> <server ssh>   for example: tools/release.sh 2 1.1 root@fieldmaxx.example.com"
code=$1
name=$2
server=$3
[[ $code =~ ^[1-9][0-9]{0,9}$ ]] && [ "$code" -le 2100000000 ] ||
  die "versionCode must be a whole number from 1 to 2100000000, higher than the last release's (was '$code')"
[[ $name =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$ ]] ||
  die "versionName may use letters, digits, '.', '_' and '-' (up to 40), like 1.2.0 (was '$name')"
case "$server" in
  "" | -*) die "the server must be an ssh address like root@fieldmaxx.example.com (was '$server')" ;;
esac
[ -f "$ROOT/keystore.properties" ] ||
  die "there is no release key here: run tools/make-release-key.sh once, or put back your backed-up key files"

echo "Checking Fieldmaxx on $server..."
origin=$(ssh -n -- "$server" "docker exec $CONTAINER printenv FMX_PUBLIC_ORIGIN") ||
  die "could not reach Fieldmaxx on $server: check the address, that ssh can sign in there, and that the server runs (deploy/setup.sh)"
[[ $origin =~ ^https://[A-Za-z0-9.-]+(:[0-9]+)?$ ]] || die "the server reported an unexpected address: '$origin'"

echo "Building Fieldmaxx $name (version code $code) for $origin..."
rm -f "$APK" # never publish an APK left from an earlier build
(cd "$ROOT" && ./gradlew :app:assembleRelease -PversionCode="$code" -PversionName="$name" -Pfieldmaxx.serverUrl="$origin")
[ -f "$APK" ] || die "the build made no signed APK at $APK: check keystore.properties and the key file it names"

echo "Publishing Fieldmaxx $name on $server..."
# One connection: the APK goes in on stdin to a temporary file inside the app's container, publish-apk copies it into
# the data folder and records it, and the temporary file is removed whatever happens.
ssh -- "$server" "docker exec -i $CONTAINER sh -c 'f=\$(mktemp) && cat > \"\$f\" && java -jar /app/fieldmaxx.jar publish-apk \"\$f\" $code $name; s=\$?; rm -f \"\$f\"; exit \$s'" < "$APK" ||
  die "the server did not publish it (the reason is above)"

echo "Done. The download page ($origin/) offers $name now, and the phones find it the next time Fieldmaxx starts."
echo "The next release needs a version code higher than $code."
