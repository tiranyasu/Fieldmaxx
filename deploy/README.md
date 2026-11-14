# Running the Fieldmaxx server

This guide covers renting a server, setting Fieldmaxx up on it with one command, publishing app updates to the crew's
phones, keeping backups, and restoring them. You don't need to be a developer. Wherever you see `<something>` in
angle brackets, put in your own value, without the brackets. For example, `<your-domain>` could become
`fieldmaxx.example.com`.

Commands go in a terminal. On a Mac, open the Terminal app; on Windows, open PowerShell. "Run on the server" means after
you have signed in to the server with `ssh` (section 2, step 1). The commands assume you sign in as `root`, as most
providers set it up; if you sign in as another user, put `sudo` in front of each command you run on the server.

## 1. What server to rent

Fieldmaxx keeps a copy of every photo for at least 3 days, plus small thumbnails of all of them. For 4 techs that is
about 5 GB a day, so the server needs disk space and a fast connection. Rent a virtual private server (VPS) with:

- a **US-West** region (close to the crew);
- **Ubuntu 24.04 LTS** (22.04 works too);
- **2–4 GB of memory**;
- **80 GB of disk or more** (or a smaller disk plus an attached volume, see below);
- a **1 Gbps or faster** network port;
- **2 TB a month or more** of data transfer.

DigitalOcean, Hetzner and Vultr all have plans like this for roughly $12–24 a month. When you create the server, the
provider shows its **IP address** (four numbers like `203.0.113.10`) and gives you a root password or lets you add an
SSH key. Write down the IP address.

### A web address

The phones reach the server through a web address with HTTPS. You can use either of these:

- **A domain you own** (for example `fieldmaxx.example.com`). In your domain provider's settings, add an "A record"
  for it that points to the server's IP address. It can take up to an hour to start working.
- **A free address that works right away:** your IP address followed by `.sslip.io`, for example
  `203.0.113.10.sslip.io`. If you ever move to a new server, this address changes with the IP address.

The HTTPS certificate is created and renewed automatically. There is nothing to buy or renew yourself.

## 2. Setting up the server

You do this once. You can also run it again later to update the server; see "Everyday tasks".

1. Sign in to the server. In the terminal, run:

       ssh root@<server-ip>

   The first time, type `yes` when it asks whether to trust the server, then enter the root password if it asks.

2. Run on the server to get Fieldmaxx (your developer gives you the `<repository-url>`):

       apt-get update && apt-get install -y git
       git clone <repository-url> /opt/fieldmaxx

   If the repository is private, GitHub asks for your username and, instead of a password, a personal access token.

3. Run the setup:

       cd /opt/fieldmaxx
       bash deploy/setup.sh

   It asks for:
   - **the web address** (press Enter to accept the `.sslip.io` address it suggests, or type your domain);
   - **the admin username, your name and a password** (at least 8 characters). The password is not shown while you
     type, and it is never saved anywhere in readable form.

   Then it installs Docker, builds and starts Fieldmaxx (5 to 10 minutes the first time), creates your admin
   account, makes a first database backup and schedules the nightly backup. At the end it shows the server address.

4. Open `https://<your-domain>/` in a browser. You should see the Fieldmaxx download page. It says
   "No version published yet" until you publish the app (section 3).

5. Sign in to the app with your admin account. Add the crew in **Menu > Admin > Users**.

**Running setup again is safe.** It keeps the data, keeps the address (press Enter), and doesn't create a second
admin. It says so when the admin already exists.

Besides SSH (which you sign in with), only ports 80 and 443 (the web) are open to the internet, both for Caddy, the
part that handles HTTPS. Fieldmaxx's own port and its database stay inside the server.

### Using an attached volume (optional)

If you added a separate disk (a "volume") for the data, do this before step 3. Mount the volume (your provider shows
how), then run on the server:

    echo "DATA_DIR=/mnt/<your-volume>/fieldmaxx" > /opt/fieldmaxx/.env

Setup then keeps everything on the volume instead of in `/var/lib/fieldmaxx`. Wherever this guide says
`/var/lib/fieldmaxx`, use your folder instead.

## 3. Publishing an app update

The phones install Fieldmaxx from your server's download page, and they update from it too. You publish a version
from the computer that builds the app. That computer needs the Fieldmaxx folder, Java 21 and the Android SDK (on
Linux, `tools/android-sdk.sh` installs the SDK; elsewhere, Android Studio does), and it must be able to `ssh` to the
server. On Windows, run the commands below in WSL or Git Bash.

**The first time only**, in the Fieldmaxx folder on that computer, create the release key:

    tools/make-release-key.sh fieldmaxx-release.keystore

It writes `fieldmaxx-release.keystore` (the key) and `keystore.properties` (its password) into the Fieldmaxx folder.
**Back them up now: see section 4.** The script refuses to overwrite an existing key, so you can't replace it by
accident.

**For every release**, in the Fieldmaxx folder on that computer, run:

    tools/release.sh <version-code> <version-name> root@<your-domain>

For example: `tools/release.sh 2 1.1 root@<your-domain>`.

- The **version code** is a whole number. It must be higher than the last one you published (1, 2, 3 and so on),
  because the phones only update to a higher number. Note down each one you use. If you repeat one, the server
  refuses it and nothing changes.
- The **version name** is what people see, for example `1.1`.

The script checks that it can reach the server and builds the signed app (a few minutes). Then it uploads and
publishes it. The download page offers it right away, and the phones show "Update available — tap to install" the
next time Fieldmaxx starts (or within a day). Updating keeps everything on the phone. The app it builds already knows
your server's address, so the crew only types their username and password.

To install on a new phone, open `https://<your-domain>/` in the phone's browser, tap **Download Fieldmaxx for
Android**, and follow the three steps on the page.

Tip: to stop `ssh` from asking for the server password, add your SSH key to the server once with
`ssh-copy-id root@<your-domain>`, or in your provider's dashboard.

## 4. Back up the release key

> **This is the most important backup.** Every update of the app must be signed with the same release key. If you
> lose it, you can't publish updates the phones will accept. Every phone would then have to uninstall Fieldmaxx and
> install it again. Uninstalling deletes everything on the phone that hasn't reached OneDrive yet.

The key is two files in the Fieldmaxx folder on the computer that builds the app:

- `fieldmaxx-release.keystore`
- `keystore.properties`

Right after creating them, copy **both** to **two** safe places that are not that computer. For example, use a USB
stick kept somewhere safe, and a password manager or private cloud storage that can hold files. Keep them private:
don't email them or share them. Git ignores both files, so they never go into the repository.

To build releases on another computer, put both files into that computer's Fieldmaxx folder.

## 5. Backups and restoring

### What the server keeps

Everything is in one folder on the server, `/var/lib/fieldmaxx` (the "data folder"):

| In the data folder | What it is |
| --- | --- |
| `fieldmaxx.db` | The database: users, jobs, points, pole data, the record of every photo, settings |
| `backups/` | The nightly database backups |
| `photos/` | Photo backups: the server's copies of the crew's photos, kept at least 3 days |
| `thumbs/` | Small thumbnails of all photos, kept for good |
| `jobs/` | The imported job files (PDF and Word) |
| `apk/` | The app versions you published |

### The nightly backup

Every night at 10:17 server time, the server saves a copy of the database in `/var/lib/fieldmaxx/backups/`. The
file names show the date and time, for example `fieldmaxx-20261114-101700.db`. The newest 14 are kept. Most servers
run on UTC, so 10:17 server time is 2:17 AM in California (3:17 AM in summer). The copy is safe to make while
Fieldmaxx is running. Each run writes one line to `/var/log/fieldmaxx-backup.log`. To make a backup right now, run
on the server:

    bash /opt/fieldmaxx/deploy/backup.sh

**The nightly backup is the database only.** The photo backups (`photos/`) and thumbnails (`thumbs/`) live in the
data folder too, but they are **not** in the nightly backup. Also, the nightly backups sit on the same server, so if
you lose the server you lose them too.

### What to keep a copy of, away from the server

To survive losing the server completely, keep copies somewhere else of:

1. **The data folder**, `/var/lib/fieldmaxx`. Copy it to your computer now and then. In a terminal on your
   computer, run:

       scp -r root@<your-domain>:/var/lib/fieldmaxx ./fieldmaxx-data-<today's-date>

   With the photo backups it can be 15 GB or more. If that is too much to copy often, copy at least the small
   `backups` folder every week:

       scp -r root@<your-domain>:/var/lib/fieldmaxx/backups ./fieldmaxx-backups-<today's-date>

2. **The release key and `keystore.properties`.** They are on the computer that builds the app, not on the server
   (see section 4).

### Restoring a database backup on the same server

This puts the database back as it was when that backup was made. Anything changed after that is lost from the
server, so pick the newest backup that is good. Run on the server, putting in the backup's file name:

    cd /opt/fieldmaxx
    docker compose stop app
    keep=/var/lib/fieldmaxx/before-restore-$(date +%Y%m%d-%H%M%S)
    mkdir "$keep"
    mv /var/lib/fieldmaxx/fieldmaxx.db* "$keep"/
    cp /var/lib/fieldmaxx/backups/<backup-file> /var/lib/fieldmaxx/fieldmaxx.db
    docker compose start app

The `mv` line moves the current database aside (all of its files, including `fieldmaxx.db-wal`, which must not stay
next to the restored one) instead of deleting it. When you are sure it isn't needed, delete the `before-restore-…`
folder.

### Moving to a new server, or after losing the old one

1. Rent the new server (section 1). If you use your own domain, point it at the new IP address. Then the phones
   carry on as before.
2. Copy your newest saved data folder onto the new server, before running setup there. In a terminal on your
   computer, run:

       scp -r ./fieldmaxx-data-<date> root@<new-server-ip>:/var/lib/fieldmaxx

   If the old server still works, first run `bash /opt/fieldmaxx/deploy/backup.sh` on it and then copy, so the
   newest backup is from right now.

3. Sign in to the new server. In `/var/lib/fieldmaxx`, replace the database with the newest nightly copy, so it is
   surely complete:

       cd /var/lib/fieldmaxx
       rm -f fieldmaxx.db fieldmaxx.db-wal fieldmaxx.db-shm
       cp backups/<newest-backup-file> fieldmaxx.db

4. Get Fieldmaxx and run the setup, as in section 2, steps 2 and 3. Setup finds your admin in the database and
   doesn't ask for a new one.

If the web address changed (for example a new `.sslip.io` address), the crew has to sign out and sign in again with
the new address. Publish the next version with the new address (section 3).

## 6. Adding an ArcGIS key later

The map imagery comes from Esri's ArcGIS servers and works without a key. Esri expects business users to have an
ArcGIS account, and you can add your account's key at any time without updating the app:

1. In your ArcGIS account, create an API key that is allowed to use basemaps (imagery).
2. In the app, signed in as an admin, open **Menu > Admin settings**.
3. Under **Map**, paste the key into **Imagery key (optional)**, then tap **Save**.

The phones use it the next time they load the map settings. To go back to no key, empty the field and tap Save.

## Everyday tasks

Run these on the server, in `/opt/fieldmaxx` (`cd /opt/fieldmaxx` first):

| To | Run |
| --- | --- |
| See whether Fieldmaxx is running | `docker compose ps` |
| See the latest server log | `docker compose logs --tail 100 app` |
| Restart Fieldmaxx | `docker compose restart` |
| Update the server to a new version of the code | `git pull && bash deploy/setup.sh`, then press Enter at each question |
| Install Ubuntu's security updates | `apt-get update && apt-get upgrade`. Reboot if it asks; Fieldmaxx starts again by itself |

If something goes wrong:

- **The web address doesn't open, or the browser warns about the certificate.** The address doesn't point to the
  server yet (setup warns about this). Fix the A record and wait; HTTPS starts by itself. Caddy's log shows what it is
  trying: `docker compose logs --tail 50 caddy`.
- **The download page says "No version published yet".** Publish the app (section 3).
- **Setup stopped with a message.** Fix what it says and run it again; that is always safe. If it shows a server log
  you don't understand, send it to your developer.
