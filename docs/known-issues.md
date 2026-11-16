# Known issues

Every known issue that was deliberately left for later when Fieldmaxx was built, kept here so a future developer can pick them up.
It reflects the code at the end of the build on 2026-11-14; items the final fix wave fixed are not listed.

Each line gives the file and line as recorded (lines may have moved), what happens and who notices, and the suggested fix if one was recorded.
"(checked at final review)" means the final review or its re-review confirmed the item in the final code; "(recorded during the build)" means it comes from a task review during the build and was not re-checked.
Where no file:line was recorded, the component is named instead.

## Photos and uploads

- PhotoPipeline.kt:507, reached from BackupWorker.kt:49: when a refused point delete wakes a backup worker that is already running, the re-enqueue uses KEEP and is dropped. The backup then waits up to 6 h for the next cleanup resume; the OneDrive upload is not affected and nobody sees it. Fix: none recorded. (checked at final review)
- BackupWorker.kt:52-56: a confirmed photo whose stamped copy vanished before its backup finished is never backed up or cleaned up; the phone's original lingers. It needs the stamped copy to be removed from outside the app. Fix: none recorded. (checked at final review)
- RegisterWorker.kt:65-68 and server PhotoService.kt:123-125: a re-stamp that encodes differently is still checked against the first copy's size and SHA-256, so its backup fails forever (rare). Related: a re-stamped copy of a different size could be written over the other side's complete copy by adoption. Fix: keep a registered photo's bytes identical to the registered copy, or re-register. (recorded during the build)
- UploadGate.kt:36-37: oneDriveFirst() can hold the backup for about 1 h while a hanging SharePoint keeps uploads queued behind the 3 streams. The crew sees backups lag. Fix: none recorded. (recorded during the build)
- PhotoPipeline.kt:295-297: reconcile runs on every progress write across 5 tables (wasted work during uploads). Fix: none recorded. (recorded during the build)
- OneDriveWorker.kt:214-217: folder candidates are kept only on the phone's copy of the point, so any server change clears them and the folder search runs again. Fix: none recorded. (recorded during the build)
- PhotoPipeline.kt (493 lines) and PipelineTest.kt (1094 lines) are large; the watcher/R81 code and the test fakes could move to their own files. Developers notice. (recorded during the build)
- OneDriveWorker.kt:128-136 and DeliveryWorker.kt:373-381 (`send`): both resume the photo's own stored session before checking what is at the name. If that session is still alive and the file at the name has meanwhile become another non-zero size (replaced by hand), the resume carries on and its FinishUpload may overwrite that file; whether SharePoint allows it is only knowable live. Vanishingly unlikely, since server-assigned counters make names unique. Fix: send an own session that meets another size to the rename branch, so the "write only into a 0-byte file" rule is unconditional. (checked at final review)
- PhotoPipeline.kt:344-349 (`noteJobDeleted`, called from RegisterWorker.kt:61-62): a restore that lands between the server's 410 and this transaction leaves the job marked deleted on the phone until the next job change or snapshot; the photo stays held and uncounted, never lost, and opening the job refreshes it. Fix: read the job row's seq before the register call and mark the job deleted only if that seq is unchanged. (checked at final review)
- Phone upload of a job deleted mid-upload: an upload already running when its job is deleted finishes; a one-upload window, the same as for a deleted point. Fix: none; parked. (recorded during the build)
- FieldmaxxApp.kt:27: the start gate resumes the pipeline only when photo folders exist, so after a kill partway through a delete, the tombstone's finish and its PhotoRemove wait up to the 6-hourly cleanup; the photo stays hidden meanwhile. Fix: start the pipeline at launch whenever a tombstone or a local photo row exists. (checked at final review)
- Tombstones never clear on a phone whose pipeline never starts (for example an admin phone that never captured); harmless, since each delete queued its PhotoRemove with the tombstone. (recorded during the build)
- LocalPhotos.kt:148-155: if the insert fails and the re-check `get` also throws, the folder is discarded even if the row committed, leaving a row with no file; needs a failed insert and a failing read on the same broken database, and shows as a visible "file missing" warning, never a silent loss. Fix: none recorded. (checked at final review)
- Camera storage (T27): the photo folder's directory entry is never fsynced; meta.json is written in place (no temp file and rename; a truncated one counts as never recorded); `PRAGMA synchronous=FULL` is per connection and checked on the writer only, so it would fall back to NORMAL if Android reopened the connection; Shutter.press awaits the tech inside NonCancellable (effectively unreachable). (recorded during the build)
- Camera overlay (T27): the overlay uses a fixed 14 sp row at the bottom, not stampLayout over the preview's image area, so the preview can differ slightly from the stamp. Fix: use stampLayout. (recorded during the build)
- core/.../stamp/Stamp.kt:71-78: a gallery photo with EXIF GPS but no accuracy loses the "±… ft · alt … ft" line and gets a 4-line stamp with no altitude. Techs notice on such photos. Fix: none recorded. (checked at final review)
- The folder-category rule has 3 copies (FolderLine.kt:22-23, FolderResolver.kt:23, PhotosZip.kt:33); a change must be made in all of them. Fix: move it to a core Naming helper. (recorded during the build)
- ReusePicker.kt:119: the `ReuseSources` storage class sits in the 448-line picker file. (recorded during the build)
- Photo folder naming (T9, sharepoint module, no file:line): ensureFolder over a path occupied by a file is not refused and a file at a folder path is treated as an existing folder; a multi-category no-hit picks firstOrNull; partials are not cleaned on a racing EXISTS; chunkBytes <= 0 loops forever; there is no hook to expire a chunk session and no partial-file model. Fix: none recorded. (recorded during the build)
- Destination client (T10, no file:line): local file errors escape as raw exceptions, so callers must treat them as non-transient. (recorded during the build)
- SharePointLinkDestination.kt, `listFolders`: ignores `odata.nextLink` paging, so a very large folder listing may be cut short. Fix: follow the paging; confirm on the pilot's business link first (see "Checks that need the real world"). (recorded during the build)
- Server photo files (T16, no file:line): Thumbs leaks a file handle and does not fsync; a crash between the rename and the DB write forces a re-upload; removing a photo can race its in-flight final chunk; chunkLocks is unbounded; photoFile loads up to 40 MB into memory. (recorded during the build)
- Server photo search (T16): the nearby bounding box is about 1% too small and radiusM is unbounded. (recorded during the build)
- Server photo register/reuse (T16): register does not validate sha/size, and reuse does not validate distance or job. (recorded during the build)
- Server job purge (T16): purgeDeletedJobs deletes thumbnails shared by reuse copies in other jobs. Fix: skip thumbnails referenced by live photos. (recorded during the build)
- PhotoRoutes.kt:54-78: `/api/photos/{id}`, `/thumb` and `/file` serve a deleted job's photo to a tech who has its id. Fix: add the deleted-job guard. (checked at final review)
- Phone photo list (T17 banner): the link banner can flap on a view-only link through a same-size match. (recorded during the build)

## Map

- MapLibreController.kt is 812 lines (bitmap text could move out). (recorded during the build)
- Map view (T24): a rotation within about 500 ms of a pan can restore a stale view (the old screen's NonCancellable save races the new screen's load); the notice `finally` would also clear a replacement notice; zoom +/- do nothing before the first placement; on a read error the config flow emits the defaults over a good value until re-read; the zoom range now 2-22 ignores older saved views outside it. (recorded during the build)
- Map notes: the "29/29" count wording (T24 #12). (recorded during the build)
- Point sheet (T25): hideThen's invokeOnCompletion also fires on cancel, so a double close after a confirmed delete could start a drag on a gone sheet. Fix: `if (it == null) after()`. (recorded during the build)
- PointSheet.kt mixes UI, PointDeps, the PointEditor scheduler and PointTags (428 lines). (recorded during the build)
- suggestName (T26): its POLE branch mixes other layers' pole numbers for a query layer whose points are POLE (the suggestion is editable); an imported layer type with double or trailing spaces would make its suggestion refused (reachability unconfirmed). (recorded during the build)
- HouseNumbers.kt:199: a plain 5xx (502/503) is neither backed off nor cached, and Retry-After is ignored. (recorded during the build)
- HouseNumbers.kt:175: the cache is unbounded for the process lifetime. Fix: cap it (LRU). (recorded during the build)
- HouseNumbers.kt:196: cancelled in-flight requests still count against Overpass. (recorded during the build)
- AppGraph.kt:48,51: overpassHttp duplicates oneDriveHttp. (recorded during the build)
- Sensors (T22): FusedRequester overwrites `callback` on a retry after failure without remove() (harmless). (recorded during the build)

## Jobs and import

- Word reader (T5): mc:AlternateContent walks both Choice and Fallback (possible duplicate text); w:noBreakHyphen/w:softHyphen are dropped (a lost minus is caught by the sign_mismatch flag); lvlRestart, isLgl and lvlOverride-lvl are ignored; startOverride on an unused parent level is not reflected in child labels. (recorded during the build)
- Word reader (T5): no zip-bomb size cap; non-PNG/JPEG images are skipped silently. Fix: none recorded. (recorded during the build)
- Line parser (T1/T6): a hyphen glued to the first number flips the sign (the sign_mismatch flag is the net); a fallback letter can collide; an unlabelled number may duplicate a later explicit number (flagged); a bare "12." lands in notes (noisy). (recorded during the build)
- Label allocation (T2): after B..Z is exhausted it returns a taken name; a PED/POLE "P1" top-level candidate collides (the resolver's Ambiguous path covers it); a description ending "pole 40" can be read as the next label (T4). (recorded during the build)
- PDF reader (T7): Widget/Link/Popup annotations with /Contents are skipped and /RC-only ones dropped; image y assumes a zero crop-box origin and no rotation; the catch-all relabels bugs as unreadable_pdf; tiny decorative images become ImageBlocks. (recorded during the build)
- PDF reader (T7): skipped images and renders are silent (no count shown). (recorded during the build)
- PDF importer (T8): strays are named "Unlabelled N" (spec: layer + sequence); dedupe keeps the first copy's flags; the soffice call has no timeout; a page-N reference is added even when the render failed; description lines like "see POLE 5" go to unread (conservative). (recorded during the build)
- Job create routes (T14): a reused import moves reference images to the last job; files are orphaned on a mid-create failure; client reference ids are unchecked. (recorded during the build)
- Job routes (T14): blocking DB work on the request thread. (recorded during the build)
- Link check (T14, links/LinkRoutes.kt): it fetches arbitrary URLs (SSRF) and `checkLink` catches CancellationException. Fix: add a host allowlist for SharePoint and OneDrive hosts. (recorded during the build)
- Job routes (T14): PATCH /jobs/{id} and POST /jobs/{id}/imports lack the deleted-job guard for techs. Fix: add it as for the other routes. (recorded during the build)
- Job creation (T23): the R44 key survives process death but the lock and draft do not, so a different redo replays the first answer and drops the redo's points; Back during an unsure save drops the key without a warning. (recorded during the build)
- Job info (T23): shows "No notes" before the job is stored (Notes does not check extras != null). (recorded during the build)
- Job screens (T23): the JobList repository lives in JobListScreen.kt (AppGraph imports from a screen file); ShareIntake.kt mixes parsing, file reading, type detection and a screen; CANT_OPEN and CANT_READ_FILE duplicate; MIME is chosen by index into JOB_FILE_TYPES. (recorded during the build)

## Server and delivery

- DeliveryWorker.kt:583-584 with :433-442: "Retry now" drops the stored server session even when it is still alive. On a link that locks, the next round adopts the photo's 0-byte file under a new session and the abandoned one answers 423, so the photo goes through about 90 min of uncounted rechecks and then counted "OneDrive keeps this file locked" attempts until SharePoint times the old session out. Delayed, never duplicated or lost. Whether business links lock is unverified; recheck on the pilot's link. Fix: none recorded. (checked at final review)
- Server and phone (T17): the phone and the server share one attempts count; deliver() does not re-check next_attempt_at. (recorded during the build)
- DeliveryWorker poll loop (T17): safeRun catches Exception, not Throwable, so the poll can die; a throwing fail() gives 5 s retries; stop() is never called and there is no shutdownNow; kick queues behind the poll backlog. (recorded during the build)
- Delivery maintenance (T17/T14): maintenance errors go to System.err and are barely logged; leftover `.download` files remain. (recorded during the build)
- DeliveryWorker (T17): the jobsWaitingForLink scan runs every 5 s. (recorded during the build)
- Server startup (T11): the migration runner splits on ';' (fragile with triggers); a nested tx opens a new connection, and a tx block must not call commit/rollback itself; PRAGMA WAL is re-run per connection. (recorded during the build)
- Server schema (T11/T30): missing indexes on login_failures, sessions.user_id, job_files.job_id, photos.job_id and photos.sha256; the events table has no index on job_id or kind, so activity queries scan it (pre-existing). Fix: add indexes. (recorded during the build)
- Server config (T11): an invalid PORT silently becomes 8080; StatusPages catches CancellationException and has no {error} test; Db is not closed on shutdown. (recorded during the build)
- Live updates (T13): broadcasts may arrive out of seq order (the client dedupes); malformed WS frames are silently ignored and ?since=garbage becomes 0; blocking JDBC runs on the WS coroutine. (recorded during the build)
- Sync ops (T15): an empty PointUpdate still bumps and records; stored failures stick to their opId (clients mint a new opId to retry); point.close does not verify photoIds (offline-first, accepted); `touch` could be DRYed. (recorded during the build)
- Exports (T18): xlsx cells are all text (a typed row model later); CSV lacks a UTF-8 BOM; the photos.zip filename is generic; ZIP duplicate names are dropped silently. (recorded during the build)
- Exports (T18): a backup vanishing mid-stream truncates the ZIP; numKey toLong overflows on huge digit strings; finishLocks is never evicted; safeSegments keeps control characters and trailing dots; a malformed link gives 502 rather than 400. (recorded during the build)
- Phone sync engine (T21): the OutboxWorker retry uses WorkManager's default backoff (30 s doubling, 5 h cap) instead of 2 s to 60 s, which holds only in-session. (recorded during the build)
- Phone sync engine (T21): no CoroutineExceptionHandler on the engine scope, so a local SQLite or decoding failure (for example a full disk) kills the process, on every launch. (recorded during the build)
- Phone sync engine (T21): stale jobs are re-fetched twice per Hello; extras are not seq-gated; MetaKeys lives in OpApplier.kt; SyncEngine.kt is about 490 lines; a stamp-mismatch reset marks the job openJob just snapshotted as stale too (one redundant re-snapshot). (recorded during the build)
- Phone sync engine (T21): the in-flight op record lives in memory, so a process death mid-flight, after a conflicting change was re-applied over, leaves the stale display until that point's next change or snapshot. (recorded during the build)

## Accounts and admin

- Sign-in (T12): username-existence timing leak; /auth/password has no lockout; no maximum password length; no Retry-After; disabled-account attempts count toward lockout. (recorded during the build)
- Sign-in (T12): login_failures and sessions are never purged; sessionUser writes on every request. (recorded during the build)
- Server auth (T12): requireAdmin is applied per handler (fragile for later routes); the CLI exits with code 0 on failure; malformed JSON likely gives 500 rather than 400. (recorded during the build)
- Live updates (T13): changePassword, logout and expiry do not close other live sockets. (recorded during the build)
- Admin settings (T19): the imagery and labels URL settings are not validated. (recorded during the build)
- Sign-in screen (T20): the first vault.load() runs on the main thread at cold start; the server address is saved before login succeeds; a plain-http server address gives a generic message; rotation cancels login; a stale token is sent to /auth/login. (recorded during the build)
- UsersScreen.kt (T30 M5): a rotation during create or reset can lose the temporary password. Fix: move it to viewModelScope. (recorded during the build)
- ActivityScreen.kt (T30 M6): "Restore sent" is never compared with the feed again, so a refused restore still reads "Restore sent". (recorded during the build)
- AdminSettingsScreen.kt:70-76,99-110: edits made before api.config() answers send the phone's stale values, which can undo another admin's change; it needs a second admin and an edit within about a second. Fix: none recorded. (checked at final review)
- server/.../positions/Positions.kt:45-52: a disabled user's last position still shows on every map for up to 1 h (no `u.disabled` filter). Fix: add the filter. (checked at final review)

## App updates

- UpdateInstaller.kt:163 with Nav.kt:221 (JobListScreen.kt:256, UpdateInstaller.kt:163,187): scrolling the update banner out of the job list's LazyColumn cancels the download and loses the allow-installs continue. A second tap downloads again; nothing unverified is ever installed. Fix: none recorded. (checked at final review)
- UpdateInstaller.kt:100-114, Nav.kt:221, Nav.kt:268, MenuSheet.kt:245: two installers (the job list's, the menu's, and the map's) can work on the same file, giving a spurious "Can't reach the server…" or two installer windows, never an unverified APK; it needs two banners tapped within one download. Fix: none recorded. (checked at final review)
- MenuSheet.kt holds StuckPhotos/endSession (AppGraph depends on a UI file); UpdateBanner sits next to the installer. (recorded during the build)
- Release publishing (T19): a concurrent publish can race (CLI-only); `/app/{file}` loads all release rows. (recorded during the build)
- AndroidManifest.xml:21: `POST_NOTIFICATIONS` is declared but never used. Fix: remove it. (checked at final review)

## Deployment

- server/build.gradle.kts:38: the logback version is hardcoded. (recorded during the build)
- The build prints an AGP deprecation notice (T32 M7). (recorded during the build)
- Git history: the owner's real OneDrive account ID is still in earlier pushed commits (the final fix wave removed it from the tree only). Rewriting history is the owner's decision. (checked at final review)
- Build setup (T1): the Android SDK zip checksum is not verified. (recorded during the build)
- server/Dockerfile: the server process runs as root inside its container. If the server were ever compromised, a non-root user (`USER` in the runtime stage, owning the data directory) would limit the damage. (recorded during the build)
- server/Dockerfile: every code update downloads all Gradle dependencies again in the build stage, so README's "5 to 10 minutes the first time" really applies each time. Fix: a BuildKit cache mount for `/root/.gradle`. (recorded during the build)

## Tests

- Importer tests (T1-T8): thin coverage in several places; RealFilesTest asserts only POLE 1/7 (a probe confirms all 7) and does not assert unread is empty; the fixture is single-level only; the R6 "line ending in , still joins" and the numbered coord-only branch / unlabelled-flag-on-orphans cases are untested; photoFileName rounding has no test; bookkeeping tests are thin. (recorded during the build)
- Destination fakes (T9/T10): dropChunkAfter stays armed across uploads; top-level Ambiguous and the category listing cache are untested; the overlap test measures caller-side concurrency only; the parallel-403 reopen is untested. (recorded during the build)
- Server tests (T11-T13, T16-T19): TestApp.current is static; tokenFor swallows errors with runCatching; fileDb leaks a temp dir with test.db; no {error} test for StatusPages; WS tests are thin; T16 and T18 have test gaps and naming issues; no test for original-gone reuse or a confirmed original with a missing file (T17); no photo-event activity test (T19). (recorded during the build)
- App tests (T20/T21): only 3 of 32 routes are contract-tested; login 429 and network tests are missing; applyLocally coverage is thin (PointMove orig coords, Delete/Restore idempotence, SetFolder candidates, photo ops, refusal skips, flush backoff/reset, reconnectDelayMs, R15/R16 on photo and job rows). Fix: a table-driven test against Ops.kt would catch drift. (recorded during the build)
- App tests (T22): FieldmaxxApp.onCreate now builds the real AppGraph and tracker in every Robolectric test, so the `graph` doc comment ("never builds it") is stale; the SourcesTest `deliver()` helper sets SensorEvent fields by reflection; the declination test uses a 1970 time; SourcesTest emits a deprecation warning under --rerun-tasks (T29 M7 is a duplicate). (recorded during the build)
- App tests (T23-T26): untested are R34 navigation and the show-share-once guard, pull-to-refresh, the job-info Save link, the 50 MB refusal, the 400 for keys over 200 characters; moves before the first points, a dialog grant showing the dot, a non-404 ApiException keeping the job, a corrupt saved view, re-tapping a chip; the echo of the sheet's own writes, crew pole edits taken in versus held back, auto-close when the point vanishes; move tests never assert closes == 0; no suggestName to splitName round-trip test over ADD_CATEGORIES and a query layer. (recorded during the build)
- PipelineTest.kt:659 (T28 M4): the refused-delete test fetches the snapshot by hand; R81's own backoff retry is never shown to fetch. FakeDestination also leaves untested the kills between a chunk accepted and its session written (M3). (recorded during the build)
- PhotoUiTest.kt:594/640, 262-263 (T29 M4/M5): no test deletes a photo during its upload; the export's skipping of removed photos is untested; the FileProvider static-cache reflection workaround lives in one test class only. PipelineTest.restart() calls resumeAll() directly, so no pipeline test goes through the app's start gate. (recorded during the build)
- Update and admin tests (T30): no test proves a kept APK is hashed again (overwrite with TAMPERED between calls); untested flows are change password (min 8, mismatch, server error), Refresh's three outcomes, and the banner continuing to the installer after allow-installs. (recorded during the build)
- Flaky tests (T30/T32): a one-off Compose IllegalStateException in an R88 test, and a one-off ConfigStoreTest timeout in a full run. Not re-checked at final review; the last full run had 651 tests, 0 failures, 5 skipped (live). Fix: re-run the class alone and watch it in full runs. (recorded during the build)
- MapScreenTest.kt:774,834 (T31 M5): real Thread.sleep waits (about 1 s each) and negative assertions that can pass for the wrong reason; the zoom-19 pan test passes on the old code too (weak regression test); `postsOnlyTheQueryWithAGenericUserAgentAndNoAuth` still uses a bare client, so its no-auth assertion is redundant. (recorded during the build)
- Live tests (T33 M4-M7): redact/redacting is copied in both live tests (a shared testFixture is possible); the "no stray files" assertion checks the requests, not the folder, so reword its message; the seed uses raw SQL like DeliveryTest; the reuse is covered only from OneDrive, so say so in the class comment. (recorded during the build)

## Docs and checklist

- plans/2026-11-13-fieldmaxx.md:783, 790, 932: the plan text still shows the old rename signatures; the code is authoritative (R82). Plan text, not product. (checked at final review)

## Checks that need the real world

None of these can be answered from the code or from tests; only a pilot on real links, or a real phone, can show them.

- The company (business) OneDrive or SharePoint link's behaviour. The tests and the owner's personal link show no lock, but the crew's business links are unchecked until the pilot (checklist step 44). Four answers are unknown:
  - Lock answers: whether a second session into an open file is refused (a 423 or a lock exception) on a business link. The BUSY recheck, its back-off and "OneDrive keeps this file locked" all depend on it.
  - The lost-session answer: whether a dropped upload session answers with the HTTP 500 session-not-found error seen on the personal link, or with something else.
  - Folder-listing paging: whether `/Folders` listings page, so that `listFolders` (which ignores `odata.nextLink`) would miss folders.
  - The "already exists" answer for folders: how SharePoint answers creating a folder that exists, or a file at a folder path.
- SharePoint's upload-session lifetime is unknown, and so is whether a new StartUpload ends another open session. Checklist step 25 (started in the evening, finished the next morning) is the only device check of the expired-session recovery.
- Esri imagery licensing: whether the map's Esri imagery may be used this way is an open item in spec section 9 (docs/superpowers/specs/2026-11-13-fieldmaxx-design.md) for the owner to settle.
- Device-only behaviour covered by docs/acceptance-checklist.md: MapLibre rendering (clusters, the badge, crew labels, the blue dot, offline tiles), touches during a fling, double-tap +, pinch while a job loads, the move bar passing map touches through, CameraX, the compass and the photo picker are compile-verified only, not run on a device.

## Batch 1 (quick wins, 2026-11-15)

Left for later after batch 1's final review. None loses data.

### Renaming
- **Re-casing an imported type.** `canonicalType` (core naming) checks the add-point categories before the point's own type. A number-only rename can therefore re-case an imported type that differs from a category only in case. Fix: check the point's current type first.
- **Old app builds.** Phones still on a build from before batch 1 send folder choices without a label, so a stale choice could still apply after a rename. This closes once every phone updates.
- **Rename warning.** It doesn't count photos that were delivered and later hidden in the app.
- **Type chip highlight.** It compares case-sensitively.

### Deleting
- **Delete dialog.** It doesn't re-check delivery while it is open.

### Tags
- **Refresh can hide a new tag.** A settings refresh that arrives before the server has applied a new tag hides that tag's chip until the next refresh. The tag stays on the point.
- **Failed tag actions.** Admin tag actions don't re-read the list when the action itself fails.
- **Malformed list.** `readTags` (server) throws if the stored tag list is malformed JSON.
- **Extra change rows.** A tag rename's de-duplication also touches points that hold another tag twice in different cases. Those points get an unneeded change row; the result is correct.

### Stamp
- **Wrapping.** It can split an emoji (a surrogate pair), and wrapping at " · " isn't tested.
- **Preview first line.** It reads " · POLE 18" until the job name has loaded.
- **Off-screen messages.** Tag messages in Admin settings show in the message line above the Tags section, so they can be off screen.

### Map
- **Overlapping markers.** With clustering gone, a tap on overlapping markers at low zoom picks the top one. Zoom in to reach the others.

### Camera
- **Rapid taps.** Tapping Take photo very fast can trigger the camera twice.

### Windows development
- **Five test classes fail on Windows.** JobsScreensTest, MapModelTest, MenuAdminUpdateTest, PhotoUiTest and PipelineTest assume Linux file paths and file deletion: FileProvider temp roots and locked files. They pass on Linux. PipelineTest's failure count also varies from run to run.
- **DataStore file.** On Windows, DataStore can't replace its file after the first write, so ConfigStoreTest uses an in-memory store. Android is not affected.
