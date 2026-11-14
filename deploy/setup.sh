#!/usr/bin/env bash
# Sets up the Fieldmaxx server on Ubuntu, or updates it, with one command. From the Fieldmaxx folder on the server:
#   sudo bash deploy/setup.sh
# It installs Docker, asks for the server's web address and the first admin, starts Fieldmaxx behind Caddy (which gets
# the HTTPS certificate by itself) and schedules the nightly database backup. Running it again is safe, for example
# after updating the code: it keeps the data, the address (press Enter) and the admin. The admin's password is never
# shown, written to a file or logged.
if [ -z "${BASH_VERSION:-}" ]; then # started with sh: stop before any bash-only line
  echo "Run it with bash: sudo bash deploy/setup.sh" >&2
  exit 1
fi
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ENV_FILE="$ROOT/.env"
DEFAULT_DATA_DIR=/var/lib/fieldmaxx
CRON_FILE=/etc/cron.d/fieldmaxx-backup
BACKUP_LOG=/var/log/fieldmaxx-backup.log
BACKUP_SCHEDULE="17 10 * * *" # 10:17 server time (UTC on most servers), about 2 or 3 AM in California
SWAP_FILE=/swapfile
FSTAB=/etc/fstab
MEMINFO=/proc/meminfo
OS_RELEASE=/etc/os-release
SERVER_START_SECONDS=180
SAFE_PATH='^/[A-Za-z0-9._/-]+$' # paths that cron and the scripts can use without quoting trouble

ANSWER=""
DOMAIN=""
PASSWORD=""
APT_UPDATED=""

say() { printf '%s\n' "$*"; }
step() { printf '\n==> %s\n' "$*"; }
warn() { printf '\nWARNING: %s\n' "$*" >&2; }
die() {
  printf '\nSetup stopped: %s\n' "$*" >&2
  exit 1
}
compose() { docker compose --project-directory "$ROOT" -f "$ROOT/docker-compose.yml" "$@"; }

# The value of KEY in .env, or nothing.
env_value() {
  [ -f "$ENV_FILE" ] || return 0
  sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1
}

check_system() {
  [ "$(id -u)" -eq 0 ] || die "run it as root: sudo bash deploy/setup.sh"
  local os_id os_name
  os_id=$(. "$OS_RELEASE" 2>/dev/null && printf '%s' "${ID:-}") || true
  os_name=$(. "$OS_RELEASE" 2>/dev/null && printf '%s' "${PRETTY_NAME:-}") || true
  [ "$os_id" = ubuntu ] || die "this script is for Ubuntu, and this server runs ${os_name:-something else}"
  [ -f "$ROOT/docker-compose.yml" ] && [ -f "$ROOT/server/Dockerfile" ] || die "run it from a complete copy of the Fieldmaxx folder"
  [[ $ROOT =~ $SAFE_PATH ]] || die "move the Fieldmaxx folder to a path without spaces or special characters (it is $ROOT)"
}

# This server's own public IPv4 address, from its network settings (nothing is looked up outside); nothing if unsure.
public_ip() {
  local ip=""
  if command -v ip >/dev/null; then
    ip=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{ for (i = 1; i < NF; i++) if ($i == "src") { print $(i + 1); exit } }') || true
  fi
  [ -n "$ip" ] || ip=$(hostname -I 2>/dev/null | awk '{ print $1 }') || true
  case "$ip" in
    10.* | 127.* | 169.254.* | 192.168.* | 172.1[6-9].* | 172.2[0-9].* | 172.3[01].* | 100.6[4-9].* | 100.[7-9][0-9].* | 100.1[01][0-9].* | 100.12[0-7].*) return 0 ;;
  esac
  if [[ $ip =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then printf '%s' "$ip"; fi
}

# ask QUESTION [DEFAULT]: one line typed by the person, into ANSWER. Enter gives DEFAULT.
ask() {
  local prompt="$1: "
  [ -z "${2:-}" ] || prompt="$1 [$2]: "
  read -r -p "$prompt" ANSWER || die "the answers ended before setup was finished"
  [ -n "$ANSWER" ] || ANSWER=${2:-}
}

# "https://Fieldmaxx.Example.com/" becomes "fieldmaxx.example.com".
normalize_domain() {
  local d
  d=$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')
  d=${d#http://}
  d=${d#https://}
  printf '%s' "${d%%/*}"
}

# A name Caddy can get a certificate for: dotted, letters/digits/hyphens, and not a bare IP address.
valid_domain() {
  local d=$1
  [ ${#d} -le 253 ] || return 1
  [[ $d =~ ^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$ ]] || return 1
  [[ ${d##*.} =~ [a-z] ]]
}

# Sets DOMAIN: the address in .env (Enter keeps it), else the person's answer, suggesting <ip>.sslip.io.
ask_domain() {
  local ip=$1 current suggestion example
  current=$(env_value DOMAIN)
  example=${ip:-203.0.113.10}
  suggestion=${current:-${ip:+$ip.sslip.io}}
  say ""
  say "Fieldmaxx needs a web address that leads to this server. Use a domain you own (point it at this server's IP"
  say "address), or the free address <server IP>.sslip.io, which works right away, like $example.sslip.io."
  while :; do
    ask "Web address for Fieldmaxx" "$suggestion"
    DOMAIN=$(normalize_domain "$ANSWER")
    valid_domain "$DOMAIN" && return 0
    say "'$ANSWER' is not a web address like fieldmaxx.example.com or $example.sslip.io. Try again."
  done
}

# Sets PASSWORD: typed twice, at least 8 characters, never shown on the screen.
read_password() {
  local again
  while :; do
    IFS= read -r -s -p "Password (at least 8 characters, not shown as you type): " PASSWORD || die "the answers ended before setup was finished"
    printf '\n' >&2
    if [ ${#PASSWORD} -lt 8 ]; then
      say "That is too short: use at least 8 characters."
      continue
    fi
    IFS= read -r -s -p "Type the password again: " again || die "the answers ended before setup was finished"
    printf '\n' >&2
    [ "$PASSWORD" = "$again" ] && return 0
    say "The two passwords are not the same. Try again."
  done
}

# The username of the first admin in the database, or nothing.
existing_admin() {
  sqlite3 -readonly -cmd ".timeout 5000" "$1" "SELECT username FROM users WHERE role = 'admin' ORDER BY created_at LIMIT 1;" ||
    die "could not read $1 to look for the admin"
}

# apt_install PACKAGE...: installs without questions (the package lists are refreshed once per run).
apt_install() {
  if [ -z "$APT_UPDATED" ]; then
    DEBIAN_FRONTEND=noninteractive apt-get update -qq
    APT_UPDATED=yes
  fi
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "$@"
}

# Docker and Docker Compose from Docker's own package source, as Docker's install guide for Ubuntu does it.
install_docker() {
  if docker compose version >/dev/null 2>&1; then
    say "Docker is already installed."
    return 0
  fi
  if command -v docker >/dev/null; then
    die "Docker is installed but without Docker Compose. Install the docker-compose-plugin package, then run setup again."
  fi
  local codename arch
  codename=$(. "$OS_RELEASE" && printf '%s' "${UBUNTU_CODENAME:-${VERSION_CODENAME:-}}")
  arch=$(dpkg --print-architecture)
  apt_install ca-certificates curl
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  printf 'deb [arch=%s signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu %s stable\n' \
    "$arch" "$codename" > /etc/apt/sources.list.d/docker.list
  APT_UPDATED="" # the new package source needs a refresh
  apt_install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  systemctl enable --now docker
}

# sqlite3 for the backups and cron to run them every night.
install_backup_tools() {
  local missing=()
  command -v sqlite3 >/dev/null || missing+=(sqlite3)
  command -v cron >/dev/null || missing+=(cron)
  if [ ${#missing[@]} -gt 0 ]; then apt_install "${missing[@]}"; else say "sqlite3 and cron are already installed."; fi
  systemctl enable --now cron >/dev/null 2>&1 || warn "could not start cron, so the nightly backup may not run"
}

# A 2 GB swap file on a server with less than 3 GB of memory, so building the server can't run out of memory.
add_swap_if_small() {
  local mem_kb
  mem_kb=$(awk '/^MemTotal:/ { print $2 }' "$MEMINFO")
  [ -n "$mem_kb" ] && [ "$mem_kb" -lt 3000000 ] || return 0
  [ -z "$(swapon --show --noheadings 2>/dev/null)" ] || return 0
  [ ! -e "$SWAP_FILE" ] || return 0
  say "This server has less than 3 GB of memory, so setup adds a 2 GB swap file ($SWAP_FILE)."
  if dd if=/dev/zero of="$SWAP_FILE" bs=1M count=2048 status=none && chmod 600 "$SWAP_FILE" &&
    mkswap "$SWAP_FILE" >/dev/null && swapon "$SWAP_FILE"; then
    grep -q "^$SWAP_FILE[[:space:]]" "$FSTAB" || printf '%s none swap sw 0 0\n' "$SWAP_FILE" >> "$FSTAB"
  else
    rm -f "$SWAP_FILE"
    warn "could not add the swap file; on a server this small the build may run out of memory"
  fi
}

# .env for docker compose: private (600), replaced in one step, keeping any other lines someone added.
write_env() {
  local tmp
  tmp=$(mktemp "$ENV_FILE.XXXXXX") # created with mode 600
  {
    printf '# Written by deploy/setup.sh: run it again to change these.\n'
    printf 'DOMAIN=%s\nDATA_DIR=%s\n' "$1" "$2"
    if [ -f "$ENV_FILE" ]; then grep -v -E '^(DOMAIN|DATA_DIR)=|^# Written by deploy/setup.sh' "$ENV_FILE" || true; fi
  } > "$tmp"
  chmod 600 "$tmp"
  mv -f "$tmp" "$ENV_FILE"
}

# Only warns: Caddy keeps trying for the certificate and HTTPS starts by itself once the address leads here.
check_dns() {
  local domain=$1 ip=$2 resolved target="this server's IP address"
  [ -z "$ip" ] || target=$ip
  resolved=$(getent ahostsv4 "$domain" 2>/dev/null | awk 'NR == 1 { print $1 }') || true
  if [ -z "$resolved" ]; then
    warn "$domain does not point to this server yet. Point it at $target; HTTPS starts working by itself once it does."
  elif [ -n "$ip" ] && [ "$resolved" != "$ip" ]; then
    warn "$domain does not point to this server yet: it points to $resolved, and this server is $ip. HTTPS starts working by itself once it does."
  fi
}

# Until the app answers on its port inside its container: by then it has set up its database.
wait_for_server() {
  local waited=0
  until compose exec -T app bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' >/dev/null 2>&1; do
    if [ "$waited" -ge "$SERVER_START_SECONDS" ]; then
      compose logs --tail 40 app >&2 || true
      die "the server did not start within $SERVER_START_SECONDS seconds; the end of its log is above"
    fi
    sleep 2
    waited=$((waited + 2))
  done
}

# create_admin USERNAME "DISPLAY NAME": the password goes in on stdin, never in a command line, a file or a log.
create_admin() {
  local out
  if out=$(printf '%s\n' "$PASSWORD" | compose exec -T app sh -c \
    'IFS= read -r FMX_ADMIN_PASSWORD; export FMX_ADMIN_PASSWORD; exec java -jar /app/fieldmaxx.jar create-admin "$1" "$2"' \
    create-admin "$1" "$2" 2>&1); then
    say "$out"
  else
    say "$out" >&2
    die "the admin was not created (the reason is above); run setup again to try again"
  fi
}

write_cron() {
  local tmp
  mkdir -p "$(dirname "$CRON_FILE")"
  tmp=$(mktemp "$CRON_FILE.XXXXXX") # cron skips file names with a dot, so it never reads this half-written
  {
    printf '# Fieldmaxx: the nightly database backup (written by deploy/setup.sh)\n'
    printf 'SHELL=/bin/sh\nPATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n'
    printf "%s root /bin/bash '%s' '%s' >> '%s' 2>&1\n" "$BACKUP_SCHEDULE" "$ROOT/deploy/backup.sh" "$1" "$BACKUP_LOG"
  } > "$tmp"
  chmod 644 "$tmp"
  mv -f "$tmp" "$CRON_FILE"
}

main() {
  check_system
  say "Fieldmaxx server setup"
  say "This installs Docker, starts Fieldmaxx with automatic HTTPS and schedules a nightly database backup."
  say "Running it again later is safe: the data, the address and the admin stay as they are."

  local data_dir ip admin="" username="" display_name=""
  data_dir=$(env_value DATA_DIR)
  data_dir=${data_dir:-$DEFAULT_DATA_DIR}
  [[ $data_dir =~ $SAFE_PATH ]] || die "DATA_DIR in .env must be a full path without spaces or special characters (it is $data_dir)"
  ip=$(public_ip)
  ask_domain "$ip"

  if [ -s "$data_dir/fieldmaxx.db" ]; then
    command -v sqlite3 >/dev/null || apt_install sqlite3
    admin=$(existing_admin "$data_dir/fieldmaxx.db")
  fi
  if [ -n "$admin" ]; then
    say "An admin already exists ($admin), so setup does not create another. Add the crew in the app: Menu > Admin > Users."
  else
    say ""
    say "Now the first admin account (you). You sign in to the app with it and add the crew's accounts there."
    while :; do
      ask "Admin username"
      username=$ANSWER
      [ -n "$username" ] && break
      say "Please type a username."
    done
    while :; do
      ask "Your name as the crew sees it (for example Jane Smith)"
      display_name=$ANSWER
      [ -n "$display_name" ] && break
      say "Please type a name."
    done
    read_password
  fi

  step "Installing Docker"
  install_docker
  step "Installing the backup tools"
  install_backup_tools
  add_swap_if_small

  step "Saving the settings in $ENV_FILE"
  write_env "$DOMAIN" "$data_dir"
  mkdir -p "$data_dir"
  check_dns "$DOMAIN" "$ip"

  step "Building and starting Fieldmaxx (the first time takes 5 to 10 minutes)"
  compose up -d --build || die "building or starting Fieldmaxx did not work (the reason is above)"
  wait_for_server

  if [ -z "$admin" ]; then
    step "Creating the admin"
    create_admin "$username" "$display_name"
    PASSWORD=""
    admin=$username
  fi

  step "Scheduling the nightly database backup"
  write_cron "$data_dir"
  bash "$ROOT/deploy/backup.sh" "$data_dir" || warn "the first backup did not work (the reason is above); the nightly one will try again"

  cat <<EOF

Fieldmaxx is running.
  Server address (the app's sign-in screen):  https://$DOMAIN
  Download page for the crew's phones:        https://$DOMAIN/
  Admin:                                      $admin
  Data folder:                                $data_dir
  Nightly database backup:                    $data_dir/backups (14 kept), log in $BACKUP_LOG

Next, publish the app from the computer that builds it with tools/release.sh (see deploy/README.md).
EOF
}

# Run only when started as a script, so tests can load the functions.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
