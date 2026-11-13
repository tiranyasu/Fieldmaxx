# Fieldmaxx: Field Survey App (design)

Date: 2026-11-13
Status: draft, awaiting owner approval

## 1. Intent and why

Fieldmaxx replaces **Cadastrus**, a web app the owner's crew currently uses for
AT&T fielding/surveying. The owner runs a crew of field techs. Each AT&T job
arrives as one or more **PDFs** (from SharePoint) listing points (poles,
pedestals, handholes, manholes, client queries…) with coordinates. The techs
walk the route, photograph each point, record pole details, and the photos must
land in the job's **Field Notes** folder in OneDrive/SharePoint, filed by
category and point.

Cadastrus does this, but it is unreliable, and that is the whole reason for this
project:

- photos are sometimes lost;
- the map zooms out on refresh;
- sometimes it breaks completely and a job PDF can't be uploaded;
- points are missed because it fails to turn "point number + coordinates" into
  a point on the map.

**Success** = the crew trusts it. No photo is ever lost without someone seeing
it, no point in a PDF is ever silently dropped, and the app doesn't fight them
(keeps their map view, keeps working on weak signal).

## 2. Users and roles

- **Admin** (the owner). Creates the logins and hands them out. No self-signup.
  Sees everything: all jobs, all techs, delivery health.
- **Tech**. Signs in with the username and password the admin gave them, works
  jobs, takes photos, records pole data.
- Several techs work **the same job at the same time**. Everyone sees each
  other's progress live and **everyone sees each other's GPS position**.

Primary device: Android phones (the owner uses Android). iPhone must also work.
The admin also works from a laptop browser at times.

## 3. Goals, non-goals, anti-goals

### Goals (first version)
1. **Reliable PDF import** of pole and query PDFs, with a check screen that
   accounts for every point found and every line it couldn't read.
2. **Map** with satellite imagery and all job points, type filters with
   progress, live crew positions.
3. **Point screen**: instructions, navigation, move point, GPS accuracy, pole
   data capture, comment, photos, close point; query points show the client's
   reference.
4. **Photo delivery that can't silently lose photos**: phone → server →
   OneDrive Field Notes folder, each hop keeping its copy until the next hop
   confirms, with visible status per photo.
5. **Crew**: admin-issued accounts, shared live progress, live GPS.
6. **Pole data export** as a spreadsheet for the OCALC work.

### Non-goals (later, not in v1)
These exist in Cadastrus's menu and are explicitly deferred: My pay, Markup for
the client, Coordinates from photos, Smart route, "On my way", rounds (the "+"
chip), map usage estimate, photo quality picker, Russian/Spanish UI,
Bug/Suggestion form, a native Android app. "Utilities, addresses & MDU" and
"Data collection" are covered only as far as the add-point categories below go.

### Anti-goals (would make this a failure even if it "works")
- A photo that disappears without a visible failure.
- A point in a PDF that never makes it onto the map without the importer saying so.
- The map resetting its view on refresh or reopen.
- Needing the company's IT department to approve anything before the crew can use it.
- An import that fails as a whole because of one bad line.

## 4. Approaches considered

1. **Installable web app + server, photos delivered through the job's Field
   Notes share link** (chosen). Works on Android and iPhone from the home
   screen, updates instantly, no app store, no IT approval. Trade-off: upload
   through an "anyone with the link" share isn't an official Microsoft API,
   so Microsoft can change it. Mitigated by the store-and-confirm relay (§6.6).
   A web app can't track GPS in the background, so a tech's dot only updates
   while the app is open.
2. **Same, but upload via official Microsoft sign-in (Graph API).** Most
   durable, but needs EES's IT to approve an app registration in their
   Microsoft 365. Not the owner's call; could take weeks or be refused.
   **Designed as a drop-in replacement** for the share-link uploader later.
3. **Native Android/iPhone app.** Background GPS and background uploads, but
   app-store/sideload distribution, $99/yr Apple account, slower to build and
   change.

**Owner's decision:** go with 1 now; possibly make it an **Android app later**.
So the frontend must be wrappable with Capacitor later (see §7.4).

## 5. How the owner's data looks today (evidence)

### 5.1 Job PDF (sample: `A05J9XE.pdf`)
- One page: a Google Earth screenshot of the route (image), plus two
  **FreeText annotations** typed by the AT&T designer on top of it:
  - Job notes: `NOTE FOR AERIAL PATH / 1. IDENTIFY THE FEASIBILITY OF ATTACHING
    FIBER ON POLES … 3. PLEASE PROVIDE THE OCALC OF ALL POLES …`
  - Point list. Exact raw annotation text (note the `\t\r` between label and
    coordinates on some lines, and the inconsistent spacing after commas):
    ```
    POLE 1 = \t\r39.63609452036168,-119.69104360818118\r
    POLE 2 = \t\r39.63610091735064,-119.68529727043486\r
    POLE 3 = 39.63612682461489,-119.68173217216633\r
    POLE 4 = 39.63614274884236,-119.67739817654694\r
    POLE 5 = \t\r39.6361531972, -119.675338878\r
    POLE 6 = 39.6361668347, -119.673274565\r
    POLE 7 = 39.63616773905345,-119.67284033048661
    ```
  - The page's own text layer is empty apart from what the annotations render.
    A reader that only looks at page text, or that renders the page and
    reads the wrapped layout, sees numbers split across lines
    (`39.63612682461489,-` / `119.68173217216633`).
- Expected import result for this file: **exactly 7 POLE points** plus the job
  notes. This file is the first fixture for the importer's tests.
- Producer is iLovePDF (the designer edits/annotates PDFs there), so other job
  PDFs may differ in layout. The importer must be tolerant (§6.2).

### 5.2 Query PDFs
The owner says query PDFs look "like the pole one". No sample yet. Cadastrus
shows query points named like `Query 1-43` with a description such as
`ped data . 39.48878665,-119.84297250`, and a "Client query · reference" panel
with reference pictures.

### 5.3 OneDrive Field Notes folder (from owner's screenshots)
```
…/NEVADA/F2_A05K51V/                  ← job folder (shared, "anyone" link: globe icon)
  Field Notes/                        ← the link the app is given per job
    Address Query/  Aerial Fielding/  CREEK IMAGE/  CULVERT/  Drainage/
    F3 BUILDING DATA/  Field Notes 3/  HH/  MAIL BOX IMAGES/  MDU/  MH/
    PED/  PFP/  POLE/  QUERY 1/  QUERY 2/  Query 3/  Rear images/  SAI/
    Street images/  UTILITIES/  VACANT LOT/
      MH/   MH_1/ MH_10/ MH_11/ … MH_19/ …          ← one folder per point
      POLE/ POLE_10B/ POLE_14b/ … POLE_3B/ …        ← "b" = poles added between
```
Category folder casing is inconsistent (`QUERY 1` vs `Query 3`). SharePoint
names are case-insensitive, so match existing folders case-insensitively and
create missing ones.

### 5.4 Cadastrus UI (from 7 saved page snapshots)
Dark UI (slate-900 `#0f172a` bars, blue `#2563eb` accents), Leaflet map, Esri
World Imagery + Esri World_Transportation label overlay, marker clustering.
The screens referenced in §6 mirror what was observed; Fieldmaxx keeps the
familiar layout so the crew doesn't have to relearn it.

## 6. What it does

### 6.1 Accounts and sign-in
- Admin creates users: display name, username, initial password. Admin can
  reset a password, disable a user, and promote another user to admin.
- Techs can change their own password.
- Sessions are long-lived (90 days, sliding) so techs aren't logged out in the field.
- First admin account is created by the setup script on the server.

### 6.2 Jobs and PDF import
**Creating a job.** "+ New job" → drop or pick one or more PDFs → the check
screen → paste the **Field Notes link** → save. Job name defaults to the first
PDF's file name (`A05J9XE.pdf` → `A05J9XE`), editable. More PDFs (e.g. a query
PDF that arrives later) can be added to an existing job through the same check
screen. Any signed-in user can create a job or add PDFs; only admins can delete
a job (owner's call to confirm; Cadastrus let techs create projects).

**Where the importer reads text from** (all of them, then de-duplicates):
1. Annotation contents (FreeText, Text/sticky notes, etc.).
2. Form field values (the sample has an AcroForm).
3. The page text layer.

**How it reads points.** Normalize text first: treat `\r`, `\n`, `\t` and runs
of spaces as separators; re-join a number split after `-` or `,` across a line
break. Then find:
- **Labelled points**: a label = a type word plus an id (`POLE 1`, `MH 10`,
  `HH 2`, `PED 3`, `POLE 14B`, `Query 1-43`), followed by `=`, `:`, `-` or
  nothing, then a coordinate pair.
- **Coordinate pairs**: decimal degrees with optional space after the comma,
  or separated by a space; also degrees-minutes-seconds (`39°38'09.9"N
  119°41'27.8"W`). Validate ranges (lat −90…90, lon −180…180).
- **Sanity checks** (flag, don't drop): lat/lon look swapped; the sign of
  longitude disagrees with the rest of the job; a point is more than ~50 km
  from the job's other points; a duplicate label with different coordinates.
- Any remaining text between labelled points becomes that point's
  **description**. A free-standing notes block (like "NOTE FOR AERIAL PATH")
  becomes the **job notes**.
- **Unlabelled coordinate pairs** become points named by layer + sequence and
  are flagged for review.
- A line that contains a type word or a number that looks like a coordinate
  but couldn't be parsed is listed as **"couldn't read"**. It is never
  silently ignored.

**Point type / layer.** Taken from the label's type word (POLE, MH…). For
query PDFs the layer name (`Query 1`, `Query 2`, `Address Query`) is chosen on
the check screen, defaulting from the label (`Query 1-43` → `Query 1`).

**Check screen** (the core of "no point silently missed"):
- "Found 7 POLE points" summary per type, a small map preview with all points,
  and a table: label, type, lat, lon, description, flags.
- A "Couldn't read" section showing the raw text of every unparsed line, with
  an inline editor to fix it into a point, or "ignore" explicitly.
- Every row is editable before saving.
- The importer never fails the whole file because of one bad line. If the
  PDF itself can't be opened, it says so plainly and nothing is saved.

**Reference pages.** Every PDF page is rendered to an image at import and kept
with the job. Each point links to the page(s) its text came from. For query
points this is shown as the **"Client query · reference"** panel; for poles it
is the route screenshot. If the query PDF sample shows a clearer per-item
image layout, attach the item's own image instead (see §9).

**Job info** shows the job notes, the source PDFs (downloadable), the Field
Notes link, and the crew working it.

### 6.3 Map screen
Layout follows Cadastrus:
- **Top bar**: job name; search; connection pill (online / offline / "3
  photos waiting"); menu ☰.
- **Chip row**: "Hide shot" toggle; "All (N)"; one chip per type with
  done/total and a progress underline (e.g. "✓ POLE (80/80)"); tapping a chip
  filters to that type.
- **Progress strip**: "Progress: 152/159 (96%)", **"Left: 7"** button (opens
  remaining points sorted by distance from you), sunset countdown ("☀️ 5h 40m
  until sunset", from device location and date), progress bar, per-type counts.
- **Map**: Esri World Imagery base + toggleable street-name overlay (Aa);
  house-number overlay (123), source left to the builder (§9); "center on me";
  zoom. Markers are circles with a short type code (P, MH, HH, PED, BU, Q…)
  coloured by type; shot points turn grey, faded and struck-through; a small
  badge marks points with photos not yet confirmed in OneDrive. Clustering at
  low zoom, off at high zoom.
- **Your position**: pulsing blue dot. **Crew**: one dot per other signed-in
  tech with their name and "2 min ago"; dots older than 10 min are faded,
  older than 1 h hidden.
- **"+" button** (bottom-left): add a point here (§6.5).
- **The map remembers its view** (centre + zoom) per user per job, saved on
  every move, restored on refresh/reopen. First open of a job fits all points.

### 6.4 Point screen (bottom sheet on tapping a marker)
- Header: point name; **Navigate** (opens Google Maps directions to the
  point); **Move point** (drag marker to the true location; the original
  coordinates are kept and the move is recorded with who/when); close.
- Instructions for the type (e.g. POLE: "Photograph the pole and the carrier
  equipment on it.") and the point's description from the PDF.
- **GPS accuracy** badge from the phone's reported accuracy ("GPS ±12 ft";
  green / amber / red).
- **Pole data** (POLE type, including added poles):
  - Line height (ft, decimal)
  - Anchor (toggle)
  - Self-supporting / Lashed (one or neither)
  - Tags (multi-select): N/A, Carrier label, Grounding, No ground, No fiber,
    Damaged cable
- **Client query · reference** panel for query points (§6.2).
- Comment (free text).
- **Take photo** (opens the phone camera) and **Pick from gallery**.
- Photo strip: thumbnails with per-photo status (§6.6).
- **Done** closes the point (requires ≥1 photo), or **Can't shoot** with a
  reason (Not found, No access, Not applicable, Other + text). Both count as
  complete for progress; "Can't shoot" points are listed for the admin.
  Closed points can be reopened.
- All edits save as you go (no separate "save" button) and sync to the crew.

### 6.5 Adding points
"+" → a crosshair at your GPS position (drag the map to adjust) → choose type
(the job's types plus the standard categories: POLE, PED, HH, MH, BU, SAI, PFP,
MDU, CULVERT, Drainage, CREEK IMAGE, MAIL BOX IMAGES, Street images, Rear
images, VACANT LOT, UTILITIES, Aerial Fielding, F3 BUILDING DATA) → name.
For POLE the name is suggested from the nearest existing pole number plus the
next free letter (`POLE_14B`), editable. Added points behave like imported
ones and are marked "added by <tech>". Menu → "Points I added" lists them.

### 6.6 Photos and delivery to OneDrive (the reliability core)
**On the phone:**
1. The photo file is written to on-device storage (IndexedDB) **before**
   anything else happens, with its point, job, tech and timestamp. The
   camera original is saved first; the smaller upload copy (see "Speed"
   below) is made from it afterwards, so a crash mid-resize loses nothing.
2. The app requests persistent storage so the browser won't evict it.
3. An upload queue sends it to the server in resumable chunks (weak signal
   friendly), retrying with backoff, resuming after app restarts.
4. The local copy is deleted only after the server reports the photo is
   **confirmed in OneDrive**, plus a 3-day grace period.

**On the server:**
5. Stores the file on disk, records it in the database, replies "received",
   and puts it on a durable delivery queue.
6. A delivery worker uploads it into the job's Field Notes folder:
   `<Category>/<PointFolder>/<FileName>`, creating folders as needed
   (category matched case-insensitively to an existing folder).
   - Point folder: `<TYPE>_<ID>`, e.g. `POLE_18`, `MH_10`, `POLE_14B`. Query
     point folder naming to be confirmed (§9).
   - File name: `<PointFolder>_<YYYYMMDD-HHMMSS>_<n>.jpg` (to be confirmed, §9).
   - Never overwrites; on a name clash it appends a suffix.
7. After uploading it **verifies** by reading the file back from OneDrive
   (exists, byte size matches). Only then is it marked **confirmed**.
8. Failures retry with backoff. After repeated failure the photo is marked
   **stuck**, the reason is shown, and the admin is alerted in-app. The
   server keeps its copy until confirmed, then deletes it 14 days later.

**What people see:**
- Each photo thumbnail: ⏳ on phone → ☁ on server → ✓ in OneDrive, or ⚠ stuck
  (tap for reason + retry).
- Top-bar pill shows the count of photos not yet confirmed.
- Menu → **Where the photos went**: per job, per point, the OneDrive path and
  status of each photo, with a link to open the folder.

**Speed (owner requirement).** Cadastrus is slow at both hops: phone →
Cadastrus takes long, then Cadastrus → SharePoint "takes forever". Its server
is a DigitalOcean machine in Santa Clara, CA (per DigitalOcean's public IP
geofeed), so distance isn't the cause: the slowness is in how it handles
uploads. Fieldmaxx must be fast at both hops:
- **Phone → server**: upload starts the moment a photo is saved, in the
  background while the tech keeps working; resumable chunks so a dropped
  connection resumes instead of restarting; 2–3 photos in parallel.
- **Smaller files, same picture**: by default the phone re-saves the photo
  at full resolution as a high-quality JPEG (roughly half the size of the
  camera original), copying the EXIF/GPS data across. An admin setting
  switches to sending camera originals untouched.
- **Server → OneDrive**: the delivery worker starts on each photo as soon
  as it's received (no batching), runs several uploads in parallel, and
  streams from disk. Folder lookups/creations are cached per job so each
  photo doesn't re-walk the folder tree.
- **Measure it**: each photo records how long each hop took and the
  throughput. Admin → Delivery health shows typical times, so slowness is
  visible and attributable to the right hop.
- **Target**: on a decent LTE signal, a photo is confirmed in OneDrive
  within ~30 s of being taken; server → OneDrive adds no more than a few
  seconds per photo.

**Uploading through the share link:** the server resolves the per-job Field
Notes "anyone with the link can edit" URL and uses the same SharePoint calls
the browser makes when someone opens that link and uploads. This is
unofficial, so it's isolated behind a `PhotoDestination` interface
(`ensureFolder`, `upload`, `verify`) so an official Microsoft Graph
implementation (approach 2) can replace it later. **First implementation
task is a spike against a test folder** to prove upload + folder creation +
verify works through an anyone-link before building on it. If the link turns
out to be org-only (requires sign-in), escalate to the owner before
proceeding.

The Field Notes link is checked when entered (can we list the folder?) and
the job shows a red banner if delivery to it starts failing.

### 6.7 Live crew positions
- While the app is open, the phone sends its position when it moves > 5 m or
  every 15 s, whichever comes first (with accuracy).
- Positions are broadcast to every signed-in user and shown on every job map.
  Only the latest position per user is kept (no location history in v1).
- A tech can't hide their dot in v1 (owner's requirement: everyone sees
  everyone). "Pause for today" (stop sharing) is a candidate for later.

### 6.8 Working with weak or no signal
- The app shell is cached (service worker), so it opens with no signal.
- The current job's points, pole data and reference images are cached on the
  phone.
- Changes made offline (close point, pole data, comment, move point, added
  points, photos) are queued and synced when back online. Last write wins per
  field; photos are additive and never conflict.
- Live updates arrive over a WebSocket; on reconnect the app re-syncs
  everything changed since it last heard from the server.

### 6.9 Admin
- **Users**: create, reset password, disable, promote.
- **Jobs**: all jobs with progress, crew, created/finished dates; archive and
  delete.
- **Delivery health**: every photo not yet confirmed across the crew, stuck
  ones first, with reasons and retry.
- **Activity**: who shot which point and when; "Can't shoot" list.
- **Pole data export**: per job, `.xlsx` and `.csv` with: point, original
  lat/lon, moved lat/lon, line height, anchor, self-supporting/lashed, tags,
  comment, photo count, tech, closed at.

### 6.10 Finishing a job
Menu → **Finish this job**: shows anything unfinished (points left, photos
not confirmed). When everything is confirmed it writes the pole data
spreadsheet into the job's Field Notes folder and moves the job to
"Finished". Finished jobs stay viewable; job data is kept (no automatic
clearing).

### 6.11 Menu (v1)
Job: All jobs · Job info · Links & folders (Field Notes link) · Points I
added · Where the photos went · Pole data export · Finish this job · Refresh
data. Account: profile, change password, sign out. Admin: admin screens.
Also shows the app version, plus "update available, tap to reload" when a new
version is deployed (replaces Cadastrus's "Force the newest version").

## 7. Architecture

### 7.1 Stack (builder's call, recorded here as a recommendation)
- **Frontend**: React + TypeScript + Vite; Leaflet (react-leaflet) with
  Leaflet.markercluster; PWA via vite-plugin-pwa (Workbox); IndexedDB via
  Dexie for the photo queue and offline cache; suncalc for sunset.
- **Backend**: Node.js 22 + TypeScript, Fastify, WebSocket for live updates,
  SQLite (WAL mode) for data, local disk for photos in transit, pdf.js
  (pdfjs-dist) for import (annotations, form fields, text, page rendering).
- One repo, two packages (`web/`, `server/`) plus a shared types package.

### 7.2 Data (sketch)
`users`, `sessions`, `jobs` (name, notes, field_notes_url, status),
`job_files` (source PDFs + rendered pages), `points` (job, type/layer, label,
folder name, lat/lon, original lat/lon, description, source page, added_by,
status: open/done/cant_shoot, reason, pole fields, comment, updated_at/by),
`photos` (point, tech, local id, file path, size, sha256, status:
received/uploading/confirmed/stuck, destination path, attempts, last_error),
`positions` (user, lat, lon, accuracy, at), `events` (audit/activity).

### 7.3 Hosting
A VPS chosen for **network speed** (owner requirement): a US-West region
(close to the crew in Nevada and to Microsoft's US datacenters), a ≥1 Gbps
port and ≥4 TB/month transfer included, and enough disk for photos in
transit (≥80 GB). Examples: DigitalOcean (San Francisco), Hetzner
(Hillsboro, OR), Vultr (Silicon Valley / Seattle); roughly $12–25/month at
that spec. Before going live, measure real upload speed from the server to
the OneDrive tenant with a test file and pick the region that's fastest.
It runs Docker Compose: the app container + Caddy for automatic HTTPS. Works with a bought domain or a free
`<ip>.sslip.io` name (Cadastrus does the latter). One-command setup script
creates the first admin. Nightly backup of the SQLite file. The owner provides
the server; the build includes deploy instructions.

### 7.4 Android later
Keep camera, geolocation, file storage and background upload behind small
interfaces in the frontend so a Capacitor Android build can swap in native
plugins (background geolocation, background upload) without rewriting
screens.

## 8. Decisions log

**Owner decided:**
- Replace Cadastrus because it's unreliable; do it better.
- Job input is PDFs (poles; queries "like the pole one").
- Photos go into the job's Field Notes folder via its share link, in
  category/point folders, done by the app itself.
- Multiple techs at once; everyone sees everyone's GPS; admin (owner) hands
  out logins; runs on a server.
- The point screen (pole data, query reference, navigate, move, gallery) is
  essential to the job.
- Approach 1 now, maybe an Android app later.
- Uploads must be fast at both hops; the server must have good upload and
  download speed.

**My calls (owner may override):**
- v1 scope and the deferred list in §3.
- Point can only be "Done" with ≥1 photo; otherwise "Can't shoot" + reason.
- Any user can create jobs; only admins delete.
- Photos re-saved at full resolution, high-quality JPEG, EXIF kept (about
  half the size) by default; admin can switch to untouched originals. No
  per-tech quality picker.
- Reference pages = rendered PDF pages linked to each point.
- Pole data exported as xlsx/csv and written to Field Notes on finish.
- Job data kept indefinitely; server photo copies purged 14 days after
  confirmation; phone copies 3 days after.
- English only.
- Stack and hosting in §7.

## 9. Open items
- **Query PDF sample**: needed to confirm label format and how reference
  images map to query items. Importer design already tolerates variation.
- **Query point folder name** in OneDrive (e.g. `QUERY 1/Query 1-43`?).
- **Photo file naming** inside point folders: should it match what
  Cadastrus produced?
- **Test Field Notes folder link** for the upload spike (a throwaway folder
  shared the same way as real jobs).
- **House-number source**: builder's call (e.g. OpenStreetMap addresses for
  the visible area at high zoom); not essential to v1.

## 10. Left to the builder
Exact visual styling within the Cadastrus-like layout; library versions;
database access layer; chunked-upload protocol details; marker colours per
type; clustering thresholds; test strategy (must include: importer unit tests
with the A05J9XE fixture expecting exactly 7 POLE points, the line-break and
spacing variants, DMS, swapped and unparseable lines; an end-to-end test of
photo capture → server → stub destination → confirmed; an offline/resume test
for the photo queue).
