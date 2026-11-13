# Fieldmaxx: Field Survey App for Android (design)

Date: 2026-11-13
Status: v2 approved by owner 2026-11-13 (native Android; includes the
owner's answers to the v2 review: server backup ≥ 3 days, photo reuse on
queries, test folder provided). v1 (web app) was
approved earlier the same day; the owner then switched to a native Android
app because a friend is building a web clone of Cadastrus. Everything the
owner decided in v1 carries over unless §8 says otherwise.

## 1. Intent and why

Fieldmaxx replaces **Cadastrus**, a web app the owner's crew currently uses for
AT&T fielding/surveying. The owner runs a crew of field techs. Each AT&T job
arrives as one or more **PDF or Word files** (from SharePoint) listing points
(poles, pedestals, handholes, manholes, client queries…) with coordinates. The
techs walk the route, photograph each point, record pole details, and the
photos must land in the job's **Field Notes** folder in OneDrive/SharePoint,
filed by category and point.

Cadastrus does this, but it is unreliable, and that is the whole reason for this
project:

- photos are sometimes lost;
- the map zooms out on refresh;
- sometimes it breaks completely and a job file can't be uploaded;
- points are missed because it fails to turn "point number + coordinates" into
  a point on the map;
- uploads are slow: slow to Cadastrus, then "forever" to SharePoint.

**Success** = the crew trusts it. No photo is ever lost without someone seeing
it, no point in a job file is ever silently dropped, photos land in OneDrive
quickly, and the app doesn't fight them (keeps their map view, keeps working
on weak signal).

Fieldmaxx is a **native Android app** plus **our own small server**. It is
separate from the friend's web clone: no shared data.

## 2. Users and roles

- **Admin** (the owner). Creates the logins and hands them out. No self-signup.
  Sees everything: all jobs, all techs, delivery health. Admin screens are
  inside the app.
- **Tech**. Signs in with the username and password the admin gave them, works
  jobs, takes photos, records pole data.
- Several techs work **the same job at the same time**. Everyone sees each
  other's progress live and **everyone sees each other's GPS position while
  their app is open**.
- **Everyone on the crew uses Android.** No iPhone support.

## 3. Goals, non-goals, anti-goals

### Goals (first version)
1. **Reliable import** of pole and query files (PDF and Word .docx), with a
   check screen that accounts for every point found and every line it
   couldn't read.
2. **Map** with satellite imagery and all job points, type filters with
   progress, live crew positions.
3. **Point screen**: instructions, navigation, move point, GPS accuracy, pole
   data capture, comment, photos, close point; query points show the client's
   reference.
4. **Photo delivery that can't silently lose photos, and is fast**: phone →
   OneDrive Field Notes folder directly, plus a **backup copy on our server
   kept at least 3 days**; the phone keeps its copy until both are done;
   visible status per photo.
5. **Reuse photos already taken** to answer client queries (engineers often
   query info the crew already provided).
6. **Built-in camera** with the stamp and a live compass on screen.
7. **Crew**: admin-issued accounts, shared live progress, live GPS
   (foreground only).
8. **Pole data export** as a spreadsheet for the OCALC work.
9. **Easy install and updates** without Google Play.

### Non-goals (later, not in v1)
My pay, Markup for the client, Coordinates from photos, Smart route, "On my
way", rounds (the "+" chip), map usage estimate, per-tech photo quality
picker, Russian/Spanish UI, Bug/Suggestion form, iPhone app, Google Play
listing, offline map downloads, background location. "Utilities, addresses &
MDU" and "Data collection" are covered only as far as the add-point
categories below go.

### Anti-goals (would make this a failure even if it "works")
- A photo that disappears without a visible failure.
- A point in a job file that never makes it onto the map without the importer
  saying so.
- The map resetting its view when the app is reopened.
- Tracking a tech's location while the app isn't on their screen.
- Needing the company's IT department to approve anything before the crew can
  use it.
- An import that fails as a whole because of one bad line.

## 4. Approaches considered

1. **Native Android app (Kotlin) + our own server; photos uploaded by the
   phone straight to OneDrive through the job's Field Notes share link**
   (chosen). Fastest delivery (one hop), uploads continue with the screen off,
   in-app camera with live compass, no IT approval. Trade-off: uploading
   through an "anyone with the link" share isn't an official Microsoft API,
   so Microsoft can change it (mitigated by the phone keeping every photo
   until verified, the server backup copy, and an export-to-OneDrive-app
   safety net, §6.6).
2. **Photos relayed through our server to OneDrive** (v1's design). Adds a
   hop before OneDrive and makes speed depend on our server. Rejected as the
   main route; but every photo *also* goes to our server as a backup (owner),
   and the server delivers it itself if the phone hasn't within 30 min.
3. **Upload via official Microsoft sign-in (Graph API).** Most durable, but
   needs EES's IT to approve an app registration. Not the owner's call. The
   uploader sits behind a `PhotoDestination` interface so this can be swapped
   in later.
4. **Cross-platform app (Flutter/React Native)** for iPhone support. Rejected:
   the whole crew is on Android, and the owner wants a genuine Android app.
5. **Wrapping the web app (Capacitor).** Rejected for the same reason.

**Owner's decisions:** native Android app, Android only, separate from the
friend's site, our own server.

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
  notes.
- Producer is iLovePDF (the designer edits/annotates PDFs there), so other job
  PDFs may differ in layout. The importer must be tolerant (§6.2).

### 5.2 Query files: Word (.docx) as well as PDF
Sample: `QUERY_1_-_A05K51V.docx` (job A05K51V, query list 1). Cadastrus names
query points `Query 1-43` (= query list 1, item 43) and shows the item text as
the description, with a "Client query · reference" panel of reference pictures.

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

### 5.3 OneDrive Field Notes folder (from owner's screenshots)
```
…/NEVADA/F2_A05K51V/                  ← job folder (shared, "anyone" link: globe icon)
  Field Notes/                        ← the link the app is given per job
    Address Query/  Aerial Fielding/  CREEK IMAGE/  CULVERT/  Drainage/
    F3 BUILDING DATA/  Field Notes 3/  HH/  MAIL BOX IMAGES/  MDU/  MH/
    PED/  PFP/  POLE/  QUERY 1/  QUERY 2/  Query 3/  Rear images/  SAI/
    Street images/  UTILITIES/  VACANT LOT/
      MH/   MH_1/ MH_10/ MH_11/ … MH_19/ …          ← one folder per point
      POLE/ POLE_1/ POLE_10/ POLE_10B/ POLE_14b/ …  ← "b" = poles added between
```
Category folder casing is inconsistent (`QUERY 1` vs `Query 3`). SharePoint
names are case-insensitive.

### 5.4 Cadastrus UI (from 7 saved page snapshots)
Dark UI (slate-900 `#0f172a` bars, blue `#2563eb` accents), Esri World
Imagery + Esri World_Transportation label overlay, marker clustering.
Fieldmaxx keeps the familiar layout so the crew doesn't have to relearn it.

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
  Delete**: deleting or recycling returns 403. So the app can never clean up
  after itself in OneDrive. Uploads must be idempotent (check for an existing
  file with the same name and size before uploading; treat a match as
  delivered). The test folder `_FIELDMAXX_TEST_DELETE_ME/POLE_99/
  POLE_99_TEST_1.jpg` had to be left for a person with full access to delete.
- The link can't see above the Field Notes folder (parent returns 403).
- **Existing layout in this job**: Field Notes holds folders named just
  `1` … `7` (one per pole, likely pre-created by the office). Files inside are
  named by Cadastrus as `POLE_1_39.636130_-119.691104_6018.jpg` =
  `<TYPE>_<ID>_<lat 6dp>_<lon 6dp>_<counter>.jpg`.
- **Cadastrus photos**: 3000×4000 JPEG, 1.4–6.7 MB, **EXIF stripped**.
  Visible stamp, bottom-left, white text with dark shadow, four lines:
  ```
  A05J9XE · POLE 1
  39.636130, -119.691104
  ±16 ft · alt 4430 ft
  11/05/26 13:35:47
  ```
  No compass line. The sample was stamped 13:35 local and created in
  OneDrive at 14:12 local (~37 min later); that job's 17 photos all landed
  within 21 s of each other.

### 5.6 Test folder findings (owner's test folder, 2026-11-13)
The owner's test folder is a **personal OneDrive** "anyone can edit" link
(`https://1drv.ms/f/c/184ec04aa3230f57/…`). Personal OneDrive now runs on the
same SharePoint back end, so the same calls work:
- `1drv.ms` → 301 to `onedrive.live.com/:f:/g/personal/<cid>/…` → 302 to
  `onedrive.live.com/?id=…&spopath=%2Fpersonal%2F<cid>%2FDocuments%2FFieldmaxx&listurl=…`
  and a guest cookie. The site is `https://onedrive.live.com/personal/<cid>`;
  the folder's server-relative path is the `spopath` parameter. (The EES
  business link instead redirects to `<tenant>-my.sharepoint.com/personal/
  <user>/_layouts/15/onedrive.aspx?id=<path>`.) The link resolver must
  handle both shapes. Use a normal browser User-Agent when resolving.
- Same results as the business link: contextinfo digest, nested folder
  creation, 9.3 MB upload in 1.8 s, read-back size match, "already exists"
  refusal (HTTP 400) on a duplicate name, and **no delete permission**.
- **Creating a folder that already exists** returns HTTP 400 "A file or
  folder with the name … already exists" (observed live during Task 10);
  the client treats that as success.
- **Chunked upload works through the link**: create an empty file, then
  `StartUpload(uploadId=guid'…')`, `ContinueUpload(uploadId,fileOffset)`,
  `FinishUpload(uploadId,fileOffset)` with 3.2 MB chunks; the final size
  matched. So uploads can resume mid-file on weak signal.
- Test files left in the test folder: `POLE/POLE_1/POLE_1_TEST_simple.jpg`,
  `POLE/POLE_1/POLE_1_TEST_chunked.jpg` (the owner can delete them).
- A final check on a real EES job folder is still needed before the crew
  switches over (pilot job).

## 6. What it does

### 6.1 Accounts and sign-in
- Admin creates users in the app: display name, username, initial password.
  Admin can reset a password, disable a user, and promote another user to
  admin. Techs can change their own password.
- Sessions are long-lived (90 days, sliding) so techs aren't logged out in the
  field. The session token is stored encrypted on the phone.
- The first admin account is created by the server setup script.

### 6.2 Jobs and import
**Accepted files:** PDF and Word (.docx). (Old-style .doc: not in v1.)

**Getting files into the app.** "+ New job" opens Android's file picker
(Downloads, the OneDrive app, Drive, email attachments all appear there). The
app is also a **share target**: in the OneDrive app or an email, "Share →
Fieldmaxx" on a PDF/DOCX opens "New job / Add to job". Several files can be
picked at once. **Reading the files happens on our server** (the app uploads
them and gets back the check-screen data), so importing needs signal, and a
fix to the reader doesn't need an app update.

**Creating a job.** Pick files → check screen → paste the **Field Notes
link** (or share it into the app from the OneDrive app) → save. Job name
defaults to the first file's name (`A05J9XE.pdf` → `A05J9XE`), editable. More
files (e.g. a query file that arrives later) can be added to an existing job
through the same check screen. Any signed-in user can create a job or add
files.

**Deleting a job** ("accidents happen"): the tech who created the job, or an
admin, can delete it. It asks for confirmation and shows what's in the job;
if any photos haven't reached OneDrive yet it warns that they'll stay
undelivered (they remain on the phone that took them). Deletion is soft: the
job disappears for everyone, an admin can restore it for 30 days, after which
it's purged. Photos already in OneDrive are untouched.

**Where the importer reads text from** (all of them, then de-duplicates):
- PDF: annotation contents (FreeText, Text/sticky notes, etc.); form field
  values (the sample has an AcroForm); the page text layer.
- DOCX: the body in document order (paragraphs and table cells), with
  **Word list numbering rendered** the way Word shows it (per list: start
  value, number format, level text, counting per list and level; numbering
  inherited from paragraph styles), **typed-in numbers** at the start of a
  paragraph (`12.`, `13..`), and embedded images in order.

**How it reads points.** Normalize text first: treat `\r`, `\n`, `\t` and runs
of spaces as separators; re-join a number split after `-` or `,` across a line
break. Then:
- **Labelled points**: a type word plus an id (`POLE 1`, `MH 10`, `HH 2`,
  `PED 3`, `POLE 14B`), followed by `=`, `:`, `-` or nothing, then a
  coordinate pair. Text after a point's coordinates up to the next label is
  its description.
- **Query lists (numbered items)**: an item starts at a numbered paragraph
  (auto or typed number) that has text. Coordinates on its line or in the
  following coordinate-only paragraphs belong to it; images that follow
  (until the next item) are its **reference images**. One coordinate = one
  point labelled `Query <n>-<item>` (`Query 1-14`); several = one point each
  with letter suffixes (`Query 1-25A`, `Query 1-25B`, using Word's letters when
  it has them). The item text is the description. A query PDF exported from
  the same kind of document is read the same way, using text and image
  positions on the page to keep reading order.
- **Coordinate pairs**: decimal degrees with optional space after the comma,
  or separated by a space; also degrees-minutes-seconds (`39°38'09.9"N
  119°41'27.8"W`). Validate ranges.
- **Sanity checks** (flag, don't drop): lat/lon look swapped; the sign of
  longitude disagrees with the rest of the job; a point is more than ~50 km
  from the job's other points; a duplicate label with different coordinates.
- A free-standing block with no coordinates (like "NOTE FOR AERIAL PATH")
  becomes the **job notes**.
- **Unlabelled coordinate pairs** become points named by layer + sequence and
  are flagged for review. An item with no coordinates is flagged "no
  coordinates".
- A line that contains a type word or a number that looks like a coordinate
  but couldn't be parsed is listed as **"couldn't read"**. Never silently
  ignored.
- Importing the same file twice into a job doesn't double points (same label
  within 1 m is skipped and reported).

**Point type / layer.** From the label's type word (POLE, MH…). For query
files the layer name (`QUERY 1`) comes from the file name and is editable on
the check screen.

**Check screen** (the core of "no point silently missed"), phone-sized:
- "Found 7 POLE points" summary per type, a small map preview with all points,
  and a list of point cards: label, lat, lon, description, flag badges; each
  editable or removable.
- A "Couldn't read" section with the raw text of every unparsed line, a "Fix"
  form (label + coordinates) or an explicit "Ignore".
- Save is enabled only when every "no coordinates" point is fixed or removed
  and every "couldn't read" line is fixed or ignored.
- If the file itself can't be opened, it says so plainly and nothing is saved.

**Reference images.** Query items' own images are attached to their point(s)
and shown as the **"Client query · reference"** panel (tap to zoom). Every PDF
page is also rendered to an image; each point links to the page(s) its text
came from (for poles this is the route screenshot). Reference images live on
our server with the job and are cached on the phone when the job is opened, so
they work offline and don't vanish like Cadastrus's did.

**Job info** shows the job notes, the source files (open on the phone), the
Field Notes link, and the crew who has worked it.

### 6.3 Map screen
Layout follows Cadastrus:
- **Top bar**: job name; search; connection pill (online / offline / "3
  photos waiting"); menu ☰. A red banner under it when OneDrive delivery for
  this job is failing (with the reason).
- **Chip row**: "Hide shot" toggle; "All (N)"; one chip per type with
  done/total and a progress underline (e.g. "✓ POLE (80/80)"); tapping a chip
  filters to that type.
- **Progress strip**: "Progress: 152/159 (96%)", **"Left: 7"** (remaining
  points sorted by distance from you), sunset countdown ("☀️ 5h 40m until
  sunset"), progress bar, per-type counts.
- **Map**: Esri World Imagery base (source/key supplied by the server) +
  toggleable street-name overlay (Aa);
  house-number overlay (123, OpenStreetMap addresses at high zoom); "center on
  me"; zoom. Markers are circles with a short type code (P, MH, HH, PED, BU,
  Q…) coloured by type; shot points turn grey, faded and struck-through; a
  small badge marks points with photos not yet in OneDrive. Clustering at low
  zoom, off at high zoom.
- **Your position**: pulsing blue dot. **Crew**: one dot per other tech with
  their name and "2 min ago". A tech whose app went to the background shows
  as a grey dot "app closed · 3 min ago"; dots older than 10 min are faded,
  older than 1 h hidden.
- **"+" button** (bottom-left): add a point here (§6.5).
- **The map remembers its view** (centre + zoom) per user per job, saved on
  every move, restored whenever the job is reopened. First open fits all
  points.

### 6.4 Point screen (bottom sheet on tapping a marker)
- Header: point name; **Navigate** (opens Google Maps directions to the
  point); **Move point**: any tech can drag the marker anywhere, any time
  (imported or added points, open or closed). Confirm/cancel after the drag,
  and "Undo move" for 10 s after. The original coordinates are kept and the
  move is recorded with who/when; the crew sees the new position live; close.
- Instructions for the type (POLE: "Photograph the pole and the carrier
  equipment on it.") and the point's description from the file.
- **GPS accuracy** badge ("GPS ±12 ft"; green ≤ 16 ft, amber ≤ 50 ft, red
  above).
- **Pole data** (POLE type, including added poles): line height (ft,
  decimal); Anchor (toggle); Self-supporting / Lashed (one or neither); tags
  (multi-select): N/A, Carrier label, Grounding, No ground, No fiber, Damaged
  cable.
- **Client query · reference** panel for query points.
- **Use existing photos** (query points, owner: "engineers skip over things
  and query us on info we already provided"): see §6.6.2.
- Comment (free text).
- **Take photo** (built-in camera, §6.6) and **Pick from gallery** (Android
  photo picker).
- Photo strip: thumbnails with per-photo status.
- Folder line: "→ Field Notes/POLE/POLE_18". If two folders could match, it
  asks once which one to use.
- **Done** closes the point (requires ≥1 photo), or **Can't shoot** with a
  reason (Not found, No access, Not applicable, Other + text). Both count as
  complete; "Can't shoot" points are listed for the admin. Closed points can
  be reopened.
- All edits save as you go and sync to the crew.

### 6.5 Adding points
"+" → a crosshair at your GPS position (drag the map to adjust) → choose type
(the job's types plus: POLE, PED, HH, MH, BU, SAI, PFP, MDU, CULVERT, Drainage,
CREEK IMAGE, MAIL BOX IMAGES, Street images, Rear images, VACANT LOT,
UTILITIES, Aerial Fielding, F3 BUILDING DATA) → name. For POLE the name is
suggested from the nearest existing pole number plus the next free letter
(`POLE 14B`), editable; for other types the next free number. Added points
behave like imported ones and are marked "added by <tech>". Menu → "Points I
added" lists them.

### 6.5a Deleting points
Any tech can delete a point (imported or added) **as long as none of its
photos has reached OneDrive**; photos still on the phone are discarded with
it, after a warning. After that, only an admin can delete it. Deletes are
soft: the point disappears for everyone, shows in the job's activity with
who/when, and an admin can restore it. Folders already created in OneDrive
are left alone.

### 6.6 Photos: camera, stamp, delivery (the reliability core)

**Camera.** A built-in camera screen (CameraX): full-resolution capture,
shutter, flash, zoom, and a **live overlay** showing exactly what will be
stamped: job · point, coordinates, ±accuracy · altitude, compass heading
(live, from the phone's rotation sensor), date/time. Several photos in a row
without leaving the camera; a counter shows how many were taken for this
point. The heading stamped is the reading at the moment the shutter is
pressed. If the compass needs calibration, the overlay says "Compass:
calibrate" (figure-8 hint).

**Stamp** (owner requirement), same look as Cadastrus plus a compass line,
burned into the image at full resolution (bottom-left, white bold text, dark
shadow, sized at 2.8% of the shorter side):
```
A05J9XE · POLE 1
39.636130, -119.691104
±16 ft · alt 4430 ft
NE 47°
11/05/26 13:35:47
```
- GPS fix older than 10 s at the shutter → line 2 ends "(GPS stale)"; no fix
  → "No GPS". Gallery picks use the photo's own EXIF GPS/time if present and
  the time line ends "(from gallery)".
- EXIF is kept and written: GPS, altitude, image direction, date/time, camera
  make/model.

**Steps on the phone** (each step persists before the next starts, so a
crash, a dead battery or a force-stop never loses a photo):
1. **Saved**: the full-resolution capture is written to app storage and
   recorded in the phone's database (point, job, tech, time, GPS, heading).
2. **Stamped**: a worker makes the upload copy: stamp drawn, JPEG quality
   85% (admin setting "Maximum" = 95%), images above 16 megapixels scaled to
   16 MP (memory safety), EXIF written. A copy is also saved to the phone's
   gallery in an album "Fieldmaxx" (setting, on by default) as an extra
   backup.
3. **Registered**: the app tells our server about the photo; the server
   assigns its file name `<TYPE>_<ID>_<lat 6dp>_<lon 6dp>_<counter>.jpg`
   (server-wide counter) and now knows the photo exists. Needs signal to our
   server.
4. **Uploading**: the phone uploads straight to the job's Field Notes folder
   (§6.6.1), up to 3 photos in parallel, in the background (Android
   WorkManager: keeps going with the screen off or the app closed, resumes
   after a reboot, waits for signal). Uses SharePoint's chunked upload (proven
   through the link, §5.6) so a dropped connection resumes mid-file.
5. **Confirmed**: the phone reads the file back from OneDrive (exists, byte
   size matches), then reports "confirmed" (destination path, timings) to our
   server, which tells the crew.
6. **Backed up** (owner requirement): every stamped photo is also uploaded
   to our server, in resumable chunks, alongside the OneDrive upload (OneDrive
   gets bandwidth priority). This runs even when OneDrive is failing, so a
   photo reaches safety as soon as there's any signal.
7. The phone's own copies are deleted 3 days after the photo is both
   confirmed in OneDrive and backed up on the server (the gallery copy stays,
   if enabled).

**On the server:**
- Keeps every backup copy **at least 3 days** after it arrives, and never
  deletes one that isn't confirmed in OneDrive. Admin setting "Keep backups
  for N days" (default 3, minimum 3). Above 80% disk it warns the admin;
  it never deletes early to make room.
- Keeps a small thumbnail (~320 px) of every photo permanently, with its
  point, location, time and OneDrive path, for reuse (§6.6.2).
- **Server takes over delivery** if a photo it holds isn't confirmed in
  OneDrive within 30 min of arriving (phone lost, broken, out of signal): the
  server uploads it to the same folder with the same name, using the same
  rules (§6.6.1). Whichever of phone or server gets there first wins; the
  other sees a same-name, same-size file and treats it as done.
- Admin can download any job's backed-up photos as a ZIP laid out like Field
  Notes.

Failures retry with backoff (15 s doubling to 30 min). After 6 failed
attempts the photo is **stuck**: the reason shows on the photo and is
reported to the server, the admin sees it in Delivery health, and retrying
continues every 30 min. "Retry now" on the photo forces an attempt.

**6.6.1 Where photos go in OneDrive** (owner: "if there's no pole 33 folder it
goes to poles in Field Notes and makes a POLE_33 folder"):
1. **Use an existing folder for the point if there is one.** Search Field
   Notes and its category folders (two levels) for a folder whose name is the
   point's id in any common form, case-insensitive: `1`, `POLE_1`, `POLE 1`,
   `Pole1`, `P1`; for added poles `14B`/`POLE_14b`; for queries `43`,
   `Query 1-43`, `QUERY_1-43`. Prefer a match inside the point's category
   folder; at the top level a bare number only matches POLE points (the
   A05J9XE layout).
2. **Otherwise create** `<Category>/<TYPE>_<ID>` (`POLE/POLE_33`, `MH/MH_10`,
   `QUERY 1/Query_1-43`), reusing an existing category folder's spelling.
3. The folder chosen is stored on the point (shared via the server) and
   reused for every later photo. Two candidates → the point screen asks once.
- Never overwrites. Before uploading, check for an existing file of the same
  name and size and treat it as delivered (the link can't delete, so retries
  must not create duplicates); same name, different size → ask the server for
  a new counter.

**6.6.2 Reusing photos already taken** (owner requirement). On any point,
mainly query points, **Use existing photos** opens a picker of photos already
taken, newest first, with thumbnail, point label, job and date:
- **Nearby**: photos taken within 150 m of this point, from **any job** (the
  same pole is often in an earlier job), with the distance shown;
- **This job**: all photos in the current job, grouped by point;
- search by point label.
Picking photos copies them into this point's folder in this job's Field
Notes, with a new file name (same pattern, the original photo's coordinates),
and the original stamp unchanged (it records when and where it was really
taken). The full image comes from, in order: the phone, the server backup,
or, for older photos, downloaded from OneDrive through the original job's
Field Notes link (the link allows viewing). Reused photos count toward the
"≥1 photo" rule and show "reused from POLE 5 · A05J9XE · 11/05/26".

**What people see.** Each thumbnail: ⏳ on phone → ⬆ uploading → ✓ in OneDrive,
or ⚠ stuck (tap for reason + Retry now); a small shield shows once the server
backup is in place. Top-bar pill counts photos not yet in
OneDrive. Menu → **Where the photos went**: per point, each photo's OneDrive
path and status, plus a button to open the Field Notes folder.

**Speed.** One hop to OneDrive, starting the moment the photo is stamped, 3
in parallel; the server backup shares the connection at lower priority.
Each photo records time from shutter to confirmed and upload time; admin's
Delivery health shows typical times. **Target**: on a decent LTE signal, a
photo is confirmed in OneDrive within ~30 s of being taken. (Android may slow
background work when a phone sits idle in a pocket for a long time; opening
the app speeds it back up.)

**Field Notes link** is checked when entered (can we list the folder?). If
delivery for a job starts failing for everyone (link expired, needs sign-in,
no upload permission), the server marks the job and every phone shows the red
banner; photos wait on the phones.

**Safety net if the share-link route breaks:** the server backup holds every
photo until OneDrive confirms it, and the admin can download a job's photos
as a Field-Notes-shaped ZIP. On the phone, menu → **Export photos to OneDrive
app** bundles the job's unconfirmed photos the same way and hands them to
Android's share sheet (the official OneDrive app, Drive, email…).

**Deleting a photo:** a tech can delete their own photo before it reaches
OneDrive. Once delivered, it stays in OneDrive (the link has no delete
permission; remove it there by hand); the app hides it from the point and
records who removed it and when, so a removed photo is never mistaken for a
lost one.

### 6.7 Live crew positions (foreground only, owner requirement)
- Location is collected **only while the app is on screen**. The app asks
  only for "while using the app" location permission and never for
  background location. No foreground service for location.
- While on screen, the phone sends its position when it moves > 5 m or every
  15 s.
- When the app leaves the screen, it sends "paused" once and stops; others see
  the grey "app closed" dot. Only the latest position per user is kept (no
  history).
- Positions go to every signed-in user and show on every job map.

### 6.8 Working with weak or no signal
- The app opens and works with no signal: jobs, points, pole data, reference
  images and photos are stored on the phone (local database).
- Changes made offline (close point, pole data, comment, move, add/delete
  point, photos) are queued and synced when back online, in order, with
  retries. Last write wins per field; photos never conflict.
- Live updates arrive over a WebSocket; on reconnect the app fetches
  everything changed since it last heard from the server.
- **Map imagery offline**: tiles already viewed are kept in the map's cache;
  no "download this area" in v1 (Esri's terms restrict bulk downloading
  World Imagery). Without imagery, points, your GPS dot and the point screens
  still work on a plain background.
- Importing a new job file needs signal (the server reads it).

### 6.9 Admin (inside the app, admin role only)
- **Users**: create, reset password, disable, promote.
- **Jobs**: all jobs with progress, crew, created/finished dates; restore
  deleted jobs (30 days) and deleted points.
- **Delivery health**: every photo not yet confirmed across the crew, stuck
  ones first, with each phone's last reported reason, plus typical delivery
  times. A red count on the menu when anything is stuck.
- **Activity**: who shot which point and when; "Can't shoot" list.
- **Settings**: photo quality (Standard 85% / Maximum 95%).

### 6.10 Pole data export and finishing a job
- **Pole data export** (any user): `.xlsx` or `.csv` with Point, Original
  lat, Original lon, Moved lat, Moved lon, Line height (ft), Anchor,
  Self-supporting/Lashed, Tags, Comment, Photos, Tech, Closed at. Generated by
  the server; the phone saves it to Downloads or shares it.
- **Finish this job**: shows anything unfinished (points left, photos not
  confirmed). When everything is confirmed, the server writes
  `<job>_pole_data.xlsx` into the job's Field Notes folder (next free name if
  taken) and moves the job to "Finished". Finished jobs stay viewable; job
  data is kept.

### 6.11 Install and updates
- The server hosts a small download page (`https://<server>/`) with a
  "Download Fieldmaxx for Android" button and three-step instructions
  (allow install from this source once, open the file, sign in).
- On start and once a day the app checks the server for a newer version and
  shows "Update available — tap to install"; tapping downloads the new APK and
  opens Android's installer. Updates keep the phone's data.
- Builds are signed with one **release key**. The owner must keep a backup of
  it: losing it means everyone has to uninstall and reinstall to get updates.

### 6.12 Menu (v1)
Job: All jobs · Job info · Field Notes link · Points I added · Where the
photos went · Export photos to OneDrive app · Pole data export · Finish this
job · Refresh. Account: profile, change password, sign out. Admin: admin
screens. Also: app version and update check. Settings: save copies to
gallery (on/off).

## 7. Architecture

### 7.1 Stack (my calls; recorded as the plan's starting point)
- **One Gradle repo, Kotlin everywhere**, five modules:
  - `core` (pure Kotlin/JVM): shared models, coordinate parsing, naming rules,
    stamp text/layout, compass helpers. Used by app and server.
  - `sharepoint` (Kotlin/JVM, OkHttp): the share-link client and folder
    resolver behind the `PhotoDestination` interface. Used by the app
    (uploads) and the server (link check, finish-job spreadsheet).
  - `importer` (Kotlin/JVM): PDF (Apache PDFBox 3) and DOCX (zip + XML)
    reading. Server only.
  - `server`: Ktor, SQLite, WebSocket, sessions, jobs/points/ops, photo
    registry, exports (xlsx/csv), APK hosting. Runs in Docker.
  - `app`: Android, Jetpack Compose, MapLibre Native (Esri raster tiles,
    clustering), CameraX, Room, WorkManager, DataStore, Fused Location
    Provider (foreground only), OkHttp WebSocket. Min Android 9 (API 28).
- **Testing**: JVM unit tests for `core`, `sharepoint` (against a mock
  SharePoint server), `importer`; Ktor test host for `server`; Robolectric for
  app logic and Compose screens. There's no Android emulator in the build
  environment, so on-device checks are done by the owner installing test
  builds.

### 7.2 Data (sketch)
Server: `users`, `sessions`, `jobs` (name, notes, field_notes_url, status,
delivery_problem), `job_files`, `ref_images`, `points` (job, layer, type, num,
label, lat/lon, original lat/lon, description, status, cant reason/text, pole
fields, comment, folder_path, folder_candidates, flags, added_by, updated
at/by, deleted at/by), `photos` (point, tech, file_name, size, status:
registered/uploading/confirmed/stuck/removed, dest_path, last_error,
taken_at, confirmed_at, upload_ms, reused_from, lat/lon, backup file +
received_at + purged_at, thumbnail), `ops_done`, `changes`
(sync feed), `positions`, `events`, `settings`, `counters`, `app_releases`.
Phone: the same jobs/points/photo info as a local cache, plus local photo
files and their pipeline state, and the outbox of pending changes.

### 7.3 Hosting
The server holds photo backups (≥ 3 days) and permanent thumbnails, so it
needs disk and decent bandwidth: a VPS in a US-West region with 2–4 GB RAM,
≥ 80 GB disk (or an attached volume), ≥ 1 Gbps port and ≥ 2 TB/month
transfer (e.g. DigitalOcean, Hetzner, Vultr; roughly $12–24/month). Rough
sizing: 4 techs × 300 photos/day × 4 MB ≈ 5 GB/day, so 3 days ≈ 15 GB plus
headroom. Docker Compose with the app container + Caddy for automatic HTTPS. Works with a
bought domain or a free `<ip>.sslip.io` name. One-command setup script creates
the first admin. Nightly backup of the SQLite file. The owner provides the
server; the build includes setup instructions.

## 8. Decisions log

**Owner decided:**
- **v2 review:** every photo is also kept on our server for **at least 3
  days** as a backup; techs must be able to **reuse photos** on queries;
  the owner is creating a test folder for development.
- Replace Cadastrus because it's unreliable; do it better.
- Job input is PDFs (poles; queries) and Word .docx files (queries).
- Photos go into the job's Field Notes folder via its share link, in
  category/point folders, done by the app itself; folders created where
  needed (`POLE/POLE_33`).
- Multiple techs at once; everyone sees everyone's GPS; admin (owner) hands
  out logins; own server.
- The point screen (pole data, query reference, navigate, move, gallery) is
  essential.
- Uploads must be fast.
- Everyone sees every job (no per-job assignment).
- Upload test approved against the A05J9XE Field Notes link.
- Stamps on the images: time, coordinates, compass, point name.
- The tech who created a job can delete it (as can an admin). Techs can
  delete a point before any of its photos is delivered, and can move any
  point anywhere.
- **v2:** a genuine **Android app** instead of a web app; **Android only**
  (whole crew on Android); **separate** from the friend's Cadastrus clone,
  with **our own server**.
- **v2:** crew locations update **only while the app is on screen, never in
  the background**.

**My calls (owner may override):**
- Phone uploads straight to OneDrive; the server backup copy goes up
  alongside; the server takes over delivery after 30 min unconfirmed.
- Reuse picker: nearby (150 m, any job) + this job; full image from phone,
  server backup, or OneDrive via the original job's link; permanent server
  thumbnails.
- Map imagery source and optional key are sent to the app by the server
  (default: Esri World Imagery without a key, like Cadastrus), so switching
  to an ArcGIS account key later needs no app update.
- No admin website; admin screens and exports live in the app (exports can
  be shared to email/OneDrive for use on a PC).
- Server reads job files; app is a share target for PDF/DOCX and links.
- Built-in camera with live stamp/compass overlay; heading at the shutter.
- Stamped copies also saved to a "Fieldmaxx" gallery album (setting, on).
- Install from our own download page; in-app update prompt; no Google Play
  in v1.
- Admin screens inside the app; no separate admin website.
- "App closed" grey dot when a tech's app leaves the screen.
- Safety net = export unconfirmed photos as a Field-Notes-shaped ZIP to the
  OneDrive app via the share sheet.
- Point can only be "Done" with ≥1 photo; otherwise "Can't shoot" + reason.
- Any user can create jobs. Deletes of jobs and points are soft.
- Folder lookup rules and Cadastrus-style file names (§6.6.1).
- Photo quality Standard 85% / Maximum 95% (admin setting); 16 MP cap.
- Pole data as xlsx/csv; written to Field Notes on finish.
- Phone copies deleted 3 days after confirmation; job data kept indefinitely.
- Techs can delete their own photos only before delivery.
- No offline map download in v1; viewed tiles cached.
- Switchover: jobs already in progress finish in Cadastrus; new jobs start
  in Fieldmaxx. Fieldmaxx never overwrites files in OneDrive.
- English only. Android 9+.
- Stack and hosting in §7.

## 9. Open items
- **Test folder**: provided (§5.6). All SharePoint integration tests run
  against it; chunked upload confirmed working. The link URL is kept out of
  the repo (an environment variable for live tests).
- **Pilot**: first real job run end to end before the crew switches over.
- **Esri licensing**: Esri's terms expect business users to have an ArcGIS
  account (free tier exists). The app works keyless like Cadastrus; the owner
  can add a key later via the server (§8).
- **Real sample files**: A05J9XE.pdf and QUERY_1_-_A05K51V.docx are in the
  session's uploads and go into the git-ignored test folder. More real files
  (other designers' pole PDFs, a query PDF) would strengthen the reader.
- **Leftover test folder**: `_FIELDMAXX_TEST_DELETE_ME` in the A05J9XE Field
  Notes folder must be deleted by someone with full access.
- **Release signing key**: generated at the first release build and handed
  to the owner to keep safe.
- A **query PDF** sample would be a useful extra test file.

## 10. Left to the builder
Exact visual styling within the Cadastrus-like layout; library versions;
database access layer; marker colours per type; clustering thresholds; sync
and upload protocol details within the rules above; test strategy (must
include: importer tests expecting exactly 7 POLE points from the A05J9XE
layout and exactly 26 points numbered 1–24, 25A, 25B with their images from
the QUERY 1 layout, using synthetic look-alike files in the repo and the real
samples in a git-ignored folder; the line-break and spacing variants, DMS,
swapped and unparseable lines; the photo pipeline surviving a process kill at
every step; the folder rules; idempotent uploads).
