#!/usr/bin/env bash
# Nightly database backup. deploy/setup.sh schedules it every night; to run it by hand:
#   sudo bash deploy/backup.sh [data folder]
# The data folder defaults to DATA_DIR in the Fieldmaxx folder's .env (written by setup.sh), else /var/lib/fieldmaxx.
# sqlite3's .backup takes a consistent copy while the server keeps running. Copies go to <data folder>/backups
# (/data/backups inside the app container); the newest 14 are kept. This is the database only: photo backups and
# thumbnails stay in the data folder's photos/ and thumbs/ folders and are not in it (see deploy/README.md).
set -euo pipefail
export LC_ALL=C

KEEP=14

die() {
  printf '%s backup FAILED: %s\n' "$(date -u '+%Y-%m-%d %H:%M:%S')" "$*" >&2
  exit 1
}

command -v sqlite3 >/dev/null || die "sqlite3 is not installed (sudo apt-get install -y sqlite3)"
wanted=${1:-}
env_file="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.env"
if [ -z "$wanted" ] && [ -f "$env_file" ]; then wanted=$(sed -n 's/^DATA_DIR=//p' "$env_file" | tail -n 1); fi
wanted=${wanted:-/var/lib/fieldmaxx}
data_dir=$(cd "$wanted" 2>/dev/null && pwd) || die "no data folder at $wanted"
db="$data_dir/fieldmaxx.db"
[ -s "$db" ] || die "no database at $db"
backups="$data_dir/backups"
mkdir -p "$backups"

# One backup at a time, so a run by hand never races the nightly one.
exec 9> "$backups/.lock"
flock -n 9 || die "another backup is running"
rm -f "$backups"/.fieldmaxx-*.partial* # left behind by a run that was killed

name="fieldmaxx-$(date -u '+%Y%m%d-%H%M%S').db"
partial=".$name.partial"
[ ! -e "$backups/$name" ] || die "$backups/$name already exists"
trap 'rm -f "$backups/$partial" "$backups/$partial"-*' EXIT

# Run inside the backups folder, so the file name given to .backup has no characters that need quoting.
cd "$backups"
sqlite3 -cmd ".timeout 10000" "$db" ".backup $partial" || die "sqlite3 could not copy $db"
# A plain single file (no -wal companion) that opens anywhere, checked before it counts as a backup.
sqlite3 "$partial" "PRAGMA journal_mode = DELETE;" > /dev/null || die "could not finish the copy"
check=$(sqlite3 "$partial" "PRAGMA integrity_check;") || die "could not check the copy"
[ "$check" = "ok" ] || die "the copy failed its integrity check: $check"
mv "$partial" "$name"

# Keep the newest $KEEP. The names sort by time, oldest first.
shopt -s nullglob
copies=(fieldmaxx-*.db)
removed=0
while [ $((${#copies[@]} - removed)) -gt "$KEEP" ]; do
  rm -f -- "${copies[$removed]}"
  removed=$((removed + 1))
done

size=$(du -k "$name" | cut -f1)
printf '%s backed up the database to %s (%s KB); %s kept\n' \
  "$(date -u '+%Y-%m-%d %H:%M:%S')" "$backups/$name" "$size" "$((${#copies[@]} - removed))"
