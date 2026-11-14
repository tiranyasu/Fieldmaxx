#!/usr/bin/env bash
# Creates the app's release signing key. Run it once, ever:
#   tools/make-release-key.sh <out.keystore>        for example: tools/make-release-key.sh fieldmaxx-release.keystore
# It writes the key (RSA 4096, valid 30 years) to <out.keystore> and its generated password to keystore.properties in
# the Fieldmaxx folder, where the release build finds it. Both files are git-ignored, private, and never overwritten.
# Every update of the app must be signed with this same key, so back up both files: without them, every phone has to
# uninstall Fieldmaxx and install it again to get updates.
set -euo pipefail
export LC_ALL=C

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
PROPS="$ROOT/keystore.properties"
ALIAS=fieldmaxx
VALIDITY_DAYS=10958 # 30 years, leap days included

die() {
  echo "make-release-key: $*" >&2
  exit 1
}

# git would leave the file out of the repository (or this isn't a git checkout at all).
git_ignores() {
  command -v git > /dev/null || return 0
  git -C "$ROOT" rev-parse --is-inside-work-tree > /dev/null 2>&1 || return 0
  git -C "$ROOT" check-ignore -q -- "$1"
}

[ $# -eq 1 ] && [ -n "$1" ] || die "usage: tools/make-release-key.sh <out.keystore>   (for example fieldmaxx-release.keystore)"
case "$1" in
  *.keystore) ;;
  *) die "the key file name must end in .keystore, so git never picks it up: $1" ;;
esac
[ ! -e "$PROPS" ] || die "$PROPS already exists: this copy of Fieldmaxx already has a release key. Nothing was changed."
dir=$(cd "$(dirname "$1")" 2> /dev/null && pwd) || die "the folder for $1 does not exist"
store="$dir/$(basename "$1")"
[ ! -e "$store" ] || die "$store already exists. Nothing was changed."
case "$store" in
  *[![:print:]]*) die "use a path of plain letters and digits for the key file, which the build can read: $store" ;;
esac
git_ignores keystore.properties || die "git would not ignore keystore.properties, so the password could end up in the repository."

# Inside the Fieldmaxx folder the key is named by its path in it, so the pair keeps working wherever the folder is
# copied; outside, by its full path.
case "$store" in
  "$ROOT"/*)
    store_file=${store#"$ROOT"/}
    git_ignores "$store_file" || die "git would not ignore $store_file, so the key could end up in the repository. Nothing was changed."
    ;;
  *) store_file=$store ;;
esac

keytool=$(command -v keytool || true)
[ -n "$keytool" ] || keytool="${JAVA_HOME:-/nonexistent}/bin/keytool"
[ -x "$keytool" ] || die "keytool was not found: install Java 21 (it includes keytool) or set JAVA_HOME"

password=$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')
[ ${#password} -ge 30 ] || die "could not make a random password"

made_store=""
made_props=""
cleanup() { # on any failure, leave nothing half made
  local status=$?
  if [ "$status" -ne 0 ]; then
    [ -z "$made_store" ] || rm -f "$store"
    [ -z "$made_props" ] || rm -f "$PROPS"
  fi
}
trap cleanup EXIT
umask 077

# keytool reads the password from the environment, never from its command line (which other programs can see).
export FMX_RELEASE_KEY_PASSWORD="$password"
made_store=yes
"$keytool" -genkeypair -noprompt -keystore "$store" -storetype PKCS12 -storepass:env FMX_RELEASE_KEY_PASSWORD \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity "$VALIDITY_DAYS" -dname "CN=Fieldmaxx"
unset FMX_RELEASE_KEY_PASSWORD

(set -C && : > "$PROPS") 2> /dev/null || die "$PROPS appeared while the key was being made. Nothing was changed."
made_props=yes
{
  printf '# The Fieldmaxx release key, made by tools/make-release-key.sh. Keep this file and the key file private and\n'
  printf '# back up both together: every app update must be signed with this key. The release build reads this file.\n'
  printf 'storeFile=%s\n' "$(printf '%s' "$store_file" | sed 's/\\/\\\\/g')"
  printf 'storePassword=%s\n' "$password"
  printf 'keyAlias=%s\n' "$ALIAS"
  printf 'keyPassword=%s\n' "$password"
} > "$PROPS"

cat << EOF

The release key is ready:
  $store
  $PROPS

BACK UP BOTH FILES NOW, together, somewhere other than this computer (for example a USB stick kept somewhere safe, or
a password manager). Every app update must be signed with this key. If it is lost, every phone has to uninstall
Fieldmaxx and install it again to get updates.
EOF
