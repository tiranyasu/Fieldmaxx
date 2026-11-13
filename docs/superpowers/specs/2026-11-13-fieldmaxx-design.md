# Fieldmaxx: Field Survey App (design)

Date: 2026-11-13
Status: approved by owner 2026-11-13 (with changes: creators can delete their jobs; techs can delete points before upload and move points anywhere)

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
1. **Reliable import** of pole and query files (PDF and Word .docx), with a check screen that
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

### 5.2 Query files: Word (.docx) as well as PDF
Queries arrive as **.docx** as well as PDF (owner, 2026-11-13). Sample:
`QUERY_1_-_A05K51V.docx` (job A05K51V, query list 1). Cadastrus names query
points `Query 1-43` (= query list 1, item 43) and shows the item text as the
description, with a "Client query · reference" panel of reference pictures.

Structure of the sample (this is the importer's second test fixture):
- A **numbered list of instructions**, one item per point, each followed by
  its **reference image(s)**: marked-up map/aerial screenshots (0, 1 or 2
  images per item).
- **The item numbers are mostly not in the text.** They come from Word's
  automatic list numbering: list A numbers items 1–11; items 12 and 13 are
  typed by hand ("12. NO STREET VIEW…", "13.. NO STREET VIEW…", note the
  double dot); list B **starts at 14** (Word `start=14`) and numbers 14–25;
  the last item ("POLE DATA", no. 25) is followed by two coordinates in a
  separate lettered list (`A-`, `B-`), i.e. one item with two points.
- Coordinates are either on the item's own line after an en dash
  (`VERIFY HH – 39.35535679, -119.81288376`) or in the next paragraph on
  their own (`39.3591087,-119.8173345`).
- Expected import: **26 points** = items 1–24 (one each) + item 25 as
  `25A` and `25B`, each with its instruction text and its images.
- Layer name comes from the file name: `QUERY_1_-_A05K51V` → `QUERY 1`.

A reader that ignores Word's list numbering, or only looks for coordinates
on the same line as the text, would mis-number or drop points here. That
matches the owner's complaint about Cadastrus.

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
create missing ones. (The screenshot shows only "b" poles under `POLE/`
because it's an old job; normally `POLE_1`, `POLE_10`… are there too.)

### 5.5 What the upload spike found (2026-11-13, owner-approved test)
Against the shared link for job **A05J9XE** (`…/OSP FIELDING/NEVADA/
A05J9XE_Additional fielding/Field Notes`):
- The link is an "anyone" link: opening it with no sign-in redirects and sets
  a guest `FedAuth` cookie. With that cookie the SharePoint REST API works:
  `POST /_api/contextinfo` (request digest), list folders/files,
  `folders/AddUsingPath` (nested folder creation), `Files/AddUsingPath`
  (upload), and file metadata read-back (verify size).
- A 9.3 MB test JPEG uploaded in **2.3 s** (~32 Mbit/s) from a US cloud
  container; read-back length matched.
- The link's effective permissions are View/Add/Edit/Open but **not
  Delete**: deleting or recycling a file or folder returns 403. So the app
  can never clean up after itself in OneDrive. Uploads must be idempotent
  (check for an existing file with the same name and size before uploading,
  and treat a match as confirmed). The test folder
  `_FIELDMAXX_TEST_DELETE_ME/POLE_99/POLE_99_TEST_1.jpg` had to be left
  for a person with full access to delete.
- The link can't see above the Field Notes folder (parent returns 403).
- **Existing layout in this job**: Field Notes holds folders named just
  `1` … `7` (one per pole, likely pre-created by the office), not
  `POLE/POLE_1`. Files inside are named by Cadastrus as
  `POLE_1_39.636130_-119.691104_6018.jpg` =
  `<TYPE>_<ID>_<lat 6dp>_<lon 6dp>_<counter>.jpg`.
- **Cadastrus photos**: 3000×4000 JPEG, 1.4–6.7 MB, **EXIF stripped**
  (no GPS/time in the file). Visible stamp, bottom-left, white text with dark
  shadow, four lines:
  ```
  A05J9XE · POLE 1
  39.636130, -119.691104
  ±16 ft · alt 4430 ft
  11/05/26 13:35:47
  ```
  No compass line. The sample was stamped 13:35 local and created in
  OneDrive at 14:12 local (~37 min later); that job's 17 photos all landed
  within 21 s of each other.

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
**Accepted files:** PDF and Word (.docx). (Old-style .doc: convert on the
server if it ever shows up, or ask for .docx; not required in v1.)

**Creating a job.** "+ New job" → drop or pick one or more PDF/DOCX files →
the check screen → paste the **Field Notes link** → save. Job name defaults to the first
PDF's file name (`A05J9XE.pdf` → `A05J9XE`), editable. More PDFs (e.g. a query
PDF that arrives later) can be added to an existing job through the same check
screen. Any signed-in user can create a job or add files.

**Deleting a job** ("accidents happen"): the tech who created the job, or
an admin, can delete it. It asks for confirmation and shows what's in the
job; if any photos haven't reached OneDrive yet it warns that they'll stay
undelivered. Deletion is a soft delete: the job disappears for everyone
but an admin can restore it for 30 days, after which it's purged. Photos
already in OneDrive are untouched (the app can't delete there).

**Where the importer reads text from** (all of them, then de-duplicates):
- PDF:
  1. Annotation contents (FreeText, Text/sticky notes, etc.).
  2. Form field values (the sample has an AcroForm).
  3. The page text layer.
- DOCX: the body in document order (paragraphs and table cells), with:
  - **Word list numbering rendered** the way Word shows it: per list,
    its start value (e.g. 14), number format (decimal / upper letter) and
    level text (`%1.`, `%1-`), counting per list and level;
  - **typed-in numbers** at the start of a paragraph (`12.`, `13..`)
    recognised as the item number;
  - embedded images, in order.

**Query lists (numbered items).** An item starts at a numbered paragraph
(auto or typed number) that has text. Coordinates on its line or in the
following coordinate-only paragraphs belong to it; images that follow (until
the next item) are its **reference images**. One coordinate = one point
labelled `<Layer>-<n>` (`Query 1-14`); several = one point each with letter
suffixes (`Query 1-25A`, `Query 1-25B`, using Word's letters when it has
them). The item text is the point's description. An item with no
coordinates is flagged on the check screen ("no coordinates"), not dropped.
A query PDF exported from the same kind of document is read the same way,
using text and image positions on the page to keep reading order.

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

**Reference images.** For query items, the item's own images (from the DOCX,
or from the PDF by position) are attached to its point(s) and shown as the
**"Client query · reference"** panel, tap to zoom. In addition, every PDF
page is rendered to an image at import and kept with the job; each point
links to the page(s) its text came from (for poles this is the route
screenshot). Reference images are stored on our server with the job, so
they don't vanish like Cadastrus's did.

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
  point); **Move point**: any tech can drag the marker anywhere, any time
  (imported or added points, open or closed). Confirm/cancel after the drag,
  and an "Undo move" right after. The original coordinates are kept and the
  move is recorded with who/when; the crew sees the new position live;
  close.
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
- **Client query · reference** panel for query points (§6.2). The reference
  comes from the query PDF. Cadastrus also stored it on its own server,
  which is why it showed "unavailable (401)" once an old job was cleared.
- **Use existing photos** (query points): pick photos already taken in this
  job for another point. The server delivers its copy into the query
  point's folder too (if past the server's retention, use Pick from gallery).
  Reused photos count toward the "≥1 photo" rule.
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

### 6.5a Deleting points
Any tech can delete a point (imported or added) **as long as none of its
photos has been uploaded** (no photo has reached the server). Photos still
only on the phone are discarded with it, after a warning. Once a photo has
been uploaded, only an admin can delete the point. Deletes are soft: the
point disappears for everyone, is listed in the job's activity with
who/when, and an admin can restore it. Folders already created in OneDrive
are left alone.

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
6. A delivery worker uploads it into the job's Field Notes folder, choosing
   the point's folder like this (owner: "if there's no pole 33 folder it goes
   to poles in Field Notes and makes a POLE_33 folder"):
   1. **Use an existing folder for the point if there is one.** The office
      sometimes pre-creates them, in different styles. Search Field Notes and
      its category folders (two levels deep) for a folder whose name is the
      point's id in any common form, case-insensitive: `1`, `POLE_1`,
      `POLE 1`, `Pole1`, `P1`; for added poles `14B`/`POLE_14b`; for queries
      `43`, `Query 1-43`, `QUERY_1-43`. Prefer a match inside the point's
      category folder over one at the top level.
   2. **Otherwise create** `<Category>/<TYPE>_<ID>`, e.g. `POLE/POLE_33`,
      `MH/MH_10`, `QUERY 1/Query_1-43`. The category folder is matched
      case-insensitively to an existing one (`POLE`, `QUERY 1`) or created.
   3. The folder chosen for each point is stored, shown on the point screen
      ("→ Field Notes/1"), and reused for every later photo of that point.
      If a match is ambiguous (two candidate folders), the point screen asks
      the tech/admin to pick once.
   - **File name** matches Cadastrus:
     `<TYPE>_<ID>_<lat 6dp>_<lon 6dp>_<counter>.jpg`, with the phone's
     coordinates at capture and a server-wide counter.
   - Never overwrites; on a name clash it bumps the counter. Before
     uploading, it checks for an existing file of the same name and size and
     treats that as already delivered (the link can't delete, so retries
     must not create duplicates).
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
verify works through an anyone-link before building on it. **Done:** the
spike succeeded (§5.5); the implementation should follow the calls proven
there.

The Field Notes link is checked when entered (can we list the folder?) and
the job shows a red banner if delivery to it starts failing.

**Safety net if the share-link route fails or breaks later:** the admin can
download any job's photos as a ZIP already laid out in the Field Notes folder
structure, ready to drag into OneDrive. Photos stay on the server until
confirmed, so nothing is lost while the route is down. The rest of the app is
built in parallel with the upload spike; only the OneDrive uploader waits on it.

**Photo stamp (owner requirement: time stamp, coordinates, compass, point
name burned into the image).** Done on the phone when the upload copy is
made (§6.6 step 1 / Speed), at full resolution. Same look as Cadastrus
(bottom-left, white text, dark shadow, sized relative to the image), plus a
compass line:
```
A05J9XE · POLE 1
39.636130, -119.691104
±16 ft · alt 4430 ft
NE 47°  (compass)
11/05/26 13:35:47
```
- Coordinates, accuracy and altitude come from the phone's GPS at the moment
  the photo comes back from the camera (the freshest fix ≤ 10 s old;
  otherwise the stamp says "GPS stale"). Gallery picks use the photo's own
  EXIF GPS/time if present; otherwise the stamp is marked "from gallery".
- **Compass**: taken from the camera's EXIF image direction when the camera
  app records it; otherwise from the phone's compass reading when the photo
  returns to the app (the tech is normally still facing the subject).
  iPhone needs a one-time compass permission, asked on first photo. If this
  proves not accurate enough in the field, the fix is an in-app camera with
  a live compass, which is also a natural part of the Android app later.
- Unlike Cadastrus, the upload copy **keeps EXIF** (GPS, time, direction),
  written from the app's own fix when the camera didn't provide it.
- The stamp is also shown on the thumbnail so the tech can see it's right.

**Deleting a photo:** a tech can delete a photo that hasn't reached OneDrive
yet. Once delivered, it stays in OneDrive (the share link has no delete
permission, see §5.5; remove it there by hand if needed); the app hides it from the point and records who removed it and
when, so a removed photo is never mistaken for a lost one.

**Server disk:** server copies are deleted 7 days after confirmation. If the
disk passes 80%, the oldest *confirmed* copies are purged first;
unconfirmed photos are never purged. Admin sees disk usage and an alert
above 80%.

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
- **Map imagery offline:** tiles you've already viewed are kept on the
  phone; there is no "download this area" in v1 (Esri's terms restrict bulk
  downloading World Imagery). With no signal and no cached imagery, points,
  your GPS dot and all point screens still work on a plain background.

### 6.9 Admin
- **Users**: create, reset password, disable, promote.
- **Jobs**: all jobs with progress, crew, created/finished dates; archive,
  delete, and restore deleted jobs (30 days) and deleted points.
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
- Job input is PDFs (poles; queries) and Word .docx files (queries).
- Photos go into the job's Field Notes folder via its share link, in
  category/point folders, done by the app itself.
- Multiple techs at once; everyone sees everyone's GPS; admin (owner) hands
  out logins; runs on a server.
- The point screen (pole data, query reference, navigate, move, gallery) is
  essential to the job.
- Approach 1 now, maybe an Android app later.
- Uploads must be fast at both hops; the server must have good upload and
  download speed.
- Everyone sees every job (no per-job assignment). "Crew" on a job = who
  has worked on it.
- Folders are created where needed: a pole 33 with no folder goes to
  `POLE/POLE_33` in Field Notes.
- Upload test approved against the A05J9XE Field Notes link.
- Stamps required on the images: time, coordinates, compass, point name.
- The tech who created a job can delete it (as can an admin).
- Techs can delete a point before any of its photos is uploaded, and can
  drag/move any point anywhere.

**My calls (owner may override):**
- v1 scope and the deferred list in §3.
- Point can only be "Done" with ≥1 photo; otherwise "Can't shoot" + reason.
- Any user can create jobs. Deletes of jobs and points are soft (admin can restore).
- Point folder lookup: reuse an existing folder in any common naming style
  before creating `<Category>/<TYPE>_<ID>`; photo file names match
  Cadastrus's pattern.
- Stamp layout copies Cadastrus's plus a compass line; compass from camera
  EXIF or the phone's compass when the photo returns; native camera (not an
  in-app camera) in v1.
- Photos re-saved at full resolution, high-quality JPEG, EXIF kept (about
  half the size) by default; admin can switch to untouched originals. No
  per-tech quality picker.
- Reference pages = rendered PDF pages linked to each point.
- Pole data exported as xlsx/csv and written to Field Notes on finish.
- Job data kept indefinitely; server photo copies purged 7 days after
  confirmation (sooner, oldest-confirmed-first, if disk > 80%); phone copies
  3 days after.
- ZIP download of a job's photos in Field Notes layout as the safety net
  for the share-link uploader; rest of the app built in parallel with the
  upload spike.
- Missing EXIF GPS filled in from the app's own fix.
- Techs can delete photos only before delivery; delivered photos are hidden
  with an audit record, not deleted from OneDrive.
- Query "use existing photos" = reuse photos from other points in the job.
- No offline map download in v1; viewed tiles are cached.
- Switchover: jobs already in progress finish in Cadastrus; new jobs start
  in Fieldmaxx. Fieldmaxx never overwrites files in OneDrive, so sharing a
  folder with Cadastrus can't destroy anything. Cadastrus's code is assumed
  unavailable (someone else's custom app).
- English only.
- Stack and hosting in §7.

## 9. Open items
- A **query PDF** sample would be a useful extra test fixture (the DOCX
  sample settles the format).
- **Leftover test folder**: `_FIELDMAXX_TEST_DELETE_ME` in the A05J9XE Field
  Notes folder must be deleted by someone with full access (the link can't).
- Whether the owner has their own EES Microsoft login (only matters for
  approach 2 later).
- **House-number source**: builder's call (e.g. OpenStreetMap addresses for
  the visible area at high zoom); not essential to v1.

## 10. Left to the builder
Exact visual styling within the Cadastrus-like layout; library versions;
database access layer; chunked-upload protocol details; marker colours per
type; clustering thresholds; test strategy (must include: importer unit tests
with the A05J9XE PDF fixture expecting exactly 7 POLE points and the
QUERY_1_-_A05K51V DOCX fixture expecting exactly 26 points numbered 1–24,
25A, 25B with their images; the line-break and spacing variants, DMS,
swapped and unparseable lines; an end-to-end test of
photo capture → server → stub destination → confirmed; an offline/resume test
for the photo queue).
