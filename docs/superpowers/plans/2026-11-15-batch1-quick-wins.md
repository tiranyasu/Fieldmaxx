# Batch 1 Quick Wins Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Four changes to the shipped Fieldmaxx app:
- markers never cluster;
- reuse photos by nearest point;
- a ⋮ menu on each point, with rename;
- one shared tag list for the crew, with the tags, HOA and comment printed on the stamp.

**Architecture:**
- `core` gains the stamp's extra line, a `point.rename` op and the tag list in `AppConfig`.
- The server stores the tag list as a settings row. It grows the list from tags it sees in point
  updates, and lets an admin rename or delete tags.
- The app removes clustering, regroups the Nearby reuse tab by point, moves the point actions into a
  ⋮ menu, and shows tag chips on every point type.

**Tech Stack:** Kotlin, Ktor 3, SQLite, Jetpack Compose, MapLibre, Room, Robolectric (all existing).

**Spec:** `docs/superpowers/specs/2026-11-15-batch1-quick-wins-design.md`. It amends
`docs/superpowers/specs/2026-11-13-fieldmaxx-design.md`.

## Global Constraints

- Everything in the original plan's Global Constraints still holds
  (`docs/superpowers/plans/2026-11-13-fieldmaxx.md`), with one exception: the stamp may have a sixth
  line, as defined below.
- The stamp's sixth line appears only when non-empty:
  - it holds the point's tags in list order, then `HOA <line height>` (POLE points with a line height
    only), then the point's comment;
  - the parts are joined by ` · `;
  - the line height prints with no trailing `.0`, so `23.5` and `24`.
- Starting tag list: `N/A`, `Carrier label`, `Grounding`, `No ground`, `No fiber`, `Damaged cable`
  (`POLE_TAGS`).
- Tags live in `Point.pole.tags` (`PoleData`) for every point type. Non-POLE points use only `tags`.
- Renaming a point never moves or renames anything in OneDrive. It clears `folder_path` and
  `folder_candidates_json`, so photos delivered after the rename resolve the new folder.
- Delete rules don't change: a tech may delete a point until a photo of it is confirmed in OneDrive;
  after that, only an admin may.
- Nothing about OneDrive delivery, backups or retries changes.
- No real identifiers in repo files: fake links only (`https://1drv.ms/f/c/0000000000000000/Field-Notes`).
- Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A tag added offline.** The PointUpdate waits in the outbox; once it's applied, the server adds
   the tag to the shared list, and other phones get it with the next config refresh. Test: Task 2,
   `unknownTagInUpdateJoinsList`.
2. **A tag renamed while a phone holds the old name on a point.** The point still shows the old tag,
   and nothing is lost. Tests: Task 2, `renameRetagsPoints`; Task 5, `pointKeepsTagMissingFromList`.
3. **A photo killed mid-pipeline after a tag change.** The printed sixth line is the one recorded at
   the shutter, read back from meta.json. A meta.json written before this batch still reads. Test:
   Task 5, `extraLineSurvivesMetaRoundTrip`.
4. **A long comment.** It wraps the same way in the preview and the printed stamp, because both call
   `stampLines`. Test: Task 1, `longExtraWraps`.
5. **Renaming a point that has registered, undelivered photos.** They keep their file names, and the
   rename warning names how many photos are already in OneDrive. Test: Task 4,
   `renameWarnsWhenDelivered`.

---

### Task 1: core: stamp extra line, rename op, tags in config

**Files:**
- Modify: `core/src/main/kotlin/com/fieldmaxx/core/stamp/Stamp.kt`
- Modify: `core/src/main/kotlin/com/fieldmaxx/core/model/Models.kt`
- Test: `core/src/test/kotlin/com/fieldmaxx/core/stamp/StampTest.kt`
- Test: `core/src/test/kotlin/com/fieldmaxx/core/model/ModelsTest.kt` (create it if absent)

**Interfaces:**
- Produces:
  - `StampMeta.extra: String? = null`. `stampLines` appends it, wrapped, after the time line.
  - `fun stampExtraLine(type: String, pole: PoleData?, comment: String): String?`, in `Stamp.kt`.
    Returns null when empty.
  - `const val STAMP_LINE_MAX = 48`. `stampLines` wraps the extra line at ` · ` boundaries, else at
    spaces, so that no line exceeds this many characters. A single word longer than that is cut.
  - `Op.PointRename(opId: String, pointId: String, type: String, num: String)`, with
    `@SerialName("point.rename")`.
  - `AppConfig.tags: List<String> = POLE_TAGS`.
  - `@Serializable data class TagRename(val from: String, val to: String)`.

- [ ] **Step 1: Write failing tests** in StampTest:
  - `extraLineOrder`:
    `stampExtraLine("POLE", PoleData(lineHeightFt = 23.5, tags = listOf("Grounding","No fiber")), "lean")`
    equals `"Grounding · No fiber · HOA 23.5 · lean"`.
  - `extraLineHeightFormat`: `PoleData(lineHeightFt = 24.0)` gives `"HOA 24"`.
  - `extraLineNonPoleHasNoHoa`: `stampExtraLine("PED", PoleData(lineHeightFt = 5.0, tags = listOf("N/A")), "")`
    equals `"N/A"`.
  - `extraLineEmptyIsNull`: `stampExtraLine("POLE", null, "  ")` is null.
  - `extraAppendedLast`: `stampLines(base.copy(extra = "N/A"))` equals `SPEC_STAMP + "N/A"`. The five
    spec lines are unchanged when `extra` is null.
  - `longExtraWraps`: an extra of 120 characters gives lines that are each at most 48 characters, and
    joining them with ` ` gives back the words in order.

  In ModelsTest, `renameOpRoundTrips`: `Op.PointRename` encodes with `"kind":"point.rename"` and
  decodes back equal. `AppConfig` JSON without `tags` decodes to `POLE_TAGS`.
- [ ] **Step 2:** Run `./gradlew :core:test`. Expected: compile failure.
- [ ] **Step 3: Implement** the interfaces above.
- [ ] **Step 4:** Run `./gradlew :core:test`. Expected: PASS.
- [ ] **Step 5: Commit:** `feat(core): stamp tags/HOA/comment line, point.rename op, tag list in config`

### Task 2: server: rename op and the shared tag list

**Files:**
- Modify: `server/src/main/kotlin/com/fieldmaxx/server/ops/Ops.kt`
- Modify: `server/src/main/kotlin/com/fieldmaxx/server/admin/AdminRoutes.kt`
- Test: `server/src/test/kotlin/com/fieldmaxx/server/OpsTest.kt`
- Test: `server/src/test/kotlin/com/fieldmaxx/server/AdminTest.kt`

**Interfaces:**
- Consumes: Task 1's `Op.PointRename`, `AppConfig.tags`, `TagRename`, and `pointLabel(layer, type, num)`
  from `core.naming`.
- Produces:
  - **`point.rename`:** any signed-in user on a live point; refused with `point_deleted` /
    `job_deleted` as the other point ops are. In one touch, it sets:
    - `type` (trimmed and upper-cased);
    - `num` (trimmed);
    - `label = pointLabel(layer, type, num)`;
    - `folder_path = NULL` and `folder_candidates_json = NULL`.

    It refuses a blank type or num with `OpFail("needs_text")`. It records the event kind
    `point.rename`.
  - **The tag list:** a settings row with key `tags`, holding a JSON array.
    `appConfig(ctx).tags` returns it, or `POLE_TAGS` when the row is absent.
  - **Growing the list:** when `point.update` carries tags not in the list (compared
    case-insensitively), the server appends them in the same transaction, keeping the spelling first
    seen.
  - **`PUT /api/admin/tags`** (admin) with a body `List<String>` replaces the list. That covers
    reorder and delete. Entries are trimmed, blanks dropped, case-insensitive duplicates dropped.
    Points keep any tag that is no longer listed.
  - **`POST /api/admin/tags/rename`** (admin) with a `TagRename` body:
    - It renames the tag in the list, and in the `pole_json.tags` of every point of a live job that
      has it. Each changed point goes through the change log, so phones update.
    - It refuses `400` when `to` is blank or already listed (case-insensitively), and `404` when
      `from` isn't listed.

- [ ] **Step 1: Write failing tests:**
  - **OpsTest:**
    - `renameSetsTypeNumLabelAndClearsFolder`: a point with `folder_path` set is renamed to POLE 14B.
      The label becomes `pointLabel(layer,"POLE","14B")`, the folder fields become null, and one
      change is recorded.
    - `renameBlankRefused`: refused with `needs_text`.
    - `renameDeletedPointRefused`: refused with `point_deleted`.
    - `unknownTagInUpdateJoinsList`: a `point.update` with tags `["grounding","Rotten"]` leaves the
      config list as `POLE_TAGS + "Rotten"`. "grounding" is not added a second time.
  - **AdminTest:**
    - `tagsDefaultToPoleTags`.
    - `putTagsReplacesListAdminOnly`: a tech gets 403; the result is trimmed and de-duplicated.
    - `renameRetagsPoints`: two points with "No fiber" and one without. Renaming to "No FO" changes
      both points and records two changes; the third point is untouched.
    - `renameToExistingRefused`: answers 400.
    - `renameUnknownRefused`: answers 404.
- [ ] **Step 2:** Run
  `./gradlew :server:test --tests '*OpsTest*' --tests '*AdminTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.** Read and write the tags row the same way as the other settings in
  AdminRoutes. Point changes go through the same recorder that `touch` uses.
- [ ] **Step 4:** Run the same command. Expected: PASS. Then run `./gradlew :server:test`, which must
  stay green.
- [ ] **Step 5: Commit:** `feat(server): point.rename op; one shared tag list, grown by updates, admin rename/replace`

### Task 3: app: no clustering, reuse by nearest point

**Files:**
- Modify: `app/src/main/kotlin/com/fieldmaxx/app/map/MapLibreController.kt` (the cluster source
  option and the cluster layers, `L_CLUSTERS`, `L_CLUSTER_COUNTS`, `CLUSTER_MAX_ZOOM`, and the cluster
  tap branch near `queryRenderedFeatures`)
- Modify: `app/src/main/kotlin/com/fieldmaxx/app/photos/ReusePicker.kt`
- Test: `app/src/test/kotlin/com/fieldmaxx/app/photos/ReusePickerTest.kt`

**Interfaces:**
- Produces:
  - `data class NearbyPoint(val pointId: String, val pointLabel: String, val jobName: String, val distanceM: Double?, val photos: List<ReuseCandidate>)`.
  - `fun nearbyByPoint(candidates: List<ReuseCandidate>): List<NearbyPoint>`. It groups by
    `photo.pointId` and sorts by `distanceM` ascending (null last, then by label). Within a point,
    photos are newest first.
- Clustering: the points `GeoJsonSource` has no cluster options. The cluster layers, the count
  images and the cluster-tap zoom are removed. Point and code layers keep `iconAllowOverlap(true)` /
  `textAllowOverlap(true)`, so no marker is hidden at low zoom.

- [ ] **Step 1: Write failing tests:**
  - `nearbyGroupsByPointNearestFirst`: photos of points at 40 m, 10 m and 40 m (the same point twice)
    give two groups. The 10 m point comes first, and the 40 m group holds both of its photos, newest
    first.
  - `nearbyTabListsPointsThenPhotos`: a Compose test. The Nearby tab shows point rows with their
    distance, like "POLE 5 · A05J9XE · 10 m". Tapping a row shows that point's photos to pick. Back
    returns to the point list. Picking still sends one `Op.PhotoReuse` per photo.
- [ ] **Step 2:** Run
  `./gradlew :app:testDebugUnitTest --tests '*ReusePickerTest*'`. Expected: FAIL.
- [ ] **Step 3: Implement.** In the picker's Nearby branch, use `nearbyByPoint` on the fetched
  candidates, replacing the newest-first sort (this supersedes R87). Update the KDoc that mentions
  R87. Remove clustering as above.
- [ ] **Step 4:** Run `./gradlew :app:testDebugUnitTest --tests '*ReusePickerTest*' --tests '*MapScreenTest*'`.
  Expected: PASS.
- [ ] **Step 5: Commit:** `feat(app): markers never cluster; Nearby reuse lists points nearest first`

### Task 4: app: the ⋮ point menu and renaming

**Files:**
- Modify: `app/src/main/kotlin/com/fieldmaxx/app/point/PointSheet.kt` (`Header`, `OpenSheet`)
- Modify: `app/src/main/kotlin/com/fieldmaxx/app/point/DeletePoint.kt` (delete is reached from the
  menu)
- Create: `app/src/main/kotlin/com/fieldmaxx/app/point/RenamePoint.kt`
- Modify: `app/src/main/kotlin/com/fieldmaxx/app/sync/OpApplier.kt`
- Test: `app/src/test/kotlin/com/fieldmaxx/app/point/PointSheetTest.kt`
- Test: `app/src/test/kotlin/com/fieldmaxx/app/sync/OpApplierTest.kt`

**Interfaces:**
- Consumes: Task 1's `Op.PointRename`, and `pointLabel` and `ADD_CATEGORIES` from `core.naming`.
- Produces:
  - **Test tags:** `PointTags.MENU = "point.menu"`, `PointTags.RENAME = "point.rename"`,
    `PointTags.RENAME_TYPE = "point.rename.type"`, `PointTags.RENAME_NUM = "point.rename.num"`,
    `PointTags.RENAME_SAVE = "point.rename.save"`.
  - **The header** shows the title, then a ⋮ `IconButton` (`MENU`), then close. The menu items are
    "Edit / Rename", "Move point" (`MOVE`), "Directions" (`NAVIGATE`, the existing `navigate`) and,
    when `canDeletePoint` allows, "Delete point" (`DELETE`, which opens the existing confirm).
  - **The rename dialog** has:
    - a type field, prefilled, with chips from the job's types plus `ADD_CATEGORIES`;
    - a number field;
    - Save, which sends `Op.PointRename`;
    - the warning, shown when any of the point's photos is confirmed:
      "N photos of this point are already in OneDrive in <folder>. New photos will go to the new name's folder; those stay where they are."
  - **`OpApplier`** applies `PointRename` locally: type, num, `label = pointLabel(layer,type,num)`,
    `folderPath = null` and `folderCandidates = null`.

- [ ] **Step 1: Write failing tests:**
  - **PointSheetTest:**
    - `menuHasRenameMoveDirectionsDelete`.
    - `deleteHiddenForTechWhenDelivered`.
    - `renameSendsOp`: renaming to PED 7 sends `PointRename(pointId, "PED", "7")`.
    - `renameWarnsWhenDelivered`: two confirmed photos show the warning with "2 photos".
    - `renameBlankDisabled`.
  - **OpApplierTest:** `renameAppliesLocally`.
- [ ] **Step 2:** Run
  `./gradlew :app:testDebugUnitTest --tests '*PointSheetTest*' --tests '*OpApplierTest*'`. Expected:
  FAIL.
- [ ] **Step 3: Implement.** Existing tests that find the Navigate and Move buttons directly now open
  the menu first; update them.
- [ ] **Step 4:** Run the same command. Expected: PASS.
- [ ] **Step 5: Commit:** `feat(app): point ⋮ menu with rename, move, directions and delete`

### Task 5: app: shared tags on every point, the stamp line, tag admin

**Files:**
- Modify: `app/src/main/kotlin/com/fieldmaxx/app/point/PoleDataForm.kt` (split out a `TagChips`
  composable)
- Modify: `app/src/main/kotlin/com/fieldmaxx/app/point/PointSheet.kt` (tags for every type; the stamp
  preview above Take photo)
- Modify: `app/src/main/kotlin/com/fieldmaxx/app/photos/CaptureMeta.kt`
- Modify: `app/src/main/kotlin/com/fieldmaxx/app/camera/CameraScreen.kt`
- Modify: `app/src/main/kotlin/com/fieldmaxx/app/camera/StampOverlay.kt`
- Modify: `app/src/main/kotlin/com/fieldmaxx/app/photos/LocalPhotos.kt`
- Modify: `app/src/main/kotlin/com/fieldmaxx/app/admin/AdminSettingsScreen.kt`
- Modify: `app/src/main/kotlin/com/fieldmaxx/app/net/Api.kt` and
  `app/src/main/kotlin/com/fieldmaxx/app/net/OkHttpApi.kt`
- Test: `app/src/test/kotlin/com/fieldmaxx/app/point/PointSheetTest.kt`
- Test: `app/src/test/kotlin/com/fieldmaxx/app/camera/CameraTest.kt`
- Test: the existing admin settings test class

**Interfaces:**
- Consumes:
  - Task 1's `stampExtraLine`, `StampMeta.extra` and `AppConfig.tags`;
  - Task 2's two admin routes.
- Produces:
  - **`CaptureMeta.extra: String? = null`**, copied into `stampMeta`. `cameraMeta(..., extra: String?)`
    and `galleryMeta(..., extra: String?)` take it. The camera screen computes it from the point at the
    shutter press.
  - **`fun tagChoices(listed: List<String>, onPoint: List<String>): List<String>`.** It returns the
    listed tags, then any tag on the point that isn't listed.
  - **The tag chips** show on every point type, with a `+ New tag` chip (`PointTags.NEW_TAG`). It
    opens a one-field dialog, max 40 characters, that adds the tag to the point via `editPole`. The
    tag shows at once, and the server lists it once the update lands.
  - **The stamp preview** (`PointTags.STAMP_PREVIEW`) sits above Take photo. It shows
    `stampLines(cameraMeta(jobName, label, fix, heading, now, extra).stampMeta(zone))` and refreshes
    every second. `PointDeps` gains `heading: StateFlow<Heading?>` and `jobName(): String`.
  - **The admin screen** gets a "Tags" section: rename (`Api.renameTag(from, to)`), delete and
    reorder (`Api.putTags(list)`).

- [ ] **Step 1: Write failing tests:**
  - **PointSheetTest:**
    - `tagsOnPedPoint`: a PED point shows the tag chips but no line height.
    - `newTagAddsToPoint`: typing "Rotten" sends `PointUpdate` with `pole.tags` containing it.
    - `pointKeepsTagMissingFromList`: a tag on the point that isn't in `config.tags` still shows,
      selected.
    - `previewShowsExtraLine`: a POLE point with "Grounding", 23.5 and the comment "lean" has a
      preview whose last line is `Grounding · HOA 23.5 · lean`.
  - **CameraTest:**
    - `shutterRecordsExtra`.
    - `extraLineSurvivesMetaRoundTrip`: CaptureMeta JSON with `extra` round-trips. The JSON of a
      pre-batch meta (no `extra`) decodes with `extra == null` and prints the five lines.
  - **Admin test:** `renameTagCallsApi`.
- [ ] **Step 2:** Run
  `./gradlew :app:testDebugUnitTest --tests '*PointSheetTest*' --tests '*CameraTest*' --tests '*AdminSettings*'`.
  Expected: FAIL.
- [ ] **Step 3: Implement.** The Stamper draws whatever `stampLines` returns: check that six or more
  lines lay out with `stampLayout(width, height, lines.size)`.
- [ ] **Step 4:** Run the same command, then the full suite:
  `./gradlew test :app:testDebugUnitTest`. Expected: all PASS, with only the live tests skipped.
- [ ] **Step 5: Commit:** `feat(app): one crew tag list on every point, tags/HOA/comment on the stamp, tag admin`
