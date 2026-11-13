# Fieldmaxx (Android) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Fieldmaxx: a native Android app plus a small Kotlin server. The crew imports AT&T job files (PDF/DOCX), works the points on a satellite map, records pole data, and takes stamped photos. The phone uploads those photos straight into each job's OneDrive "Field Notes" folder, and also backs them up to our server.

**Architecture:** One Gradle repo, Kotlin everywhere, with five modules:
- `core`: shared models and pure rules.
- `sharepoint`: the OneDrive share-link client and the folder rules; used by both the app and the server.
- `importer`: PDF/DOCX reading; server only.
- `server`: Ktor + SQLite + WebSocket.
- `app`: Android, Compose, MapLibre, CameraX, Room, WorkManager.

Every photo moves through persisted steps on the phone (saved → stamped → registered → in OneDrive + backed up). The server keeps backups at least 3 days and delivers a photo itself if the phone hasn't within 30 minutes.

**Tech Stack:** Kotlin 2.1, JDK 21 toolchain (JVM bytecode 17), Gradle 8.14.3, Android Gradle Plugin 8.10, compileSdk/targetSdk 35, minSdk 28.
- **App:** Jetpack Compose (BOM 2025.05), Navigation Compose, Room 2.7 (KSP), WorkManager 2.10, DataStore 1.1, CameraX 1.4, MapLibre Native Android 11.8, Play Services Location 21.3, androidx.exifinterface 1.4, Coil 3.
- **Shared / server:** kotlinx.serialization 1.8, kotlinx.coroutines 1.10, OkHttp 4.12 (+ MockWebServer), Ktor 3.1 (Netty, WebSockets, content negotiation, test host), xerial sqlite-jdbc 3.49, Bouncy Castle 1.80 (Argon2), Apache PDFBox 3.0, dhatim fastexcel 0.19, commons-suncalc 3.11.
- **Tests:** JUnit 5 + kotlin.test (JVM modules); JUnit 4 + Robolectric 4.14 + Compose UI test + work-testing (app).

Use these versions or newer patch releases; check each one resolves at the first build.

**Spec:** `docs/superpowers/specs/2026-11-13-fieldmaxx-design.md` (v2, approved). Read it before any task; `§` below refers to it.

## Global Constraints

- Packages `com.fieldmaxx.<module>`; applicationId `com.fieldmaxx.app`; minSdk 28, targetSdk/compileSdk 35; `core` and `sharepoint` compile to JVM 17 bytecode (the app consumes them).
- **Foreground-only location:** the manifest declares only `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`. Never `ACCESS_BACKGROUND_LOCATION`, never a location foreground service. Location updates run only while the app is in the STARTED lifecycle state.
- Accepted job files: PDF and `.docx` only. Anything else → HTTP 415 with message `Only PDF and Word (.docx) files`.
- No point is ever silently dropped: every coordinate becomes a point or shows on the check screen as flagged/unread.
- Never overwrite or delete anything in OneDrive (the share link has no delete permission, §5.5). Check for a same-name, same-size file before every upload. The only exception: a partial file this photo created in a lost chunked session may be restarted.
- Point folder: reuse an existing folder (any common naming style, §6.6.1), else create `<Category>/<TYPE>_<ID>` (`POLE/POLE_33`, `QUERY 1/Query_1-43`).
- Photo file name `<TYPE>_<ID>_<lat 6dp>_<lon 6dp>_<counter>.jpg`, e.g. `POLE_1_39.636130_-119.691104_6018.jpg`. The server assigns the counter.
- Stamp (bottom-left, white bold, dark shadow, font = 2.8% of the shorter side, 5 lines): `A05J9XE · POLE 1` / `39.636130, -119.691104` / `±16 ft · alt 4430 ft` / `NE 47°` / `11/05/26 13:35:47`.
- Stamped copy: JPEG quality 85 (Standard) or 95 (Maximum, admin setting); scaled down to ≤ 16,000,000 pixels; EXIF written (GPS, altitude, image direction, DateTimeOriginal, make/model).
- Phone → OneDrive: chunked upload, up to 3 photos in parallel. Server backup runs alongside at lower priority.
- Retries: 15 s doubling to a 30 min cap; "stuck" after 6 failed attempts, still retried every 30 min.
- Server backups kept ≥ 3 days after arrival (admin setting, default 3, minimum 3), never deleted before OneDrive confirmation. The server delivers a backup itself if it is unconfirmed 30 min after arrival. Permanent ~320 px thumbnails.
- The phone deletes its own copies 3 days after a photo is both confirmed and backed up. A gallery copy goes to album "Fieldmaxx" (setting, default on).
- Sessions: bearer token, 90 days sliding, stored encrypted on the phone. Lockout: 10 failures per username per 15 min → HTTP 429.
- Crew positions: send on > 5 m moved or every 15 s; on leaving the screen send `paused` once and stop. Dots: grey "app closed" when paused, faded after 10 min, hidden after 1 h.
- Soft deletes. Job: deletable by its creator or an admin; admin restores within 30 days, then purge. Point: any user while no photo of it reached OneDrive, otherwise admin only; admin restores.
- Map defaults served by `/api/config`: imagery `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}`, labels `https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}` (opacity 0.9), optional key.
- Colours: bars `#0f172a` at 92% opacity, accent `#2563eb`, done `#22c55e`, warning `#fbbf24`, shot markers `#9ca3af` at 55% opacity.
- English only. Secrets never committed: the live test link comes only from env `FMX_LIVE_TEST_LINK`; real sample files live in git-ignored `importer/src/test/resources/private/`; the release keystore and `keystore.properties` are git-ignored.

## Review Focus

1. **Phone dies or Android kills the app between any two photo steps** (after save, mid-stamp, mid-upload, before reporting): on restart the photo continues and lands in OneDrive exactly once. Test: Task 28 `pipelineSurvivesAKillAfterEveryStep`.
2. **Signal drops halfway through a chunked upload, or the upload session expires overnight**: resume from the last confirmed offset, or restart cleanly with no duplicate file. Tests: Task 10 `resumesAtConfirmedOffset`, `lostSessionRestartsOnOwnPartialFile`.
3. **Two techs change the same point at once** (one fills pole data, the other drags it): both changes survive. Test: Task 15 `concurrentFieldEditsBothPersist`.
4. **Field Notes link wrong, expired or sign-in-only**: readable error at entry, red banner, photos wait on the phone and on the server. Tests: Task 14 `linkCheckReportsFailureReason`, Task 17 `forbiddenDestinationSetsJobDeliveryProblem`.
5. **The same file imported twice into a job**: no doubled points. Test: Task 14 `duplicateImportSkipsIdenticalPoints`.

---

## Milestone A: Repo and `core`

### Task 1: Gradle repo, Android SDK script, shared models, coordinate parsing

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `.gitignore`, `gradlew` (+ wrapper files), `tools/android-sdk.sh`
- Create: `core/build.gradle.kts`, `core/src/main/kotlin/com/fieldmaxx/core/model/Models.kt`, `core/src/main/kotlin/com/fieldmaxx/core/Json.kt`, `core/src/main/kotlin/com/fieldmaxx/core/geo/Geo.kt`
- Test: `core/src/test/kotlin/com/fieldmaxx/core/geo/GeoTest.kt`, `core/src/test/kotlin/com/fieldmaxx/core/model/JsonTest.kt`

**Interfaces:**
- Produces (`geo/Geo.kt`):
```kotlin
data class LatLon(val lat: Double, val lon: Double)
enum class CoordFormat { DECIMAL, DMS }
data class CoordMatch(val lat: Double, val lon: Double, val start: Int, val end: Int, val swapped: Boolean, val format: CoordFormat)
fun normalizeText(raw: String): String
fun findCoordinatePairs(text: String): List<CoordMatch>
fun distanceMeters(a: LatLon, b: LatLon): Double
fun metersToFeet(m: Double): Double
```
- Produces (`Json.kt`): `val FmxJson: Json` (`ignoreUnknownKeys = true`, `encodeDefaults = true`, `classDiscriminator = "kind"`).
- Produces (`model/Models.kt`): all `@Serializable`; enum constants are lower-case so JSON matches the spec's wording.
```kotlin
enum class Role { admin, tech }
data class User(val id: String, val username: String, val displayName: String, val role: Role, val disabled: Boolean)
enum class PointStatus { open, done, cant_shoot }
enum class CantShootReason { not_found, no_access, not_applicable, other }
val POLE_TAGS: List<String> = listOf("N/A", "Carrier label", "Grounding", "No ground", "No fiber", "Damaged cable")
enum class Attachment { self_supporting, lashed }
data class PoleData(val lineHeightFt: Double? = null, val anchor: Boolean = false, val attachment: Attachment? = null, val tags: List<String> = emptyList())
enum class ImportFlag { swapped, sign_mismatch, far_from_job, duplicate_label, unlabelled, no_coordinates }
data class DraftPoint(val layer: String, val type: String, val num: String, val label: String, val lat: Double?, val lon: Double?, val description: String, val flags: List<ImportFlag>, val sourcePage: Int?, val refImageIds: List<String>, val rawText: String)
data class UnreadLine(val text: String, val sourcePage: Int?, val reason: String)
data class RefImageInfo(val id: String, val kind: String /* page|item */, val page: Int?)
data class ImportPreview(val importId: String, val fileName: String, val kind: String /* pdf|docx */, val layerSuggestion: String, val notes: String, val points: List<DraftPoint>, val unread: List<UnreadLine>, val refImages: List<RefImageInfo>)
data class Job(val id: String, val name: String, val notes: String, val fieldNotesUrl: String?, val status: String /* active|finished */, val createdBy: String, val createdAt: Long, val finishedAt: Long?, val deletedAt: Long?, val deliveryProblem: String?)
data class JobListItem(val job: Job, val pointsTotal: Int, val pointsDone: Int)
data class Point(val id: String, val jobId: String, val layer: String, val type: String, val num: String, val label: String, val lat: Double, val lon: Double, val origLat: Double?, val origLon: Double?, val description: String, val status: PointStatus, val cantReason: CantShootReason?, val cantText: String?, val pole: PoleData?, val comment: String, val folderPath: String?, val folderCandidates: List<String>?, val refImageIds: List<String>, val flags: List<ImportFlag>, val addedBy: String?, val updatedAt: Long, val updatedBy: String?, val deletedAt: Long?)
enum class PhotoStatus { registered, confirmed, stuck, removed }
data class PhotoInfo(val id: String, val jobId: String, val pointId: String, val userId: String, val fileName: String?, val size: Long, val status: PhotoStatus, val destPath: String?, val lastError: String?, val takenAt: Long, val lat: Double?, val lon: Double?, val backedUp: Boolean, val confirmedAt: Long?, val uploadMs: Long?, val reusedFrom: String?)
data class PhotoRegistration(val id: String, val jobId: String, val pointId: String, val size: Long, val sha256: String, val takenAt: Long, val lat: Double?, val lon: Double?, val accuracyM: Double?, val headingDeg: Double?)
data class RegisteredPhoto(val fileName: String, val status: PhotoStatus, val backupOffset: Long)
data class CrewPosition(val userId: String, val displayName: String, val lat: Double, val lon: Double, val accuracyM: Double, val at: Long, val paused: Boolean)
sealed interface Op { val opId: String
  // nested data classes with @SerialName:
  // "point.update" Op.PointUpdate(opId, pointId, pole: PoleData? = null, comment: String? = null)
  // "point.move" Op.PointMove(opId, pointId, lat, lon)                "point.close" Op.PointClose(opId, pointId, photoIds: List<String>)
  // "point.cantShoot" Op.PointCantShoot(opId, pointId, reason, text)  "point.reopen" Op.PointReopen(opId, pointId)
  // "point.add" Op.PointAdd(opId, jobId, pointId, layer, type, num, lat, lon)
  // "point.delete" Op.PointDelete(opId, pointId)                      "point.restore" Op.PointRestore(opId, pointId)
  // "point.setFolder" Op.PointSetFolder(opId, pointId, folderPath)
  // "photo.reuse" Op.PhotoReuse(opId, photoId, fromPhotoId, pointId)  "photo.remove" Op.PhotoRemove(opId, photoId)
  // "photo.confirmed" Op.PhotoConfirmed(opId, photoId, destPath, uploadMs: Long)   "photo.failed" Op.PhotoFailed(opId, photoId, error, attempts: Int)
}
data class OpResult(val opId: String, val ok: Boolean, val error: String? = null)
data class Change(val seq: Long, val jobId: String?, val entity: String /* job|point|photo */, val entityId: String, val job: Job? = null, val point: Point? = null, val photo: PhotoInfo? = null, val at: Long)
sealed interface ServerMessage   // nested: "change" ServerMessage.ChangeMsg(change)  "position" ServerMessage.PositionMsg(pos)  "hello" ServerMessage.Hello(seq: Long)
sealed interface ClientMessage   // nested: "position" ClientMessage.PositionUpdate(lat, lon, accuracyM, altitudeM: Double?)  "paused" object ClientMessage.Paused
data class JobFileInfo(val id: String, val name: String, val layer: String)
data class CrewMember(val userId: String, val displayName: String)
data class JobSnapshot(val job: Job, val points: List<Point>, val photos: List<PhotoInfo>, val refImages: List<RefImageInfo>, val files: List<JobFileInfo>, val crew: List<CrewMember>, val seq: Long)
data class JobImportInput(val importId: String, val layer: String, val notes: String, val points: List<DraftPoint>)
data class CreateJobInput(val name: String, val fieldNotesUrl: String?, val imports: List<JobImportInput>)
data class LinkCheck(val ok: Boolean, val rootName: String? = null, val error: String? = null)
data class FinishResult(val finished: Boolean, val pointsLeft: Int = 0, val photosPending: Int = 0)
data class AppConfig(val imageryUrl: String, val labelsUrl: String, val imageryKey: String?, val photoQuality: Int /* 85|95 */, val backupDays: Int)
data class AdminSettings(val photoQuality: Int? = null, val backupDays: Int? = null, val imageryUrl: String? = null, val labelsUrl: String? = null, val imageryKey: String? = null)
data class HealthReport(val diskUsedPct: Int, val photos: List<PhotoInfo>, val phoneToConfirmedMedianS: Long?, val uploadMedianS: Long?)
data class ActivityItem(val at: Long, val user: String, val kind: String, val label: String?, val detail: String)
data class AppRelease(val versionCode: Int, val versionName: String, val url: String, val sha256: String)
data class ReuseCandidate(val photo: PhotoInfo, val pointLabel: String, val jobName: String, val distanceM: Double?)
```

- [ ] **Step 1: Scaffold the repo.**
  - `settings.gradle.kts` includes `:core`, `:sharepoint`, `:importer`, `:server`, `:app`.
  - Version catalog with the Tech Stack versions; the Kotlin JVM toolchain is 21.
  - Generate the wrapper with `/opt/gradle-8.14.3/bin/gradle wrapper --gradle-version 8.14.3`.
  - `.gitignore`: `build/`, `.gradle/`, `local.properties`, `*.keystore`, `keystore.properties`, `importer/src/test/resources/private/`, `data/`.
  - `tools/android-sdk.sh` downloads `https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip` into `$HOME/android-sdk/cmdline-tools/latest`, accepts licences, installs `platform-tools`, `platforms;android-35` and `build-tools;35.0.0`, and writes `sdk.dir` into `local.properties`. Run it now.

- [ ] **Step 2: Write the failing tests** in `GeoTest.kt` (kotlin.test, JUnit 5):
```kotlin
@Test fun decimalPairNoSpace() { val m = findCoordinatePairs("39.63609452036168,-119.69104360818118").single(); assertEquals(39.63609452036168, m.lat); assertEquals(-119.69104360818118, m.lon); assertEquals(CoordFormat.DECIMAL, m.format) }
@Test fun spaceAfterComma() = assertEquals(-119.675338878, findCoordinatePairs("39.6361531972, -119.675338878").single().lon)
@Test fun splitAfterMinusAcrossLineBreak() = assertEquals(-119.68173217216633, findCoordinatePairs(normalizeText("39.63612682461489,-\r119.68173217216633")).single().lon)
@Test fun labelTabCr() = assertEquals("POLE 1 =\n39.6,-119.6", normalizeText("POLE 1 = \t\r39.6,-119.6"))
@Test fun dms() { val m = findCoordinatePairs("39°38'09.9\"N 119°41'27.8\"W").single(); assertEquals(39.636083, m.lat, 1e-5); assertEquals(-119.691056, m.lon, 1e-5); assertEquals(CoordFormat.DMS, m.format) }
@Test fun swapped() { val m = findCoordinatePairs("-119.69104,39.63609").single(); assertEquals(39.63609, m.lat); assertEquals(-119.69104, m.lon); assertTrue(m.swapped) }
@Test fun rejects() { assertTrue(findCoordinatePairs("1.5, 2.5").isEmpty()); assertTrue(findCoordinatePairs("123.4567, 200.1234").isEmpty()) }
@Test fun enDashAndOrder() { val ms = findCoordinatePairs("VERIFY HH – 39.35535679, -119.81288376 and 39.1234,-119.5678"); assertEquals(listOf(39.35535679, 39.1234), ms.map { it.lat }); assertEquals("VERIFY HH – ".length, ms[0].start) }
@Test fun distanceAndFeet() { assertEquals(85.7, distanceMeters(LatLon(39.636, -119.691), LatLon(39.636, -119.690)), 0.5); assertEquals(16.0, metersToFeet(4.877), 0.5) }
```
`JsonTest.kt`: `Op.PointMove` round-trips through `FmxJson` with `"kind":"point.move"`; `ServerMessage.Hello(5)` encodes to `{"kind":"hello","seq":5}`.

- [ ] **Step 3: Run to verify failure.** Run: `./gradlew :core:test`. Expected: FAIL, compilation errors for the missing symbols.

- [ ] **Step 4: Implement.**
  - `normalizeText`: `\r\n`/`\r` → `\n`; `\t` → space; collapse spaces; trim lines; join a line ending in `-` or `,` with a following line that starts with a digit or `-`.
  - Decimal pairs: two signed numbers with 1–3 integer digits and ≥ 3 decimals, separated by a comma (optional spaces) or by whitespace.
  - Range check: both values in range; or swapped (`|first| > 90`, `|second| ≤ 90`); otherwise rejected.
  - DMS: `D°M'S"[NS]` then `D°M'S"[EW]`, also accepting `′ ″`.
  - Haversine with R = 6371008.8.

- [ ] **Step 5: Run to verify pass.** Run: `./gradlew :core:test`. Expected: PASS.

- [ ] **Step 6: Commit.** `git add -A && git commit -m "build: gradle repo, android sdk script, core models and coordinate parsing"`

### Task 2: Naming rules

**Files:** Create `core/src/main/kotlin/com/fieldmaxx/core/naming/Naming.kt`; Test `core/src/test/kotlin/com/fieldmaxx/core/naming/NamingTest.kt`

**Interfaces:**
- Produces:
```kotlin
val KNOWN_TYPES = listOf("POLE", "PED", "HH", "MH", "BU", "SAI", "PFP", "MDU")
val ADD_CATEGORIES = listOf("POLE", "PED", "HH", "MH", "BU", "SAI", "PFP", "MDU", "CULVERT", "Drainage", "CREEK IMAGE", "MAIL BOX IMAGES", "Street images", "Rear images", "VACANT LOT", "UTILITIES", "Aerial Fielding", "F3 BUILDING DATA")
data class PointKey(val layer: String, val type: String, val num: String, val label: String)
enum class Where { CATEGORY, TOP }
fun isQueryLayer(layer: String): Boolean                 // ^query\s*\d+$ (ignore case)
fun pointLabel(layer: String, type: String, num: String): String
fun defaultFolderName(label: String): String              // whitespace runs → "_"
fun normalizeFolderName(name: String): String             // lowercase, drop whitespace and "_" (keep "-")
fun categoryMatches(folderName: String, layer: String): Boolean
fun pointFolderMatches(folderName: String, p: PointKey, where: Where): Boolean
fun photoFileName(folderName: String, lat: Double, lon: Double, counter: Long): String
fun markerCode(type: String): String
fun suggestAddedPoleNum(existingNums: List<String>, nearestNum: String): String
fun nextNum(existingNums: List<String>): String
```

- [ ] **Step 1: Write the failing tests** (`pole1 = PointKey("POLE","POLE","1","POLE 1")`, `pole14b = PointKey("POLE","POLE","14B","POLE 14B")`, `q43 = PointKey("QUERY 1","QUERY 1","43","Query 1-43")`):
```kotlin
@Test fun labels() { assertEquals("POLE 14B", pointLabel("POLE","POLE","14B")); assertEquals("Query 1-25A", pointLabel("QUERY 1","QUERY 1","25A")) }
@Test fun folderNames() { assertEquals("POLE_14B", defaultFolderName("POLE 14B")); assertEquals("Query_1-43", defaultFolderName("Query 1-43")) }
@Test fun categories() { assertTrue(categoryMatches("QUERY 1","Query 1")); assertTrue(categoryMatches("Query_1","QUERY 1")); assertTrue(categoryMatches("POLES","POLE")); assertFalse(categoryMatches("Query 3","QUERY 1")) }
@Test fun inCategory() { listOf("1","POLE_1","Pole 1","pole1","P1").forEach { assertTrue(pointFolderMatches(it, pole1, Where.CATEGORY), it) }; assertTrue(pointFolderMatches("POLE_14b", pole14b, Where.CATEGORY)); assertFalse(pointFolderMatches("10", pole1, Where.CATEGORY)) }
@Test fun topLevel() { assertTrue(pointFolderMatches("1", pole1, Where.TOP)); assertFalse(pointFolderMatches("43", q43, Where.TOP)); assertTrue(pointFolderMatches("Query 1-43", q43, Where.TOP)); assertTrue(pointFolderMatches("QUERY_1-43", q43, Where.CATEGORY)); assertTrue(pointFolderMatches("43", q43, Where.CATEGORY)) }
@Test fun fileName() = assertEquals("POLE_1_39.636130_-119.691104_6018.jpg", photoFileName("POLE_1", 39.63613, -119.691104, 6018))
@Test fun codes() { assertEquals("P", markerCode("POLE")); assertEquals("MH", markerCode("MH")); assertEquals("Q", markerCode("QUERY 1")); assertEquals("ST", markerCode("Street images")) }
@Test fun addedPoles() { assertEquals("14B", suggestAddedPoleNum(listOf("14"), "14")); assertEquals("14C", suggestAddedPoleNum(listOf("14","14B"), "14")); assertEquals("14C", suggestAddedPoleNum(listOf("14","14b"), "14")) }
@Test fun nextNumber() { assertEquals("11", nextNum(listOf("1","2","10"))); assertEquals("1", nextNum(emptyList())) }
```

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :core:test --tests '*NamingTest'`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - **Candidate names:** non-query points `{num, type+num, firstLetter(type)+num}`; query points `{num, "query"+n+"-"+num, "q"+n+"-"+num}`; all compared after `normalizeFolderName`.
  - **Bare number:** at `Where.TOP` the bare `num` matches only when `type == "POLE"` (§6.6.1).
  - **Coordinates in the file name:** formatted with `Locale.US`, 6 decimals.
  - **Marker codes:** POLE → `P`, other known types → themselves, query layers → `Q`, anything else → first two letters uppercased.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git commit -am "feat(core): naming rules"`

### Task 3: Stamp text, layout, compass

**Files:** Create `core/src/main/kotlin/com/fieldmaxx/core/stamp/Stamp.kt`; Test `core/src/test/kotlin/com/fieldmaxx/core/stamp/StampTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class StampMeta(val jobName: String, val pointLabel: String, val lat: Double?, val lon: Double?, val accuracyM: Double?, val altitudeM: Double?, val headingDeg: Double?, val takenAtMs: Long, val zone: java.time.ZoneId, val gpsStale: Boolean, val fromGallery: Boolean)
fun compass8(deg: Double): String            // N NE E SE S SW W NW
fun formatStampTime(ms: Long, zone: java.time.ZoneId): String   // MM/dd/yy HH:mm:ss
fun stampLines(m: StampMeta): List<String>
data class StampLayout(val fontPx: Int, val x: Int, val firstBaselineY: Int, val lineHeightPx: Int, val shadowBlurPx: Int)
fun stampLayout(width: Int, height: Int, lineCount: Int): StampLayout
```

- [ ] **Step 1: Write the failing tests** (`base` = A05J9XE, POLE 1, 39.63613, -119.691104, accuracy 4.877 m, altitude 1350.26 m, heading 47.0, `2026-11-05T13:35:47` in `America/Los_Angeles`):
```kotlin
@Test fun full() = assertEquals(listOf("A05J9XE · POLE 1", "39.636130, -119.691104", "±16 ft · alt 4430 ft", "NE 47°", "11/05/26 13:35:47"), stampLines(base))
@Test fun noGps() = assertEquals(listOf("A05J9XE · POLE 1", "No GPS", "Compass n/a", "11/05/26 13:35:47"), stampLines(base.copy(lat = null, lon = null, accuracyM = null, altitudeM = null, headingDeg = null)))
@Test fun noAltitude() = assertEquals("±16 ft", stampLines(base.copy(altitudeM = null))[2])
@Test fun staleAndGallery() { val l = stampLines(base.copy(gpsStale = true, fromGallery = true)); assertEquals("39.636130, -119.691104 (GPS stale)", l[1]); assertEquals("11/05/26 13:35:47 (from gallery)", l[4]) }
@Test fun compass() { assertEquals("N", compass8(0.0)); assertEquals("N", compass8(22.4)); assertEquals("NE", compass8(22.5)); assertEquals("N", compass8(359.0)); assertEquals("S", compass8(200.0)) }
@Test fun layout() = assertEquals(StampLayout(84, 84, 3496, 105, 21), stampLayout(3000, 4000, 5))
```

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :core:test --tests '*StampTest'`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - **Layout:** `fontPx = round(min(w,h) * 0.028)`, `x = fontPx`, `lineHeightPx = round(fontPx * 1.25)`, `firstBaselineY = h - fontPx - lineHeightPx * (lineCount - 1)`, `shadowBlurPx = round(fontPx * 0.25)`.
  - **Feet:** whole numbers.
  - **No GPS:** the accuracy line is omitted.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git commit -am "feat(core): stamp lines, layout and compass"`

---

## Milestone B: `importer` (server-side job file reader)

Test helpers live in `importer/src/testFixtures/kotlin/com/fieldmaxx/importer/` (Gradle `java-test-fixtures` plugin) so `server` tests can reuse them.

### Task 4: Labelled-point parser and sanity flags

**Files:** Create `importer/build.gradle.kts` (with `java-test-fixtures`), `importer/src/main/kotlin/com/fieldmaxx/importer/Labelled.kt`, `importer/src/main/kotlin/com/fieldmaxx/importer/Sanity.kt`, `importer/src/testFixtures/kotlin/com/fieldmaxx/importer/Replicas.kt`; Test `importer/src/test/kotlin/com/fieldmaxx/importer/LabelledTest.kt`

**Interfaces:**
- Consumes: `normalizeText`, `findCoordinatePairs`, `CoordMatch` (Task 1); `pointLabel`, `KNOWN_TYPES` (Task 2); `DraftPoint`, `ImportFlag`.
- Produces:
```kotlin
enum class GroupSource { ANNOTATION, FIELD, PAGE_TEXT, DOCX }
data class TextGroup(val source: GroupSource, val page: Int?, val text: String)
data class LabelledResult(val points: List<DraftPoint>, val unlabelled: List<CoordMatch>, val leftoverText: String)
fun extractLabelledPoints(group: TextGroup): LabelledResult
fun addSanityFlags(points: List<DraftPoint>): List<DraftPoint>
```

- [ ] **Step 1: Write `Replicas.kt`.** It holds `const val AERIAL`, the §5.1 text shape with synthetic coordinates: `"POLE 1 = \t\r39.10000000000001,-119.10000000000001\rPOLE 2 = \t\r39.20000000000002,-119.20000000000002\rPOLE 3 = 39.30000000000003,-119.30000000000003\rPOLE 4 = 39.40000000000004,-119.40000000000004\rPOLE 5 = \t\r39.5000000005, -119.500000005\rPOLE 6 = 39.6000000006, -119.600000006\rPOLE 7 = 39.70000000000007,-119.70000000000007"`.

- [ ] **Step 2: Write the failing tests:**
```kotlin
@Test fun aerialReplica() { val r = extractLabelledPoints(TextGroup(GroupSource.ANNOTATION, 1, AERIAL)); assertEquals((1..7).map { "POLE $it" }, r.points.map { it.label }); with(r.points[4]) { assertEquals("POLE", layer); assertEquals("5", num); assertEquals(39.5000000005, lat); assertEquals(-119.500000005, lon); assertEquals(1, sourcePage) }; assertTrue(r.unlabelled.isEmpty()); assertTrue(r.leftoverText.isBlank()) }
@Test fun suffixUppercased() = assertEquals("POLE 14B", extractLabelledPoints(TextGroup(GroupSource.DOCX, null, "POLE 14b = 39.1234,-119.1234")).points.single().label)
@Test fun mixedCaseColonDms() = assertEquals("POLE 3", extractLabelledPoints(TextGroup(GroupSource.PAGE_TEXT, 2, "Pole 3: 39°38'09.9\"N 119°41'27.8\"W")).points.single().label)
@Test fun trailingDescription() = assertEquals("existing 40ft", extractLabelledPoints(TextGroup(GroupSource.PAGE_TEXT, 1, "MH 2 = 39.1234,-119.1234 existing 40ft\nMH 3 = 39.2234,-119.2234")).points[0].description)
@Test fun unlabelledReturned() = assertEquals(1, extractLabelledPoints(TextGroup(GroupSource.PAGE_TEXT, 1, "see 39.1234,-119.1234")).unlabelled.size)
@Test fun sanity() { val out = addSanityFlags(listOf(p("POLE 1", 39.1, -119.1), p("POLE 2", 39.11, 119.11), p("POLE 3", 39.12, -119.12), p("POLE 4", 41.5, -119.1), p("POLE 1", 39.2, -119.2))); assertContains(out[1].flags, ImportFlag.sign_mismatch); assertContains(out[3].flags, ImportFlag.far_from_job); assertContains(out[0].flags, ImportFlag.duplicate_label); assertContains(out[4].flags, ImportFlag.duplicate_label); assertTrue(out[2].flags.isEmpty()) }
```

- [ ] **Step 3: Run to verify failure.** Run: `./gradlew :importer:test --tests '*LabelledTest'`. Expected: FAIL.

- [ ] **Step 4: Implement.**
  - Normalize, find coordinate pairs, then for each pair test the slice since the previous pair's end against the label pattern anchored at the slice end: `(?:^|[^A-Za-z])(TYPE)\s*#?\s*(\d{1,4}[A-Za-z]?)\s*[=:\-–]?\s*$`.
  - `TYPE` is a `KNOWN_TYPES` word (ignore case); otherwise any `[A-Z]{2,8}` (case-sensitive).
  - A point's description is the text after its coordinates, up to the next label.
  - Sanity flags:
    - `sign_mismatch`: longitude sign differs from the majority.
    - `far_from_job`: more than 50 km from the median.
    - `duplicate_label`: the same label more than 1 m apart.

- [ ] **Step 5: Run to verify pass.** Expected: PASS.

- [ ] **Step 6: Commit.** `git add -A && git commit -m "feat(importer): labelled point parser and sanity flags"`

### Task 5: DOCX to ordered blocks with Word list numbering

**Files:** Create `importer/src/main/kotlin/com/fieldmaxx/importer/Blocks.kt`, `importer/src/main/kotlin/com/fieldmaxx/importer/Docx.kt`, `importer/src/testFixtures/kotlin/com/fieldmaxx/importer/MakeDocx.kt`; Test `importer/src/test/kotlin/com/fieldmaxx/importer/DocxTest.kt`

**Interfaces:**
- Produces:
```kotlin
sealed interface DocBlock { val page: Int? }
data class ParaBlock(val text: String, val listLabel: String?, override val page: Int?) : DocBlock
data class ImageBlock(val imageId: String, override val page: Int?) : DocBlock
class ExtractedImage(val id: String, val mime: String /* image/png|image/jpeg */, val bytes: ByteArray)
class ImportException(val code: String /* unreadable_pdf|unreadable_docx|unsupported_type */, message: String) : Exception(message)
data class DocxExtract(val blocks: List<DocBlock>, val images: List<ExtractedImage>)
fun docxToBlocks(bytes: ByteArray): DocxExtract
fun renderListLabel(lvlText: String, levels: List<Pair<Int, String /* numFmt */>>): String
// test fixture
data class DocxList(val numId: Int, val start: Int, val fmt: String /* decimal|upperLetter */, val lvlText: String)
sealed interface DocxItem { data class Para(val text: String, val numId: Int? = null, val ilvl: Int = 0, val styleNumId: Int? = null) : DocxItem; class Image(val png: ByteArray) : DocxItem; data class Table(val cells: List<List<String>>) : DocxItem }
fun makeDocx(lists: List<DocxList>, body: List<DocxItem>): ByteArray
```

- [ ] **Step 1: Write `makeDocx`** with `java.util.zip.ZipOutputStream`. It produces `[Content_Types].xml`, `_rels/.rels`, `word/document.xml`, `word/numbering.xml` (one `w:abstractNum` per list carrying `w:start`, `w:numFmt` and `w:lvlText`, plus a `w:num` pointing at it), `word/styles.xml` (style `NumberedStyle<N>` with a `w:numPr`), `word/_rels/document.xml.rels` and `word/media/imageN.png`.

- [ ] **Step 2: Write the failing tests:**
  - `decimalList`: three paras in `numId 1, start 1, "%1."` → labels `1.`, `2.`, `3.`.
  - `startsAt14` → `14.`, `15.`.
  - `upperLetter` with `"%1-"` → `A-`, `B-`.
  - `typedNumberStaysText`: `"12. NO STREET VIEW"` with no list → `listLabel == null` and the text unchanged.
  - `imagesInOrderWithBytes` → block kinds `[Para, Image, Para]`, the image bytes equal the input, mime `image/png`.
  - `numberingFromStyle` → `1.`.
  - `tableCellsInOrder` → `before, c1, c2, after`.
  - `garbage`: `ImportException` with code `unreadable_docx`.

- [ ] **Step 3: Run to verify failure.** Run: `./gradlew :importer:test --tests '*DocxTest'`. Expected: FAIL.

- [ ] **Step 4: Implement** with `ZipInputStream` + a namespace-aware DOM parser. Walk `w:body` in order (including paragraphs inside `w:tbl` cells). Numbering, which the tests don't fully determine:
```
numPr = paragraph pPr numPr ?: numPr of the paragraph's style
numPr missing or numId == 0 → listLabel = null
lvl = abstractNum(num(numId)).levels[ilvl]; start = num.lvlOverride[ilvl].startOverride ?: lvl.start ?: 1
counters[numId][ilvl] = if unset then start else +1; clear deeper levels
listLabel = renderListLabel(lvl.lvlText, levels 0..ilvl as (value, numFmt))
```
`renderListLabel` supports `decimal`, `upperLetter` (A…Z, AA…), `lowerLetter`, `upperRoman`, `lowerRoman`. Image ids are `img-<n>` in order.

- [ ] **Step 5: Run to verify pass.** Expected: PASS.

- [ ] **Step 6: Commit.** `git add -A && git commit -m "feat(importer): docx blocks with Word list numbering"`

### Task 6: Query-item parser

**Files:** Create `importer/src/main/kotlin/com/fieldmaxx/importer/Query.kt`; Modify `Replicas.kt` (add `QUERY_LISTS`, `QUERY_BODY`, `QUERY_BLOCKS`); Test `importer/src/test/kotlin/com/fieldmaxx/importer/QueryTest.kt`

**Interfaces:**
- Produces:
```kotlin
fun layerFromFileName(fileName: String): String   // "QUERY_1_-_A05K51V.docx" → "QUERY 1"; "A05J9XE.pdf" → "A05J9XE"
data class QueryResult(val points: List<DraftPoint>, val notes: List<String>, val unattachedImageIds: List<String>)
fun extractQueryItems(blocks: List<DocBlock>, layer: String): QueryResult
```

- [ ] **Step 1: Add the query replica** to `Replicas.kt`. It mirrors §5.2's structure with synthetic coordinates (≥ 7 decimals) and the same instruction text:
  - **List A** (`numId 1`, start 1, `"%1."`): 10 `VERIFY …` items and `NO STREET VIEW PROVIDE ALL UTILITY DETAILS – <coords>`, one image each.
  - **Typed items:** `12. NO STREET VIEW PROVIDE ALL UTILITY DETAILS  - <coords>` and `13.. NO STREET VIEW … – <coords>`, each with an image.
  - **List B** (`numId 4`, start 14): the 12 instruction items from §5.2, each with coordinates in the next unnumbered paragraph. Images: one each for items 14, 18, 19, 20, 21 and 24; two for item 23; none for items 15, 16, 17 and 22.
  - **Item 25:** `POLE DATA`, followed by two coordinate-only paragraphs in list C (`numId 3`, `"%1-"` upperLetter) and one image.
  - Export `QUERY_LISTS`/`QUERY_BODY` for `makeDocx`, and `QUERY_BLOCKS`, the blocks `docxToBlocks` produces from them.

- [ ] **Step 2: Write the failing tests:**
```kotlin
@Test fun layers() { assertEquals("QUERY 1", layerFromFileName("QUERY_1_-_A05K51V.docx")); assertEquals("QUERY 2", layerFromFileName("query 2.pdf")); assertEquals("A05J9XE", layerFromFileName("A05J9XE.pdf")) }
@Test fun replica26() { val r = extractQueryItems(QUERY_BLOCKS, "QUERY 1"); assertEquals((1..24).map { "$it" } + listOf("25A", "25B"), r.points.map { it.num }); assertEquals("Query 1-1", r.points[0].label); assertEquals("Query 1-25B", r.points[25].label) }
@Test fun descriptions() { val p = extractQueryItems(QUERY_BLOCKS, "QUERY 1").points; assertEquals("VERIFY HH", p[0].description); assertEquals("NO STREET VIEW PROVIDE ALL UTILITY DETAILS", p[11].description); assertEquals("PROVIDE WIDE ANGLE IMAGES FROM POLE 5 TO POLE 4", p[13].description) }
@Test fun coordsOnNextParagraph() = assertNotNull(extractQueryItems(QUERY_BLOCKS, "QUERY 1").points[13].lat)
@Test fun imagesAttach() { val p = extractQueryItems(QUERY_BLOCKS, "QUERY 1").points; assertEquals(1, p[0].refImageIds.size); assertEquals(2, p[22].refImageIds.size); assertEquals(0, p[15].refImageIds.size); assertEquals(p[24].refImageIds, p[25].refImageIds) }
@Test fun noCoordinatesFlagged() { val p = extractQueryItems(listOf(ParaBlock("CHECK SOMETHING", "1.", null)), "QUERY 1").points.single(); assertNull(p.lat); assertEquals(listOf(ImportFlag.no_coordinates), p.flags) }
@Test fun unnumberedContinues() { val p = extractQueryItems(listOf(ParaBlock("A – 39.1234,-119.1234", "1.", null), ParaBlock("B – 39.2234,-119.2234", null, null)), "QUERY 1").points; assertEquals("2", p[1].num); assertEquals(listOf(ImportFlag.unlabelled), p[1].flags) }
@Test fun textExtendsDescription() { val p = extractQueryItems(listOf(ParaBlock("POLE DATA", "1.", null), ParaBlock("near the creek", null, null), ParaBlock("39.1234,-119.1234", null, null)), "QUERY 1").points.single(); assertEquals("POLE DATA near the creek", p.description); assertEquals(39.1234, p.lat) }
```

- [ ] **Step 3: Run to verify failure.** Run: `./gradlew :importer:test --tests '*QueryTest'`. Expected: FAIL.

- [ ] **Step 4: Implement** (the tests don't fully pin this algorithm):
```
typed = ^\s*(\d{1,4})\s*\.+\s*(.*)$
image → current item?.images += id ?: unattached += id
para  → number = numeric listLabel digits ?: typed number ?: null; letter = alphabetic listLabel letters ?: null
        body = text minus typed number; coords = findCoordinatePairs(body); words = body minus coords minus [–-:,] has letters
        words && (number != null || coords.any()) → new item(num = number ?: prev+1 flagged unlabelled, text = words, coords)
        !words && coords.any() && current != null → current.coords += coords (with letter)
        words && current != null && current.coords.isEmpty() → current.text += " " + words
        words → notes += text
points: 0 coords → one point (lat/lon null, no_coordinates); 1 → num; n > 1 → num + (letter ?: A, B, …); all share the item's images
```

- [ ] **Step 5: Run to verify pass.** Expected: PASS.

- [ ] **Step 6: Commit.** `git add -A && git commit -m "feat(importer): numbered query items"`

### Task 7: PDF groups via PDFBox

**Files:** Create `importer/src/main/kotlin/com/fieldmaxx/importer/Pdf.kt`, `importer/src/testFixtures/kotlin/com/fieldmaxx/importer/MakePdf.kt`; Test `importer/src/test/kotlin/com/fieldmaxx/importer/PdfTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class PdfGroup(val source: GroupSource, val page: Int, val blocks: List<DocBlock>)
class PdfExtract(val groups: List<PdfGroup>, val images: List<ExtractedImage>, val pageRenders: List<ExtractedImage> /* ids "page-<n>", PNG */)
fun pdfToGroups(bytes: ByteArray, renderDpi: Float = 144f): PdfExtract
// test fixture (built with PDFBox)
data class PdfPageSpec(val lines: List<Triple<String, Float, Float>> = emptyList(), val images: List<PdfImageSpec> = emptyList(), val freeText: List<String> = emptyList())
class PdfImageSpec(val png: ByteArray, val x: Float, val y: Float, val w: Float, val h: Float)
fun makePdf(pages: List<PdfPageSpec>): ByteArray
```

- [ ] **Step 1: Write the failing tests:**
  - `freeTextIsItsOwnGroup` (AERIAL annotation → `ANNOTATION` group on page 1 with ≥ 7 para blocks);
  - `pageTextTopToBottom` (lines at y 600 "second" and y 700 "first" → `first, second`);
  - `imageBetweenLines` (lines at y 700 and 400, image at y 500 → kinds `Para, Image, Para`; one extracted image);
  - `pngRenderPerPage` (2 pages → ids `page-1`, `page-2`, PNG signature);
  - `garbage` (code `unreadable_pdf`).

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :importer:test --tests '*PdfTest'`. Expected: FAIL.

- [ ] **Step 3: Implement** with PDFBox 3 (`Loader.loadPDF`):
  - **Annotations:** `PDAnnotation.contents` split on `\r|\n` into para blocks.
  - **Form fields:** `PDAcroForm` field values (`valueAsString`) → `FIELD` groups.
  - **Page text:** a `PDFTextStripper` subclass collects `TextPosition`s into lines (baseline within 2 pt), sorted top-down then by x.
  - **Images:** a `PDFStreamEngine` subclass handles `Do` for `PDImageXObject`, taking the y position from the current transformation matrix and the image via `image.image` → PNG.
  - **Order:** interleave images into the lines by y.
  - **Renders:** `PDFRenderer.renderImageWithDPI(i, renderDpi)` → PNG.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(importer): pdf annotations, fields, text, images, renders"`

### Task 8: `importFile` orchestration and real-file checks

**Files:** Create `importer/src/main/kotlin/com/fieldmaxx/importer/Importer.kt`; Test `importer/src/test/kotlin/com/fieldmaxx/importer/ImporterTest.kt`, `importer/src/test/kotlin/com/fieldmaxx/importer/RealFilesTest.kt`

**Interfaces:**
- Consumes: Tasks 4–7.
- Produces:
```kotlin
class ImportResult(val fileName: String, val kind: String, val layerSuggestion: String, val notes: String, val points: List<DraftPoint>, val unread: List<UnreadLine>, val images: List<ExtractedImage>, val refImages: List<RefImageInfo>)
fun importFile(fileName: String, bytes: ByteArray): ImportResult
```

- [ ] **Step 1: Copy the owner's samples** (git-ignored): `mkdir -p importer/src/test/resources/private && cp /root/.claude/uploads/ba4638bf-5ef8-5958-b902-cca747642d63/958958f7-A05J9XE.pdf importer/src/test/resources/private/A05J9XE.pdf && cp "/root/.claude/uploads/ba4638bf-5ef8-5958-b902-cca747642d63/a4755df1-QUERY_1_-_A05K51V.docx" importer/src/test/resources/private/QUERY_1_-_A05K51V.docx`. Confirm `git status` doesn't list them.

- [ ] **Step 2: Write the failing tests.** `ImporterTest`:
  - `aerialPdf`: notes annotation `"     NOTE FOR AERIAL PATH\r1. IDENTIFY THE FEASIBILITY OF ATTACHING FIBER ON POLES THROUGHOUT THE PROPOSED CABLE ROUTE ON POLE.\r2. PLEASE MARK THE POLES IN BETWEEN THE CABLE PATH IN CASE IF WE HAVE MISSED ANY OF THEM."`, plus AERIAL, plus an image. Expect labels `POLE 1`…`POLE 7`, notes contain `NOTE FOR AERIAL PATH`, `unread` empty, every point's `refImageIds` contains `page-1`, `layerSuggestion == "POLE"`.
  - `queryDocx` → 26 points, `layerSuggestion == "QUERY 1"`, `points[22].refImageIds.size == 2`.
  - `queryDocxAsPdfSameLabels` (skipped unless `soffice` is on PATH; convert with `soffice --headless --convert-to pdf --outdir <tmp>`).
  - `unparseableLineReported`: `"POLE 8 = 39.6361,-119.69\rPOLE 9 = 39.63,abc"` → 1 point, and `unread` holds `POLE 9 = 39.63,abc` on page 1.
  - `strayCoordinatesFlaggedUnlabelled`.
  - `annotationAndPageTextNotDoubled`.
  - `unsupportedType`: `job.xlsx` → `ImportException("unsupported_type")`.

  `RealFilesTest` (`@EnabledIf` the private files exist):
  - `A05J9XE.pdf` gives the 7 labels with the exact §5.1 coordinates (POLE 1 = 39.63609452036168, -119.69104360818118 … POLE 7 = 39.63616773905345, -119.67284033048661);
  - `QUERY_1_-_A05K51V.docx` gives 26 points: `Query 1-1` at 39.35535679, -119.81288376; `Query 1-25A` at 39.3548480, -119.8126657; `Query 1-25B` at 39.3540205, -119.8126599;
  - if `soffice` exists, the DOCX converted to PDF gives the same 26 labels.

- [ ] **Step 3: Run to verify failure.** Run: `./gradlew :importer:test`. Expected: the new tests FAIL.

- [ ] **Step 4: Implement `importFile`:**
  - **Kind:** from the extension; otherwise `ImportException("unsupported_type")`.
  - **Per group:**
    - a group with no coordinate pairs → notes;
    - otherwise run `extractLabelledPoints`; if it finds no labelled points, run `extractQueryItems` with `layerFromFileName(fileName)`.
  - **Leftover coordinates** → flagged `unlabelled` points.
  - **Leftover lines** matching `\b(POLE|PED|HH|MH|BU|SAI|PFP|MDU)\b\s*\d` (ignore case) or `\d{1,3}\.\d{3,}` → `unread`, with reason `type and number without readable coordinates` or `number that looks like a coordinate`.
  - **Cleanup:** de-duplicate (same label, coordinates within 1e-7); add `page-<p>` to PDF points; `addSanityFlags`.
  - **Layer suggestion:** the most common layer.

- [ ] **Step 5: Run to verify pass.** Run: `./gradlew :importer:test`. Expected: PASS, including the real-file tests on this machine.

- [ ] **Step 6: Commit.** `git add -A && git commit -m "feat(importer): importFile with real-file checks"`

---

## Milestone C: `sharepoint` (used by app and server)

### Task 9: Destination interface, fake destination, folder resolver

**Files:** Create `sharepoint/build.gradle.kts`, `sharepoint/src/main/kotlin/com/fieldmaxx/sharepoint/Destination.kt`, `sharepoint/src/main/kotlin/com/fieldmaxx/sharepoint/FakeDestination.kt` (in `main`, used by server and app tests), `sharepoint/src/main/kotlin/com/fieldmaxx/sharepoint/FolderResolver.kt`; Test `sharepoint/src/test/kotlin/com/fieldmaxx/sharepoint/FolderResolverTest.kt`

**Interfaces:**
- Consumes: `PointKey`, `Where`, `categoryMatches`, `pointFolderMatches`, `defaultFolderName` (Task 2).
- Produces (paths are relative to Field Notes, `/`-separated, `""` = root):
```kotlin
data class FileInfo(val size: Long)
data class ChunkSession(val uploadId: String, val offset: Long)
enum class DestinationErrorCode { REQUIRES_SIGN_IN, FORBIDDEN, NOT_FOUND, EXISTS, NETWORK, UPLOAD_SESSION_LOST, UNEXPECTED }
class DestinationException(val code: DestinationErrorCode, message: String) : Exception(message)
interface PhotoDestination {
  fun rootName(): String
  fun listFolders(relPath: String): List<String>
  fun ensureFolder(relPath: String)
  fun fileInfo(relPath: String): FileInfo?
  fun upload(relPath: String, bytes: ByteArray)                                   // small files (exports)
  fun uploadChunked(relPath: String, file: java.io.File, chunkBytes: Int, resume: ChunkSession?, onProgress: (ChunkSession) -> Unit)
  fun download(relPath: String, out: java.io.OutputStream)
}
typealias DestinationFactory = (fieldNotesUrl: String) -> PhotoDestination
class FakeDestination(private val root: String = "Field Notes") : PhotoDestination {
  val folders: MutableSet<String>; val files: MutableMap<String, ByteArray>; val calls: MutableList<String>
  fun failNext(n: Int, code: DestinationErrorCode); var uploadDelayMs: Long; var dropChunkAfter: Int?   // simulate a dropped connection
}
sealed interface FolderResolution { data class Found(val path: String) : FolderResolution; data class Created(val path: String) : FolderResolution; data class Ambiguous(val candidates: List<String>) : FolderResolution }
class FolderResolver(private val dest: PhotoDestination) { fun resolve(p: PointKey): FolderResolution }
```

- [ ] **Step 1: Write the failing tests:**
  - `a05j9xeTopLevel`: folders `1`, `2`, `3` + POLE 1 → `Found("1")`.
  - `categoryMixedCase`: `POLE`, `POLE/POLE_14b` → `Found("POLE/POLE_14b")`.
  - `nothingCreatesPole33`: → `Created("POLE/POLE_33")`, and the folder exists in the fake.
  - `queryCategoryExists`: → `Created("QUERY 1/Query_1-43")`.
  - `queryNeverMatchesTopLevelPoleFolder`.
  - `categoryPreferredOverTop`.
  - `twoCandidatesAmbiguous`.
  - `existingCategorySpellingReused` (`Query 3` stays `Query 3`).
  - `listingsCachedPerResolver` (`listFolders("")` called once for two points).

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :sharepoint:test --tests '*FolderResolverTest'`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - **Resolver order (§6.6.1):** the category folder's subfolders, then the top level, otherwise create `<existing-or-new category>/<defaultFolderName(label)>`. Invalidate the cached listing for a folder after creating inside it.
  - **Fake rules:** `upload` throws `EXISTS` if the path exists and `NOT_FOUND` if the parent is missing. `uploadChunked` honours `resume` and `dropChunkAfter` (throws `NETWORK` after N chunks, having reported progress).

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(sharepoint): destination interface, fake, folder resolver"`

### Task 10: SharePoint share-link client

**Files:** Create `sharepoint/src/main/kotlin/com/fieldmaxx/sharepoint/SharePointLinkDestination.kt`, `sharepoint/src/test/kotlin/com/fieldmaxx/sharepoint/MockSharePoint.kt`; Test `sharepoint/src/test/kotlin/com/fieldmaxx/sharepoint/SharePointLinkTest.kt`, `sharepoint/src/test/kotlin/com/fieldmaxx/sharepoint/SharePointLiveTest.kt`

**Interfaces:**
- Produces:
```kotlin
class SharePointLinkDestination(shareUrl: String, client: okhttp3.OkHttpClient, clock: java.time.Clock = java.time.Clock.systemUTC()) : PhotoDestination
fun spPathLiteral(serverRelativePath: String): String   // doubles ', percent-encodes each segment, keeps "/"
fun sharePointDestinations(client: okhttp3.OkHttpClient): DestinationFactory   // one cached instance per URL for 30 min
```

- [ ] **Step 1: Write `MockSharePoint`** (OkHttp `MockWebServer` + `Dispatcher`) emulating the calls proven in §5.5 and §5.6, recording requests. It supports both link shapes:
  - **Business:** `/:f:/g/personal/u/TOKEN?e=x` → 302 to `/personal/u/_layouts/15/onedrive.aspx?id=%2Fpersonal%2Fu%2FDocuments%2FOSP%20FIELDING%2FField%20Notes&ga=1`, with `Set-Cookie: FedAuth=abc`.
  - **Personal:** `/f/c/cid/TOKEN?e=x` → 301 to `/:f:/g/personal/CID/TOKEN?...` → 302 to `/?id=…&spopath=%2Fpersonal%2Fcid%2FDocuments%2FFieldmaxx&listurl=…`, with the cookie.
  - **API calls:**
    - `POST <site>/_api/contextinfo` → `{FormDigestValue, FormDigestTimeoutSeconds: 1800}`;
    - `GET …/GetFolderByServerRelativePath(decodedurl='…')/Folders?$select=Name`;
    - `POST …/folders/AddUsingPath(decodedurl='…',overwrite=false)`;
    - `GET …/GetFileByServerRelativePath(decodedurl='…')?$select=Length` → 200 or 404;
    - `POST …/Files/AddUsingPath(decodedurl='name',overwrite=false)` → 200, or 400 `already exists`;
    - `StartUpload(uploadId=guid'…')`, `ContinueUpload(uploadId=guid'…',fileOffset=N)`, `FinishUpload(uploadId=guid'…',fileOffset=N)`; an unknown or expired uploadId → 400;
    - `GET …/GetFileByServerRelativePath(decodedurl='…')/$value`.
  - **Switches:** cookie expiry (403 `Access denied` until the share URL is fetched again); a sign-in-only link (302 to `https://login.microsoftonline.com/...`); drop the connection on chunk N; expire upload sessions.

- [ ] **Step 2: Write the failing tests:**
  - `businessLinkRootName` → `Field Notes`.
  - `personalLinkUsesSpopath` → root path `/personal/cid/Documents/Fieldmaxx` and site `<host>/personal/cid`.
  - `listFoldersEncodesPath`.
  - `pathLiteral` (`spPathLiteral("/a/O'Brien St/x") == "/a/O''Brien%20St/x"`).
  - `ensureFolderCreatesEachSegment`.
  - `uploadSendsDigestAndNoOverwrite`.
  - `fileInfoNullOn404`.
  - `existingNameThrowsExists`.
  - `chunkedUploadCompletes` (3 chunks; final size equals the file length; `onProgress` offsets 3200000, 6400000, …).
  - `resumesAtConfirmedOffset` (drop on chunk 2, then `uploadChunked(resume = lastProgress)` sends only the remaining chunks).
  - `lostSessionRestartsOnOwnPartialFile` (expired uploadId with `resume` given → a new `StartUpload` on the same path, no `EXISTS`).
  - `signInRedirect` → `REQUIRES_SIGN_IN` `"This link needs a Microsoft sign-in"`.
  - `digestRefreshedAfterTimeout`.
  - `cookieExpiryReResolvesOnce`.
  - `persistent403` → `FORBIDDEN` `"The link no longer allows uploads"`.
  - `networkFailure` → `NETWORK`.
  - `downloadStreamsBytes`.

  `SharePointLiveTest` (`@EnabledIfEnvironmentVariable(named = "FMX_LIVE_TEST_LINK", matches = ".+")`) runs against the owner's test folder under `_fieldmaxx_it/<timestamp>/`: ensure folder, chunked upload of 9 MB, `fileInfo` size matches, duplicate refused, download equals upload.

- [ ] **Step 3: Run to verify failure.** Run: `./gradlew :sharepoint:test --tests '*SharePointLinkTest'`. Expected: FAIL.

- [ ] **Step 4: Implement.**
  - **Resolve:** follow redirects manually with a browser User-Agent (`Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36`), keeping cookies (OkHttp `CookieJar`). Root path = the `spopath` query parameter if present, else the `id` parameter of the `onedrive.aspx` URL. Site = Location origin + `/personal/<segment>`.
  - **Every request:** send `Accept: application/json;odata=nometadata`.
  - **Digest:** cached until 60 s before its timeout.
  - **Auth errors:** on 401/403 re-resolve once, then retry.
  - **Chunked upload:**
    - if `resume == null`: create an empty file (`EXISTS` propagates), then `StartUpload` with the first chunk;
    - then `ContinueUpload` for each middle chunk, reporting `ChunkSession` after each;
    - `FinishUpload` for the last chunk;
    - on an expired-session error while resuming: restart with a new uploadId on the same path.

- [ ] **Step 5: Run to verify pass.** Expected: PASS. Then run the live test once: `FMX_LIVE_TEST_LINK='<owner test link>' ./gradlew :sharepoint:test --tests '*SharePointLiveTest'` (the link is in the session conversation, never committed). Expected: PASS.

- [ ] **Step 6: Commit.** `git add -A && git commit -m "feat(sharepoint): share-link client with chunked resumable upload"`

---

## Milestone D: `server` (Ktor)

### Task 11: Server scaffold, database, config, test harness

**Files:** Create `server/build.gradle.kts`, `server/src/main/kotlin/com/fieldmaxx/server/{Main.kt, Config.kt, Db.kt, AppModule.kt, Ctx.kt}`, `server/src/main/resources/migrations/001_init.sql`; Test `server/src/test/kotlin/com/fieldmaxx/server/{TestApp.kt, ScaffoldTest.kt}`

**Interfaces:**
- Produces:
```kotlin
data class Config(val dataDir: java.nio.file.Path, val port: Int, val publicOrigin: String, val sessionDays: Int, val destination: String /* sharepoint|fake */, val buildId: String)
fun loadConfig(env: Map<String, String> = System.getenv()): Config   // FMX_DATA_DIR(./data) PORT(8080) FMX_PUBLIC_ORIGIN FMX_DESTINATION(sharepoint) FMX_BUILD_ID(dev); sessionDays 90
class Db(url: String) { fun <T> tx(block: java.sql.Connection.() -> T): T; fun <T> read(block: java.sql.Connection.() -> T): T }   // WAL, foreign_keys, runs migrations
interface ChangeRecorder { fun record(jobId: String?, entity: String, entityId: String, job: Job? = null, point: Point? = null, photo: PhotoInfo? = null): Change }
interface DeliveryKick { fun kick() }
class Ctx(val config: Config, val db: Db, val clock: java.time.Clock, val destinations: DestinationFactory, var changes: ChangeRecorder, var delivery: DeliveryKick)
fun io.ktor.server.application.Application.fieldmaxx(ctx: Ctx)
// test harness
class MutableClock(var nowMs: Long = 1_762_000_000_000) : java.time.Clock()
class TestApp(val clock: MutableClock = MutableClock(), val destination: FakeDestination = FakeDestination()) { val ctx: Ctx; fun run(block: suspend ApplicationTestBuilder.(TestApp) -> Unit); suspend fun ApplicationTestBuilder.tokenFor(username: String, role: Role = Role.tech): String }
```

- [ ] **Step 1: Write `001_init.sql`.** Times are INTEGER ms since epoch; JSON is TEXT. Tables:
  - `users(id, username UNIQUE COLLATE NOCASE, display_name, password_hash, role, disabled, created_at)`
  - `sessions(token_hash PK, user_id, created_at, expires_at)`
  - `login_failures(username, at)`
  - `jobs(id, name, notes, field_notes_url, status, created_by, created_at, finished_at, deleted_at, deleted_by, delivery_problem)`
  - `job_files(id, job_id, kind, original_name, stored_path, layer, uploaded_by, created_at)`
  - `imports(id, user_id, file_name, kind, stored_path, result_json, created_at)`
  - `ref_images(id, job_id, import_id, kind, page, stored_path, mime)`
  - `points(id, job_id, layer, type, num, label, lat, lon, orig_lat, orig_lon, description, status, cant_reason, cant_text, pole_json, comment, folder_path, folder_candidates_json, ref_image_ids_json, flags_json, source_file_id, source_page, added_by, created_at, updated_at, updated_by, deleted_at, deleted_by)`
  - `photos(id, job_id, point_id, user_id, file_name, size, sha256, taken_at, lat, lon, accuracy_m, heading_deg, status, dest_path, last_error, attempts, next_attempt_at, backup_path, backup_offset, backup_received_at, backup_purged_at, thumb_path, confirmed_at, upload_ms, reused_from, source_kind, removed_at, removed_by, created_at)`
  - `counters(name PK, value)`, `settings(key PK, value)`
  - `ops_done(op_id PK, user_id, at, result_json)`
  - `changes(seq INTEGER PRIMARY KEY AUTOINCREMENT, job_id, entity, entity_id, data_json, at)`
  - `positions(user_id PK, lat, lon, accuracy_m, altitude_m, at, paused)`
  - `events(id INTEGER PRIMARY KEY AUTOINCREMENT, job_id, user_id, kind, data_json, at)`
  - `app_releases(version_code PK, version_name, file_path, sha256, created_at)`
  Indexes on `points(job_id)`, `photos(point_id)`, `photos(status, next_attempt_at)`, `photos(lat, lon)`, `changes(job_id)`.

- [ ] **Step 2: Write the failing tests:** `migrationsCreateAllTables`, `migrationsIdempotent`, `versionEndpointReturnsBuild` (`GET /api/version` → `{"build":"test"}`), `configDefaults`.

- [ ] **Step 3: Run to verify failure.** Run: `./gradlew :server:test --tests '*ScaffoldTest'`. Expected: FAIL.

- [ ] **Step 4: Implement.** Ktor with Netty, `ContentNegotiation(FmxJson)`, `WebSockets`, and `StatusPages` mapping exceptions to `{error}` JSON. `Main.kt` wires real collaborators: `sharePointDestinations`, `ChangeLog` (Task 13), `DeliveryWorker` (Task 17); until those exist, use no-op implementations. `TestApp` uses in-memory SQLite (`jdbc:sqlite:file:test<N>?mode=memory&cache=shared`), a temp `dataDir`, buildId `test` and a `FakeDestination`.

- [ ] **Step 5: Run to verify pass.** Expected: PASS.

- [ ] **Step 6: Commit.** `git add -A && git commit -m "feat(server): scaffold, schema, config, test harness"`

### Task 12: Accounts and sessions

**Files:** Create `server/src/main/kotlin/com/fieldmaxx/server/auth/{Passwords.kt, Sessions.kt, AuthPlugin.kt, AuthRoutes.kt}`, `server/src/main/kotlin/com/fieldmaxx/server/users/{UserService.kt, UserRoutes.kt}`, `server/src/main/kotlin/com/fieldmaxx/server/Cli.kt`; Test `server/src/test/kotlin/com/fieldmaxx/server/AuthTest.kt`

**Interfaces:**
- Produces:
```kotlin
fun hashPassword(pw: String): String; fun verifyPassword(hash: String, pw: String): Boolean   // Argon2id (Bouncy Castle), encoded with salt and params
fun createSession(ctx: Ctx, userId: String): String          // raw token (32 random bytes, base64url); stores sha256
fun sessionUser(ctx: Ctx, token: String): User?               // slides expires_at to now + sessionDays
fun createUser(ctx: Ctx, username: String, displayName: String, password: String, role: Role): User
// Ktor: `authenticate("bearer")` → call.user(): User; requireAdmin()
// Routes: POST /api/auth/login {username,password} → {token,user}; POST /api/auth/logout; GET /api/auth/me; POST /api/auth/password {current,next}
//         GET/POST /api/admin/users; PATCH /api/admin/users/{id} {displayName?, role?, disabled?, password?}
// CLI: `java -jar server.jar create-admin <username> "<Display Name>"` (password from FMX_ADMIN_PASSWORD)
```

- [ ] **Step 1: Write the failing tests:**
  - `loginReturnsTokenCaseInsensitiveUsername`
  - `wrongPassword401WithMessage` (`"Wrong username or password"`)
  - `eleventhFailure429`
  - `disabledUser403`
  - `sessionSlidesAfter89Days`
  - `sessionExpiresAfter91IdleDays`
  - `techCannotListUsers`
  - `adminCreatesResetsDisables`
  - `duplicateUsername409`
  - `changePasswordNeedsCurrent`
  - `cliCreatesAdmin`

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :server:test --tests '*AuthTest'`. Expected: FAIL.

- [ ] **Step 3: Implement** (ids via `UUID.randomUUID()`; the lockout counts `login_failures` rows from the last 15 min and clears them on success).

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(server): accounts, bearer sessions, admin users, cli"`

### Task 13: Change log, WebSocket hub, crew positions

**Files:** Create `server/src/main/kotlin/com/fieldmaxx/server/sync/{ChangeLog.kt, Hub.kt, SyncRoutes.kt}`, `server/src/main/kotlin/com/fieldmaxx/server/positions/Positions.kt`; Test `server/src/test/kotlin/com/fieldmaxx/server/SyncTest.kt`

**Interfaces:**
- Produces: `class ChangeLog(db: Db, clock: Clock, hub: Hub) : ChangeRecorder { fun since(seq: Long, limit: Int = 1000): Pair<List<Change>, Long> }`; `class Hub { fun add(session: DefaultWebSocketServerSession, user: User); fun broadcast(msg: ServerMessage) }`; `fun savePosition(ctx: Ctx, user: User, m: ClientMessage): CrewPosition`; `fun listPositions(ctx: Ctx): List<CrewPosition>` (omits positions older than 1 h). Routes: `GET /api/changes?since=N` → `{changes, seq}`; `GET /api/positions`; `WS /api/ws?token=…` (sends `Hello(seq)` on open).

- [ ] **Step 1: Write the failing tests:** `seqIncreasesAndSinceFilters`, `sinceCapsAtLimit`, `wsRejectsBadToken` (close code 4401), `wsHelloCarriesSeq`, `changeBroadcastToAll`, `positionStoredAndBroadcastWithName`, `pausedMarksPositionPaused`, `positionsOlderThanHourHidden`.

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :server:test --tests '*SyncTest'`. Expected: FAIL.

- [ ] **Step 3: Implement.** `record` inserts into `changes` and broadcasts a `ChangeMsg`. Positions are upserted and broadcast, and never written to `changes`. `Paused` keeps the last coordinates, with `paused = true` and `at = now`.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(server): change log, websocket hub, positions"`

### Task 14: Imports and jobs API

**Files:** Create `server/src/main/kotlin/com/fieldmaxx/server/jobs/{ImportRoutes.kt, JobService.kt, JobRoutes.kt, Rows.kt}`, `server/src/main/kotlin/com/fieldmaxx/server/links/LinkRoutes.kt`; Test `server/src/test/kotlin/com/fieldmaxx/server/JobsTest.kt` (test dependency `testFixtures(project(":importer"))`)

**Interfaces:**
- Consumes: `importFile` (Task 8), `ChangeRecorder`, `DestinationFactory`.
- Produces:
```kotlin
fun createJob(ctx: Ctx, user: User, input: CreateJobInput): Pair<Job, List<String> /* skippedDuplicates */>
fun addImportToJob(ctx: Ctx, user: User, jobId: String, imp: JobImportInput): Pair<List<Point>, List<String>>
fun deleteJob(ctx: Ctx, user: User, jobId: String); fun restoreJob(ctx: Ctx, user: User, jobId: String); fun purgeDeletedJobs(ctx: Ctx): Int
fun jobSnapshot(ctx: Ctx, jobId: String): JobSnapshot
fun java.sql.ResultSet.toJob(): Job; fun java.sql.ResultSet.toPoint(): Point   // Rows.kt, reused later
// Routes: POST /api/imports (multipart "file") → ImportPreview;  GET /api/imports/{importId}/ref/{refId}
//         POST /api/jobs; POST /api/jobs/{id}/imports; GET /api/jobs?include=finished,deleted → List<JobListItem>
//         GET /api/jobs/{id}/snapshot; PATCH /api/jobs/{id} {name?, fieldNotesUrl?, notes?} (new link clears deliveryProblem, calls ctx.delivery.kick())
//         DELETE /api/jobs/{id}; POST /api/jobs/{id}/restore; GET /api/jobs/{id}/ref/{refId}; GET /api/jobs/{id}/files/{fileId}
//         POST /api/links/check {url} → LinkCheck
```

- [ ] **Step 1: Write the failing tests:**
  - `uploadReturnsPreviewAndRefImages` (multipart with the aerial PDF built by `makePdf`)
  - `xlsxRejected415`
  - `createJobFromPreview`
  - `draftWithoutCoordinates400ListsLabels`
  - `everyUserSeesEveryActiveJob`
  - `snapshotHasFilesAndCrew`
  - `newLinkClearsProblemAndKicks`
  - `creatorDeletesOtherTech403AdminMay`
  - `adminRestoresWithin30DaysThen410`
  - `purgeAfter30Days`
  - `duplicateImportSkipsIdenticalPoints` (adding the same preview twice → 7 skipped, the job still has 7)
  - `sameLabelDifferentCoordsFlagged`
  - `linkCheckOkReturnsRootName`
  - `linkCheckReportsFailureReason` (destination throws `REQUIRES_SIGN_IN` → `LinkCheck(ok=false, error="This link needs a Microsoft sign-in")`)
  - `jobCreationRecordsChange`

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :server:test --tests '*JobsTest'`. Expected: FAIL.

- [ ] **Step 3: Implement.** Files go under `<dataDir>/imports/<id>/`, `<dataDir>/jobs/<jobId>/files/` and `<dataDir>/refs/`. A duplicate is the same label within 1 m. An hourly sweep deletes imports unused for 24 h and runs `purgeDeletedJobs`.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(server): imports, jobs, link check"`

### Task 15: Idempotent operations

**Files:** Create `server/src/main/kotlin/com/fieldmaxx/server/ops/{Ops.kt, OpsRoutes.kt}`; Test `server/src/test/kotlin/com/fieldmaxx/server/OpsTest.kt`

**Interfaces:**
- Produces: `fun applyOps(ctx: Ctx, user: User, ops: List<Op>): List<OpResult>`; route `POST /api/ops {ops}` → `{results}`. `Op.PhotoReuse`, `Op.PhotoRemove`, `Op.PhotoConfirmed` and `Op.PhotoFailed` are dispatched to Task 16 functions; until then they return `ok=false, error="unsupported"`.

- [ ] **Step 1: Write the failing tests:**
  - `replayReturnsStoredResultNoNewChange`
  - `updateSetsOnlyGivenFields`
  - `concurrentFieldEditsBothPersist` (tech A `PointUpdate(pole)`, tech B `PointMove` → both present)
  - `firstMoveKeepsOriginal`
  - `closeNeedsPhotoIds` (`error == "needs_photo"`)
  - `cantShootNeedsReasonOtherNeedsText`
  - `reopenClearsCant`
  - `addBuildsLabelAndAddedBy`
  - `techDeleteAllowedUntilConfirmedThenAdminOnly` (`error == "photos_delivered"`)
  - `restoreAdminOnly`
  - `setFolderStoresPathClearsCandidates`
  - `opsOnDeletedJob` (`error == "job_deleted"`)
  - `eachOpOneChangeOneEvent`

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :server:test --tests '*OpsTest'`. Expected: FAIL.

- [ ] **Step 3: Implement.** One transaction per op; the result is stored in `ops_done`; `updated_at`/`updated_by` set on every change. The delete rule checks for photos with `status = confirmed`.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(server): idempotent point and photo operations"`

### Task 16: Photo registry, backups, thumbnails, reuse search

**Files:** Create `server/src/main/kotlin/com/fieldmaxx/server/photos/{PhotoService.kt, PhotoRoutes.kt, Thumbs.kt}`; Modify `ops/Ops.kt`; Test `server/src/test/kotlin/com/fieldmaxx/server/PhotosTest.kt`

**Interfaces:**
- Produces:
```kotlin
fun registerPhoto(ctx: Ctx, user: User, reg: PhotoRegistration): RegisteredPhoto      // file name via photoFileName(defaultFolderName(point.label), lat ?: point.lat, lon ?: point.lon, nextCounter("photo"))
fun renamePhoto(ctx: Ctx, user: User, photoId: String): RegisteredPhoto              // new counter after a same-name/different-size clash
fun appendBackupChunk(ctx: Ctx, user: User, photoId: String, offset: Long, bytes: ByteArray): Pair<Long /* offset */, Boolean /* complete */>
fun reusePhoto(ctx: Ctx, user: User, op: Op.PhotoReuse): OpResult; fun removePhoto(ctx: Ctx, user: User, op: Op.PhotoRemove): OpResult
fun markConfirmed(ctx: Ctx, user: User, op: Op.PhotoConfirmed): OpResult; fun markFailed(ctx: Ctx, user: User, op: Op.PhotoFailed): OpResult
fun nearbyPhotos(ctx: Ctx, lat: Double, lon: Double, radiusM: Double = 150.0, excludePointId: String?): List<ReuseCandidate>
fun nextCounter(ctx: Ctx, name: String): Long
fun java.sql.ResultSet.toPhoto(): PhotoInfo
// Routes: POST /api/photos/register; POST /api/photos/{id}/rename; PUT /api/photos/{id}/backup?offset=N (octet-stream ≤ 2 MB) → {offset, complete}
//         GET /api/photos/{id}; GET /api/photos/{id}/thumb; GET /api/photos/{id}/file (backup while kept, else 404)
//         GET /api/photos/nearby?lat&lon&radiusM&excludePointId; GET /api/jobs/{id}/photos → List<ReuseCandidate> (this job)
```

- [ ] **Step 1: Write the failing tests:**
  - `registerAssignsCadastrusName` (`POLE_1_<lat>_<lon>_<n>.jpg`)
  - `registerIsIdempotentPerId`
  - `noGpsUsesPointCoords`
  - `backupChunksCompleteWithShaCheck`
  - `wrongOffset409WithCurrent`
  - `shaMismatch422ResetsBackup`
  - `backupOver40MB413`
  - `thumbnailMadeOnBackupComplete` (≤ 320 px on the long side)
  - `confirmedOpSetsDestAndTimingsAndChange`
  - `failedOpRecordsErrorStuckAt6`
  - `nearbyReturnsOtherJobsWithin150mSortedByDistance`
  - `reuseCreatesRegisteredPhotoWithOriginalCoordsAndKicksDelivery`
  - `removeBeforeConfirmDeletesBackupAfterOnlyHides`
  - `removeByOtherTechForbiddenAdminAllowed`
  - `renameAssignsNewCounter`

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :server:test --tests '*PhotosTest'`. Expected: FAIL.

- [ ] **Step 3: Implement.** Backups are written to `<dataDir>/photos/<id>.part`, fsynced, then renamed to `.jpg`. Thumbnails use `javax.imageio` with quality 0.8 into `<dataDir>/thumbs/<id>.jpg`. A reuse photo has `source_kind = "backup"` when the original's backup still exists, else `"onedrive"` (copied later by Task 17). Every status change records a change.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(server): photo registry, backups, thumbnails, reuse search"`

### Task 17: Server delivery worker, takeover, retention

**Files:** Create `server/src/main/kotlin/com/fieldmaxx/server/delivery/{DeliveryWorker.kt, Retention.kt}`; Test `server/src/test/kotlin/com/fieldmaxx/server/DeliveryTest.kt`

**Interfaces:**
- Consumes: `FolderResolver`, `PhotoDestination` (Tasks 9–10), `toPhoto`, `toPoint`.
- Produces:
```kotlin
fun nextDelayMs(attempts: Int): Long                     // 15s, 30s, 60s, 2m, 4m, 8m, 16m, then 30m
class DeliveryWorker(ctx: Ctx, concurrency: Int = 4) : DeliveryKick { fun start(); fun stop(); override fun kick(); fun runOnce(): Int }
fun retryPhoto(ctx: Ctx, admin: User, photoId: String)
fun purgeBackups(ctx: Ctx): Int                           // backup older than settings.backupDays (min 3) AND confirmed
fun diskUsedPct(dir: java.nio.file.Path): Int
```

- [ ] **Step 1: Write the failing tests** (FakeDestination, MutableClock):
  - `backupNotTakenOverBefore30Min`
  - `takeoverAfter30MinDeliversAndConfirms` (path `POLE/POLE_1/<fileName>`)
  - `reusePhotoDeliveredImmediatelyFromBackup`
  - `reuseFromOneDriveDownloadsOriginalThenUploads`
  - `sameNameSameSizeCountsAsDelivered`
  - `retriesFollowNextDelay`
  - `stuckAfter6AdminRetryResets`
  - `maxFourInFlight`
  - `ambiguousFolderWaitsWithCandidates`
  - `forbiddenDestinationSetsJobDeliveryProblem` (cleared on next success)
  - `noLinkSetsAddLinkProblem` (`"Add the Field Notes link"`)
  - `purgeKeepsUnconfirmedAndRespectsMinimum3Days` (setting 2 is treated as 3)
  - `diskOver80WarnsNeverPurgesEarly`

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :server:test --tests '*DeliveryTest'`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - **Due photos:** status `registered`/`stuck` and either (a) backup complete and `backup_received_at + 30 min <= now`, or (b) `reused_from != null`; also `next_attempt_at <= now`.
  - **Folder:** use `point.folder_path`, else resolve it (store it, record a change; on `Ambiguous` store the candidates and skip).
  - **Upload:** `fileInfo` check, then `uploadChunked` from the backup file (or from the OneDrive download for reuse), verify, `confirmed`.
  - **Errors:** `REQUIRES_SIGN_IN`/`FORBIDDEN` set `jobs.delivery_problem`.
  - **Schedule:** poll every 5 s; `kick()` runs immediately; one resolver per job cached for 10 min.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(server): delivery takeover, retries, backup retention"`

### Task 18: Exports and finishing a job

**Files:** Create `server/src/main/kotlin/com/fieldmaxx/server/exports/{PoleData.kt, PhotosZip.kt, Finish.kt, ExportRoutes.kt}`; Test `server/src/test/kotlin/com/fieldmaxx/server/ExportsTest.kt`

**Interfaces:**
- Produces: `val POLE_COLUMNS = listOf("Point", "Original lat", "Original lon", "Moved lat", "Moved lon", "Line height (ft)", "Anchor", "Self-supporting/Lashed", "Tags", "Comment", "Photos", "Tech", "Closed at")`; `fun poleDataRows(ctx: Ctx, jobId: String): List<List<String>>`; `fun poleDataCsv(rows: List<List<String>>): String`; `fun poleDataXlsx(rows: List<List<String>>): ByteArray`; `fun writePhotosZip(ctx: Ctx, jobId: String, out: java.io.OutputStream)`; `fun finishJob(ctx: Ctx, user: User, jobId: String): FinishResult`. Routes: `GET /api/jobs/{id}/pole-data.csv|.xlsx`; `GET /api/jobs/{id}/photos.zip` (admin); `POST /api/jobs/{id}/finish` → `FinishResult`.

- [ ] **Step 1: Write the failing tests:**
  - `csvHeaderExact`
  - `movedOnlyWhenMovedTagsSemicolonAnchorYesNo`
  - `rowsSortedNumericThenSuffix` (`1, 2, 10, 14, 14B`)
  - `xlsxReadableWithSameHeader` (read back with Apache POI in test scope)
  - `zipUsesFolderPathOrDefault`
  - `finishBlockedCounts` (`FinishResult(false, 7, 3)`)
  - `finishUploadsXlsxToRootNextFreeNameAndFinishes`

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :server:test --tests '*ExportsTest'`. Expected: FAIL.

- [ ] **Step 3: Implement** with fastexcel; the ZIP is stored (no compression). Finish uploads `<job>_pole_data.xlsx`, or `<job>_pole_data_2.xlsx` etc. if taken.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(server): pole data export, photos zip, finish job"`

### Task 19: Admin endpoints, app config, APK hosting

**Files:** Create `server/src/main/kotlin/com/fieldmaxx/server/admin/AdminRoutes.kt`, `server/src/main/kotlin/com/fieldmaxx/server/release/{ReleaseRoutes.kt, DownloadPage.kt}`; Modify `Cli.kt` (`publish-apk <path> <versionCode> <versionName>`); Test `server/src/test/kotlin/com/fieldmaxx/server/AdminTest.kt`

**Interfaces:**
- Produces:
  - **Admin:** `GET /api/admin/health → HealthReport`; `POST /api/admin/photos/{id}/retry`; `GET /api/admin/activity?jobId= → List<ActivityItem>`; `PUT /api/admin/settings AdminSettings`.
  - **Everyone:** `GET /api/config → AppConfig`.
  - **App releases:** `GET /api/app/latest → AppRelease?`; `GET /app/fieldmaxx-<versionName>.apk`; `GET /` → download page.

- [ ] **Step 1: Write the failing tests:**
  - `adminOnly`
  - `healthStuckFirstWithMedians`
  - `retryResets`
  - `activityListsCantShootReason`
  - `settingsDefaultsAndBackupDaysMin3` (setting 2 → 400 `"Backups must be kept at least 3 days"`)
  - `configDefaultsToEsriKeyless`
  - `publishApkThenLatest`
  - `downloadPageHasButtonAndSteps` (contains `Download Fieldmaxx for Android` and the three steps from §6.11)

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :server:test --tests '*AdminTest'`. Expected: FAIL.

- [ ] **Step 3: Implement.** The download page is plain HTML in the Cadastrus colours, with a viewport meta tag, a large button and three numbered steps. It works on a phone.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(server): admin endpoints, app config, apk hosting"`

---

## Milestone E: Android `app`

### Task 20: App scaffold, network client, sign-in

**Files:** Create `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/com/fieldmaxx/app/{FieldmaxxApp.kt, MainActivity.kt, AppGraph.kt, ui/Theme.kt, ui/Nav.kt}`, `app/src/main/kotlin/com/fieldmaxx/app/net/{Api.kt, OkHttpApi.kt, TokenVault.kt, AuthInterceptor.kt}`, `app/src/main/kotlin/com/fieldmaxx/app/auth/LoginScreen.kt`; Test `app/src/test/kotlin/com/fieldmaxx/app/{ManifestTest.kt, ApiTest.kt, LoginScreenTest.kt}`

**Interfaces:**
- Produces:
```kotlin
class ApiException(val status: Int, override val message: String) : Exception(message)
interface Api {   // suspend functions over OkHttp + FmxJson; base URL from BuildConfig.SERVER_URL or the login screen's server field
  suspend fun login(username: String, password: String): Pair<String, User>; suspend fun me(): User; suspend fun logout(); suspend fun changePassword(current: String, next: String)
  suspend fun config(): AppConfig; suspend fun jobs(include: String? = null): List<JobListItem>; suspend fun snapshot(jobId: String): JobSnapshot; suspend fun changes(since: Long): Pair<List<Change>, Long>
  suspend fun ops(ops: List<Op>): List<OpResult>; suspend fun importFile(name: String, bytes: ByteArray): ImportPreview; suspend fun createJob(i: CreateJobInput): Job; suspend fun addImport(jobId: String, i: JobImportInput): List<Point>
  suspend fun patchJob(jobId: String, name: String? = null, fieldNotesUrl: String? = null, notes: String? = null): Job; suspend fun checkLink(url: String): LinkCheck; suspend fun deleteJob(jobId: String); suspend fun restoreJob(jobId: String); suspend fun finishJob(jobId: String): FinishResult
  suspend fun positions(): List<CrewPosition>; suspend fun registerPhoto(r: PhotoRegistration): RegisteredPhoto; suspend fun renamePhoto(id: String): RegisteredPhoto
  suspend fun backupChunk(id: String, offset: Long, bytes: ByteArray): Pair<Long, Boolean>; suspend fun nearby(lat: Double, lon: Double, excludePointId: String?): List<ReuseCandidate>; suspend fun jobPhotos(jobId: String): List<ReuseCandidate>
  suspend fun adminUsers(): List<User>; suspend fun createUser(username: String, displayName: String, password: String, role: Role): User; suspend fun patchUser(id: String, displayName: String? = null, role: Role? = null, disabled: Boolean? = null, password: String? = null): User
  suspend fun health(): HealthReport; suspend fun retryPhoto(id: String); suspend fun activity(jobId: String?): List<ActivityItem>; suspend fun putSettings(s: AdminSettings)
  suspend fun latestRelease(): AppRelease?; suspend fun download(path: String, to: java.io.File)
}
interface TokenVault { fun save(token: String?); fun load(): String? }   // AndroidKeystoreTokenVault (AES-GCM key in AndroidKeyStore, ciphertext in DataStore); InMemoryTokenVault for tests
class AppGraph(context: Context) { val api: Api; val vault: TokenVault /* later tasks add db, sync, location, compass, photos, mapControllerFactory */ }
```

- [ ] **Step 1: Scaffold `app`.**
  - **Gradle:** Compose, Kotlin serialization and KSP plugins; `buildConfigField SERVER_URL`; release `signingConfig` read from git-ignored `keystore.properties` when present; `testOptions.unitTests.isIncludeAndroidResources = true`; Robolectric with `@GraphicsMode(NATIVE)` available.
  - **Manifest:** `INTERNET`, `CAMERA`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `REQUEST_INSTALL_PACKAGES`, `POST_NOTIFICATIONS`; a `FileProvider`; intent filters on `MainActivity` for `ACTION_SEND`/`ACTION_SEND_MULTIPLE` of `application/pdf`, `application/vnd.openxmlformats-officedocument.wordprocessingml.document` and `text/plain`.
  - **Theme:** dark, with the Global Constraints colours.

- [ ] **Step 2: Write the failing tests:**
  - `ManifestTest.noBackgroundLocation`: parse the merged manifest; `ACCESS_BACKGROUND_LOCATION` absent, no `<service android:foregroundServiceType="location">`.
  - `ApiTest` (MockWebServer): `bearerHeaderSent`, `unauthorizedMapsToApiException`, `opsSerialiseWithKind`.
  - `LoginScreenTest`: `showsWrongPasswordMessage`, `successNavigatesToJobs`.

- [ ] **Step 3: Run to verify failure.** Run: `./gradlew :app:testDebugUnitTest`. Expected: FAIL.

- [ ] **Step 4: Implement.** The login screen has server address (prefilled from `BuildConfig`), username and password fields.

- [ ] **Step 5: Run to verify pass.** Expected: PASS. Also run `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.

- [ ] **Step 6: Commit.** `git add -A && git commit -m "feat(app): scaffold, api client, token vault, login"`

### Task 21: Local database and sync engine

**Files:** Create `app/src/main/kotlin/com/fieldmaxx/app/data/{AppDatabase.kt, Entities.kt, Daos.kt, Converters.kt}`, `app/src/main/kotlin/com/fieldmaxx/app/sync/{SyncEngine.kt, OpApplier.kt, LiveSocket.kt, OutboxWorker.kt}`; Test `app/src/test/kotlin/com/fieldmaxx/app/sync/SyncEngineTest.kt`

**Interfaces:**
- Produces:
```kotlin
// Room entities store core models as JSON columns
@Entity data class JobEntity(@PrimaryKey val id: String, val json: String, val updatedAt: Long)
@Entity(indices = [Index("jobId")]) data class PointEntity(@PrimaryKey val id: String, val jobId: String, val json: String)
@Entity(indices = [Index("pointId"), Index("jobId")]) data class PhotoInfoEntity(@PrimaryKey val id: String, val jobId: String, val pointId: String, val json: String)
@Entity data class OutboxEntity(@PrimaryKey(autoGenerate = true) val seq: Long = 0, val opJson: String)
@Entity data class MetaEntity(@PrimaryKey val key: String, val value: String)
@Entity(indices = [Index("pointId"), Index("state")]) data class LocalPhotoEntity(@PrimaryKey val id: String, val jobId: String, val pointId: String, val originalPath: String, val stampedPath: String?, val metaJson: String, val state: String /* saved|stamped|registered|confirmed */, val fileName: String?, val oneDriveUploadId: String?, val oneDriveOffset: Long, val createdThisFile: Boolean, val backupOffset: Long, val backupDone: Boolean, val attempts: Int, val nextAttemptAt: Long, val lastError: String?, val stuck: Boolean, val createdAt: Long, val confirmedAt: Long?)
enum class SyncStatus { ONLINE, OFFLINE, SYNCING }
class SyncEngine(api: Api, db: AppDatabase, live: LiveSocket, scope: CoroutineScope, now: () -> Long) {
  val status: StateFlow<SyncStatus>; val authRequired: SharedFlow<Unit>; val crew: StateFlow<List<CrewPosition>>
  fun start(user: User); fun stop(); suspend fun openJob(jobId: String); suspend fun enqueue(op: Op)
  fun points(jobId: String): Flow<List<Point>>; fun job(jobId: String): Flow<Job?>; fun photos(pointId: String): Flow<List<PhotoInfo>>; fun jobPhotos(jobId: String): Flow<List<PhotoInfo>>
}
class OpApplier(db: AppDatabase) { suspend fun applyLocally(op: Op, userId: String, now: Long); suspend fun applyChange(c: Change) }
class LiveSocket(client: OkHttpClient, url: () -> String, token: () -> String?) { val messages: SharedFlow<ServerMessage>; val connected: StateFlow<Boolean>; fun connect(); fun close(); fun send(m: ClientMessage) }   // reconnect backoff 1 s → 30 s
```

- [ ] **Step 1: Write the failing tests** (Robolectric, in-memory Room, fake Api):
  - `enqueueAppliesLocallyAtOnce`
  - `offlineOpsSurviveRestartAndFlushInOrder`
  - `serverChangeOverwritesLocal`
  - `reconnectFetchesChangesSinceLastSeq`
  - `unauthorizedPausesOutboxKeepsOps`
  - `outboxWorkerFlushesInBackground` (work-testing)

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :app:testDebugUnitTest --tests '*SyncEngineTest'`. Expected: FAIL.

- [ ] **Step 3: Implement.** Flush posts batches of ≤ 50 in order, with backoff 2 s → 60 s. An `OutboxWorker` (network constraint) is enqueued whenever the outbox is non-empty. `meta.lastSeq` updates after applying changes. `openJob` stores the snapshot and prefetches reference images into Coil's disk cache.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(app): room store and sync engine"`

### Task 22: Foreground location, compass, position sender

**Files:** Create `app/src/main/kotlin/com/fieldmaxx/app/platform/{Location.kt, Compass.kt, PositionSender.kt}`; Test `app/src/test/kotlin/com/fieldmaxx/app/platform/PlatformTest.kt`

**Interfaces:**
- Produces:
```kotlin
data class Fix(val lat: Double, val lon: Double, val accuracyM: Double, val altitudeM: Double?, val at: Long)
interface LocationSource { val fixes: StateFlow<Fix?>; fun start(); fun stop() }          // FusedLocationProviderClient, high accuracy, 2 s interval
data class Heading(val deg: Double, val at: Long, val reliable: Boolean)
interface CompassSource { val heading: StateFlow<Heading?>; fun start(); fun stop() }       // TYPE_ROTATION_VECTOR; reliable = accuracy ≥ SENSOR_STATUS_ACCURACY_MEDIUM
fun azimuthFromRotationVector(rv: FloatArray, displayRotation: Int): Double               // remaps axes for screen rotation; 0..360
fun shouldSend(prev: Pair<Fix, Long>?, next: Fix, now: Long): Boolean                       // > 5 m or ≥ 15 s
class ForegroundTracker(lifecycle: Lifecycle /* ProcessLifecycleOwner */, location: LocationSource, compass: CompassSource, live: LiveSocket, now: () -> Long)   // ON_START: start + send; ON_STOP: send Paused, stop
```

- [ ] **Step 1: Write the failing tests:**
  - `firstFixSent`
  - `fourMetresWithin15sNotSent`
  - `sixMetresSent`
  - `fifteenSecondsSendsWithoutMovement`
  - `azimuthNorthPortrait`
  - `azimuthRemappedForLandscape` (known rotation-vector fixtures)
  - `onStopSendsPausedAndStopsUpdates`
  - `noUpdatesWhileStopped` (a lifecycle test with `TestLifecycleOwner`)

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :app:testDebugUnitTest --tests '*PlatformTest'`. Expected: FAIL.

- [ ] **Step 3: Implement.** Use `SensorManager.getRotationMatrixFromVector` → `remapCoordinateSystem` by display rotation → `getOrientation`. Permission requests are handled on the map screen (Task 24).

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(app): foreground location, compass, position sender"`

### Task 23: Jobs: list, import, check screen, job info, share target

**Files:** Create `app/src/main/kotlin/com/fieldmaxx/app/jobs/{JobListScreen.kt, NewJobScreen.kt, CheckModel.kt, CheckScreen.kt, JobInfoScreen.kt, FieldNotesLinkField.kt, ShareIntake.kt}`; Test `app/src/test/kotlin/com/fieldmaxx/app/jobs/{CheckModelTest.kt, JobsScreensTest.kt, ShareIntakeTest.kt}`

**Interfaces:**
- Produces:
```kotlin
data class CheckState(val previews: List<ImportPreview>, val edits: Map<String, List<DraftPoint>>, val ignoredUnread: Set<String>, val layers: Map<String, String>)
fun summary(points: List<DraftPoint>): List<String>        // "Found 7 POLE points"; "1 needs coordinates"
fun canSave(s: CheckState): Boolean
fun fixUnread(s: CheckState, importId: String, line: UnreadLine, label: String, coords: String): CheckState   // throws IllegalArgumentException("Coordinates not recognised")
fun toJobImports(s: CheckState): List<JobImportInput>
fun flagText(f: ImportFlag): String   // "Possibly swapped", "Sign differs", "Far from the rest", "Duplicate label", "No label", "No coordinates"
sealed interface SharedIntake { data class Files(val uris: List<android.net.Uri>) : SharedIntake; data class Link(val url: String) : SharedIntake }
fun parseShareIntent(intent: android.content.Intent): SharedIntake?        // text containing sharepoint.com / 1drv.ms / onedrive.live.com → Link
```

- [ ] **Step 1: Write the failing tests:**
  - `summaryPerType`
  - `saveDisabledUntilNoCoordinatesFixedOrRemoved`
  - `fixUnreadAddsPoint`
  - `ignoreUnreadEnablesSave`
  - `flagBadgesText`
  - `jobListShowsProgressAndFinishedSection`
  - `deleteOnlyForCreatorOrAdminConfirmNamesJob`
  - `linkCheckShowsRootOrError`
  - `saveWithoutLinkShowsPhotosWillWait` (`"Photos will wait until the Field Notes link is added"`)
  - `jobInfoOpensSourceFile` (downloads `/api/jobs/{id}/files/{fileId}` and fires `ACTION_VIEW`)
  - `shareIntentPdfIsFiles`
  - `shareIntentTextWithOneDriveLinkIsLink`

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :app:testDebugUnitTest --tests 'com.fieldmaxx.app.jobs.*'`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - **New job:** `ActivityResultContracts.OpenMultipleDocuments` with the PDF/DOCX MIME types, then upload each file → check screen. The job name defaults to the first file's name without its extension (`A05J9XE.pdf` → `A05J9XE`) and is editable.
  - **Check screen cards:** summary lines, a list of point cards (edit/remove; a small map preview through `MapController` is optional in v1), "Couldn't read" fix/ignore, layer field, Field Notes link with Check, Save.
  - **Share intake:** shows "New job / Add to job".

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(app): job list, import check screen, job info, share target"`

### Task 24: Map screen

**Files:** Create `app/src/main/kotlin/com/fieldmaxx/app/map/{MapModel.kt, ViewMemory.kt, MapController.kt, MapLibreController.kt, MapScreen.kt, TopBar.kt, ChipRow.kt, ProgressStrip.kt, SearchSheet.kt, LeftList.kt}`; Test `app/src/test/kotlin/com/fieldmaxx/app/map/{MapModelTest.kt, MapScreenTest.kt}`

**Interfaces:**
- Produces:
```kotlin
data class TypeProgress(val type: String, val done: Int, val total: Int); data class Progress(val done: Int, val total: Int, val pct: Int, val byType: List<TypeProgress>)
fun progress(points: List<Point>): Progress                // done + cant_shoot complete; deleted excluded
fun visiblePoints(points: List<Point>, type: String?, hideShot: Boolean): List<Point>
fun chipLabel(t: TypeProgress): String                       // "✓ POLE (80/80)" | "POLE (12/80)"
fun sunsetText(nowMs: Long, lat: Double, lon: Double, zone: java.time.ZoneId): String   // "☀️ 5h 40m until sunset" | "🌙 After sunset" (commons-suncalc)
fun remainingByDistance(points: List<Point>, from: LatLon?): List<Point>
enum class CrewDot { FRESH, PAUSED, FADED, HIDDEN }; fun crewDotState(p: CrewPosition, now: Long): CrewDot   // paused → PAUSED; ≥ 10 min FADED; ≥ 1 h HIDDEN
fun searchPoints(points: List<Point>, q: String): List<Point>
data class CameraView(val lat: Double, val lon: Double, val zoom: Double)
interface ViewMemory { suspend fun save(userId: String, jobId: String, v: CameraView); suspend fun load(userId: String, jobId: String): CameraView? }   // DataStore
data class HouseNumber(val lat: Double, val lon: Double, val text: String)
interface MapController {   // MapLibre lives behind this so screens are testable (native libs don't load in Robolectric)
  fun setConfig(c: AppConfig); fun setPoints(points: List<Point>, pendingPhotoPointIds: Set<String>); fun setCrew(crew: List<CrewPosition>, now: Long); fun setMyFix(f: Fix?)
  fun moveTo(v: CameraView); fun fitAll(points: List<Point>); val camera: StateFlow<CameraView?>; val pointTaps: SharedFlow<String>
  fun setLabelsVisible(v: Boolean); fun setHouseNumbers(items: List<HouseNumber>); fun beginDrag(pointId: String): Flow<LatLon>; fun endDrag()
}
```

- [ ] **Step 1: Write the failing tests:**
  - **Model:**
    - `progressCountsCantShootIgnoresDeleted`
    - `chipLabels`
    - `sunsetFormat` (5 h 40 min before commons-suncalc's sunset at 39.53, -119.81 → `☀️ 5h 40m until sunset`; 1 min after → `🌙 After sunset`)
    - `crewDots` (9 min FRESH, paused 2 min PAUSED, 11 min FADED, 61 min HIDDEN)
    - `searchMatchesLabelNumDescription`
  - **Screen** (`FakeMapController`):
    - `restoresSavedViewOnOpen`
    - `fitsAllWhenNoSavedView`
    - `savesViewOnCameraMove`
    - `hideShotFilters`
    - `chipFiltersType`
    - `pillShowsOnlineOfflineWaiting` (`"3 photos waiting"`)
    - `deliveryProblemBanner`
    - `asksOnlyWhileInUseLocationPermission`

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :app:testDebugUnitTest --tests 'com.fieldmaxx.app.map.*'`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - **`MapLibreController`** wraps `MapView` in `AndroidView`:
    - raster sources from `AppConfig` (imagery; labels at opacity 0.9);
    - points as a GeoJSON source with `cluster = true` and `clusterMaxZoom = 17`;
    - circle and symbol layers using data-driven colour per type and grey 55% opacity for shot points, with a badge for pending photos;
    - crew as a separate source;
    - viewed tiles cached by MapLibre's ambient cache.
  - **Overlays:** top bar, chips and progress are Compose overlays matching §6.3.

- [ ] **Step 4: Run to verify pass.** Expected: PASS. Then run `./gradlew :app:assembleDebug` → success.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(app): map screen with filters, progress, crew, view memory"`

### Task 25: Point sheet

**Files:** Create `app/src/main/kotlin/com/fieldmaxx/app/point/{PointSheet.kt, PoleDataForm.kt, QueryReference.kt, CloseControls.kt, MoveFlow.kt, FolderLine.kt, DeletePoint.kt, Gps.kt}`, `app/src/main/kotlin/com/fieldmaxx/app/photos/PhotoView.kt` (the type plus a stub `fun photoViewsFor(pointId: String): Flow<List<PhotoView>> = flowOf(emptyList())`, replaced in Task 29); Test `app/src/test/kotlin/com/fieldmaxx/app/point/PointSheetTest.kt`

**Interfaces:**
- Produces:
```kotlin
fun navigateUri(lat: Double, lon: Double): android.net.Uri      // https://www.google.com/maps/dir/?api=1&destination=<lat>,<lon>
enum class Tone { GREEN, AMBER, RED, NONE }; data class GpsBadge(val text: String, val tone: Tone)
fun gpsBadge(f: Fix?): GpsBadge                       // "GPS ±12 ft"; ≤16 GREEN, ≤50 AMBER, else RED; null → "No GPS yet"
val TYPE_INSTRUCTIONS: Map<String, String>            // "POLE" → "Photograph the pole and the carrier equipment on it."
enum class PhotoUiStatus { ON_PHONE, UPLOADING, IN_ONEDRIVE, STUCK }
data class PhotoView(val id: String, val thumb: Any /* java.io.File or URL String */, val status: PhotoUiStatus, val backedUp: Boolean, val error: String?, val destPath: String?, val ownerId: String)
fun canDeletePoint(user: User, photos: List<PhotoView>): Boolean   // admin, or none IN_ONEDRIVE
```

- [ ] **Step 1: Write the failing tests:**
  - `poleFormEnqueuesUpdate` (25.5 ft, anchor, lashed, tags)
  - `doneDisabledWithoutPhotos`
  - `cantShootReasonRequiredOtherNeedsText`
  - `navigateIntentUri`
  - `moveConfirmEnqueuesMoveUndoWithin10sRestores`
  - `deleteHiddenAfterOneDriveForTech`
  - `deleteWarnsPhotosOnPhoneDiscarded` (`"2 photos on this phone will be discarded"`)
  - `queryShowsReferenceImages`
  - `ambiguousFolderPickerEnqueuesSetFolder`
  - `folderLineShowsPath` (`"→ Field Notes/POLE/POLE_18"`)
  - `commentDebouncedSave`

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :app:testDebugUnitTest --tests 'com.fieldmaxx.app.point.*'`. Expected: FAIL.

- [ ] **Step 3: Implement** a `ModalBottomSheet` in the §6.4 order. Reference images load with Coil (auth header). Move uses `MapController.beginDrag`. The comment and pole fields save after a 500 ms debounce.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(app): point sheet"`

### Task 26: Add point flow and "Points I added"

**Files:** Create `app/src/main/kotlin/com/fieldmaxx/app/map/AddPointFlow.kt`, `app/src/main/kotlin/com/fieldmaxx/app/menu/PointsIAddedScreen.kt`; Test `app/src/test/kotlin/com/fieldmaxx/app/map/AddPointTest.kt`

**Interfaces:**
- Produces: `fun suggestName(points: List<Point>, type: String, layer: String, at: LatLon): String` (POLE → the nearest pole's number via `suggestAddedPoleNum`, returned as a label like `"POLE 14B"`; others → `nextNum` of that type's numbers, e.g. `"Street images 3"`); `fun typeChoices(jobLayers: List<String>): List<String>` (job layers, then `ADD_CATEGORIES`, de-duplicated).

- [ ] **Step 1: Write the failing tests:** `nearestPole14SuggestsPOLE14B`, `with14BSuggests14C`, `streetImagesNext3`, `typeChoicesOrderNoDupes`, `confirmEnqueuesAddAtCrosshairOpensSheet`, `pointsIAddedOnlyMine`.

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :app:testDebugUnitTest --tests '*AddPointTest'`. Expected: FAIL.

- [ ] **Step 3: Implement.** A centre crosshair over the map, starting at the latest fix (else the map centre); then a type picker; then an editable name.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(app): add point flow"`

### Task 27: Camera with live stamp overlay, gallery pick

**Files:** Create `app/src/main/kotlin/com/fieldmaxx/app/camera/{CameraScreen.kt, CaptureController.kt, CameraXCaptureController.kt, StampOverlay.kt, GalleryPick.kt}`, `app/src/main/kotlin/com/fieldmaxx/app/photos/{LocalPhotos.kt, CaptureMeta.kt}`; Test `app/src/test/kotlin/com/fieldmaxx/app/camera/CameraTest.kt`

**Interfaces:**
- Produces:
```kotlin
@Serializable data class CaptureMeta(val jobName: String, val pointLabel: String, val source: String /* camera|gallery */, val lat: Double?, val lon: Double?, val accuracyM: Double?, val altitudeM: Double?, val headingDeg: Double?, val takenAt: Long, val gpsStale: Boolean)
interface CaptureController { suspend fun capture(to: java.io.File) }   // CameraX ImageCapture, CAPTURE_MODE_MAXIMIZE_QUALITY
fun interface PhotoPipelineStarter { fun start(localPhotoId: String) }   // Task 28 provides the real one
class LocalPhotos(db: AppDatabase, filesDir: java.io.File, starter: PhotoPipelineStarter, now: () -> Long) {
  suspend fun saveCaptured(jobId: String, pointId: String, meta: CaptureMeta, write: suspend (java.io.File) -> Unit): String   // file written and row inserted (state "saved") BEFORE starter.start(id)
  suspend fun saveFromGallery(jobId: String, pointId: String, uri: android.net.Uri, jobName: String, pointLabel: String, fallback: Fix?): String
}
fun overlayLines(jobName: String, pointLabel: String, fix: Fix?, heading: Heading?, now: Long, zone: java.time.ZoneId): List<String>   // = stampLines, gpsStale when fix older than 10 s
```

- [ ] **Step 1: Write the failing tests:**
  - `shutterUsesHeadingAndFixAtPressTime`
  - `staleFixMarked`
  - `originalPersistedBeforePipelineStarts` (the starter asserts the row and file exist)
  - `failingWriteLeavesNoRow`
  - `galleryUsesExifGpsWhenPresent` (an EXIF fixture JPEG)
  - `galleryWithoutExifUsesFixAndMarksFromGallery`
  - `burstCounterIncrements`
  - `compassCalibrationHintWhenUnreliable` (`"Compass: calibrate"`)

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :app:testDebugUnitTest --tests '*CameraTest'`. Expected: FAIL.

- [ ] **Step 3: Implement** a CameraX `Preview` + `ImageCapture` (flash, pinch zoom). The overlay re-renders `overlayLines` every 500 ms. The Photo Picker uses `PickMultipleVisualMedia(ImageOnly)` and copies into app storage.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(app): camera with live stamp overlay and gallery pick"`

### Task 28: Photo pipeline workers

**Files:** Create `app/src/main/kotlin/com/fieldmaxx/app/photos/{PhotoPipeline.kt, Stamper.kt, ExifWriter.kt, StampWorker.kt, RegisterWorker.kt, OneDriveWorker.kt, BackupWorker.kt, CleanupWorker.kt, GallerySaver.kt, UploadGate.kt}`; Test `app/src/test/kotlin/com/fieldmaxx/app/photos/{StamperTest.kt, PipelineTest.kt}`

**Interfaces:**
- Consumes: `stampLines`, `stampLayout` (Task 3); `FolderResolver`, `PhotoDestination`, `ChunkSession`, `DestinationFactory` (Tasks 9–10); `Api` (Task 20); `SyncEngine`, `LocalPhotoEntity` (Task 21); `CaptureMeta`, `PhotoPipelineStarter` (Task 27).
- Produces:
```kotlin
fun targetSize(w: Int, h: Int, maxPixels: Int = 16_000_000): Pair<Int, Int>
object Stamper { fun stamp(original: java.io.File, out: java.io.File, meta: CaptureMeta, zone: java.time.ZoneId, quality: Int) }   // decode (inSampleSize + exact scale), apply EXIF rotation, draw stamp, compress, write EXIF
object ExifWriter { fun write(file: java.io.File, meta: CaptureMeta, make: String?, model: String?) }
class PhotoPipeline(context: Context, db: AppDatabase, api: Api, destinations: DestinationFactory, sync: SyncEngine, now: () -> Long) : PhotoPipelineStarter {
  override fun start(localPhotoId: String); fun retryNow(localPhotoId: String); fun resumeAll()   // resumeAll on app start: re-enqueue every unfinished photo from its persisted state
}
object UploadGate { val oneDrive: kotlinx.coroutines.sync.Semaphore /* 3 permits */; val backup: kotlinx.coroutines.sync.Semaphore /* 1 permit */ }
fun nextDelayMs(attempts: Int): Long   // 15 s doubling, 30 min cap
```
The workers are unique per photo and step (`ExistingWorkPolicy.KEEP`), all with a network constraint except `StampWorker`.

- [ ] **Step 1: Write the failing tests:**
  - **Stamper** (Robolectric `@GraphicsMode(NATIVE)`):
    - `targetSizeCaps48MpTo16MpKeepingAspect` (`8000×6000` → `(4618, 3464)`);
    - `keeps3000x4000`;
    - `stampedFileHasExifGpsDirectionAndDate`;
    - `stampDrawnInBottomLeft` (pixels in the stamp box differ from the source, the top-right does not);
    - `qualitySettingChangesSize`.
  - **Pipeline** (FakeDestination + fake Api + work-testing `TestDriver`):
    - `happyPathSavedToConfirmedAndBackedUp` (file at `POLE/POLE_1/<server fileName>`; `Op.PhotoConfirmed` enqueued);
    - `pipelineSurvivesAKillAfterEveryStep` (run the steps one at a time, rebuild `PhotoPipeline` from the database between each, call `resumeAll()`; exactly one file in the fake and exactly one confirmed op);
    - `resumesChunkedUploadFromPersistedOffset` (the fake drops after chunk 1; a new worker run resumes from that offset);
    - `sameNameSameSizeCountsAsDelivered`;
    - `sameNameDifferentSizeRenamesViaServer`;
    - `ambiguousFolderWaitsForChoice`;
    - `retriesThenStuckAfter6ReportsFailedOp`;
    - `retryNowForcesAttempt`;
    - `neverMoreThan3OneDriveUploads`;
    - `backupRunsWhenOneDriveFails`;
    - `serverConfirmedFirstMarksLocalConfirmed` (the server took over: a `PhotoInfo` change to `confirmed` stops the phone upload);
    - `cleanupDeletesOnly3DaysAfterConfirmedAndBackedUp`;
    - `galleryCopyWhenSettingOn`;
    - `queueSurvivesOfflineHours` (30 photos with no network, then network → each uploads once).

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :app:testDebugUnitTest --tests 'com.fieldmaxx.app.photos.*'`. Expected: FAIL.

- [ ] **Step 3: Implement.** Steps and their persisted state:
  1. **StampWorker:** `saved` → `stamped`.
  2. **RegisterWorker:** `api.registerPhoto` → `fileName` → `registered`.
  3. **OneDriveWorker:**
     - folder from `point.folderPath`, else `FolderResolver` + enqueue `Op.PointSetFolder`;
     - `fileInfo` check;
     - `uploadChunked` (resume from `oneDriveUploadId`/`oneDriveOffset`; persist progress after each chunk; set `createdThisFile`);
     - verify;
     - enqueue `Op.PhotoConfirmed`;
     - state `confirmed`.
  4. **BackupWorker:** chunks to `PUT /api/photos/{id}/backup` from `backupOffset`, lower priority, independent of the OneDrive result.
  5. **CleanupWorker:** periodic, every 6 h.

  Failures: `attempts++`, schedule the next run with `nextDelayMs`; at 6 attempts set `stuck` and enqueue `Op.PhotoFailed(error, attempts)`. `FieldmaxxApp.onCreate` calls `resumeAll()`. JPEG quality comes from `AppConfig.photoQuality`.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(app): resilient photo pipeline to OneDrive and server backup"`

### Task 29: Photo UI, reuse picker, export to OneDrive app

**Files:** Create `app/src/main/kotlin/com/fieldmaxx/app/photos/{PhotoViews.kt, PhotoStrip.kt, WherePhotosWentScreen.kt, ReusePicker.kt, ExportZip.kt, DeletePhoto.kt}`; Modify `photos/PhotoView.kt` (replace the stub); Test `app/src/test/kotlin/com/fieldmaxx/app/photos/PhotoUiTest.kt`

**Interfaces:**
- Produces: `fun photoViews(local: List<LocalPhotoEntity>, server: List<PhotoInfo>): List<PhotoView>`; `fun photoViewsFor(pointId: String): Flow<List<PhotoView>>` (real version, combining Room flows); `fun pendingCount(local: List<LocalPhotoEntity>, server: List<PhotoInfo>): Int`; `fun buildExportZip(photos: List<LocalPhotoEntity>, points: Map<String, Point>, out: java.io.File)` (paths `<folderPath or Category/defaultFolder>/<fileName or local id>.jpg`); `fun canDeletePhoto(user: User, p: PhotoView): Boolean` (owner before IN_ONEDRIVE; admin always; after IN_ONEDRIVE it only hides).

- [ ] **Step 1: Write the failing tests:**
  - `statusMapping` (saved/stamped → ON_PHONE; registered → UPLOADING; confirmed → IN_ONEDRIVE; stuck → STUCK)
  - `shieldWhenBackedUp`
  - `pendingCountNoDoubleCounting`
  - `stripShowsIconsAndStuckReasonWithRetryNow`
  - `whereWentListsPathsAndOpensFieldNotesLink`
  - `reusePickerTabsNearbyThisJobSearch` (nearby shows `"80 m"`; after picking, the point shows `"reused from POLE 5 · A05J9XE · 11/05/26"`)
  - `reuseEnqueuesPhotoReuseOps`
  - `exportZipFieldNotesLayoutOnlyUnconfirmed` (shared through the share sheet via `FileProvider`)
  - `deletePhotoRules`

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :app:testDebugUnitTest --tests '*PhotoUiTest'`. Expected: FAIL.

- [ ] **Step 3: Implement.** Thumbnails come from the stamped local file, else the server thumb (`/api/photos/{id}/thumb`) via Coil. The reuse picker calls `api.nearby` (excluding this point), `api.jobPhotos`, and filters by label on search.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(app): photo statuses, reuse picker, export to OneDrive app"`

### Task 30: Menu, admin screens, exports, finish job, in-app update, settings

**Files:** Create `app/src/main/kotlin/com/fieldmaxx/app/menu/{MenuSheet.kt, SettingsScreen.kt}`, `app/src/main/kotlin/com/fieldmaxx/app/admin/{UsersScreen.kt, AdminJobsScreen.kt, DeliveryHealthScreen.kt, ActivityScreen.kt, AdminSettingsScreen.kt}`, `app/src/main/kotlin/com/fieldmaxx/app/update/{UpdateChecker.kt, UpdateInstaller.kt}`, `app/src/main/kotlin/com/fieldmaxx/app/jobs/{ExportPoleData.kt, FinishJob.kt}`; Test `app/src/test/kotlin/com/fieldmaxx/app/menu/MenuAdminUpdateTest.kt`

**Interfaces:**
- Produces: `class UpdateChecker(api: Api, currentVersionCode: Int, now: () -> Long) { suspend fun check(force: Boolean = false): AppRelease? }` (at most once a day unless forced); `class UpdateInstaller(context: Context, api: Api) { suspend fun downloadAndInstall(r: AppRelease) }` (verifies sha256, then `ACTION_VIEW` via `FileProvider`; if `!packageManager.canRequestPackageInstalls()` it opens `ACTION_MANAGE_UNKNOWN_APP_SOURCES` first).

- [ ] **Step 1: Write the failing tests:**
  - `menuAdminSectionOnlyForAdmins`
  - `stuckRedCountOnDeliveryHealth`
  - `deliveryHealthDiskAlertAndMedians`
  - `usersCreateShowsTempPasswordOnce`
  - `adminRestoresDeletedJob`
  - `adminSettingsBackupDaysMin3Message`
  - `exportPoleDataSavesOrShares`
  - `finishBlockedMessage` (`"7 points left, 3 photos not in OneDrive yet"`)
  - `updateCheckOncePerDay`
  - `updateBannerText` (`"Update available — tap to install"`)
  - `installerRejectsShaMismatch`
  - `gallerySettingToggles`

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :app:testDebugUnitTest --tests '*MenuAdminUpdateTest'`. Expected: FAIL.

- [ ] **Step 3: Implement** the menu from §6.12 and the admin screens from §6.9, as plain lists in the theme. Exports download to cache and offer "Save to Downloads" (MediaStore) or "Share".

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(app): menu, admin, exports, finish job, in-app update"`

### Task 31: House-number overlay

**Files:** Create `app/src/main/kotlin/com/fieldmaxx/app/map/HouseNumbers.kt`; Test `app/src/test/kotlin/com/fieldmaxx/app/map/HouseNumbersTest.kt`

**Interfaces:**
- Consumes: `HouseNumber`, `MapController.setHouseNumbers` (Task 24).
- Produces: `data class BBox(val s: Double, val w: Double, val n: Double, val e: Double)`; `fun overpassQuery(b: BBox): String`; `fun parseHouseNumbers(json: String): List<HouseNumber>`; `class HouseNumberLoader(client: OkHttpClient) { suspend fun load(b: BBox): List<HouseNumber> }` (cached per bbox rounded to 3 decimals).

- [ ] **Step 1: Write the failing tests:** `queryHasHousenumberBboxOutCenter`, `parsesNodesAndWayCentersSkipsMissing`.

- [ ] **Step 2: Run to verify failure.** Run: `./gradlew :app:testDebugUnitTest --tests '*HouseNumbersTest'`. Expected: FAIL.

- [ ] **Step 3: Implement.** POST to `https://overpass-api.de/api/interpreter` on camera idle at zoom ≥ 17, debounced 600 ms. Draw via `MapController.setHouseNumbers` as a white bold 12 sp symbol layer with a dark halo. Toggle with the `123` button.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(app): house-number overlay"`

---

## Milestone F: Ship

### Task 32: Server deployment, release signing, APK publishing

**Files:** Create `server/Dockerfile`, `docker-compose.yml`, `deploy/Caddyfile`, `deploy/setup.sh`, `deploy/backup.sh`, `deploy/README.md`, `tools/make-release-key.sh`, `tools/release.sh`; Modify `server/build.gradle.kts` (Ktor fat jar), `app/build.gradle.kts` (versionCode from `-PversionCode`)

**Interfaces:**
- Produces:
  - `tools/make-release-key.sh <out.keystore>`: `keytool` RSA 4096, validity 30 years. Writes `keystore.properties` (git-ignored) and prints a reminder to back up both files.
  - `tools/release.sh <versionCode> <versionName> <server ssh>`: `./gradlew :app:assembleRelease -PversionCode=…`, copies the APK to the server, runs `publish-apk`.

- [ ] **Step 1: Write the deployment files.**
  - **`Dockerfile`:** `eclipse-temurin:21-jre`, the fat jar, volume `/data`, `EXPOSE 8080`.
  - **`docker-compose.yml`:** `app` + `caddy:2` (`{$DOMAIN} { reverse_proxy app:8080 }`).
  - **`setup.sh`:** for Ubuntu. Installs Docker; asks for the domain (suggest `<ip>.sslip.io`), admin username, display name and password; writes `.env`; `docker compose up -d --build`; `create-admin`; nightly cron for `backup.sh`.
  - **`backup.sh`:** `sqlite3 .backup` to `/data/backups`, keeping 14.
  - **`README.md`:**
    - server requirements from §7.3 (US-West, 2–4 GB RAM, ≥ 80 GB disk, ≥ 1 Gbps, ≥ 2 TB transfer);
    - setup;
    - publishing an app update;
    - **back up the release key**;
    - restoring a backup;
    - adding an ArcGIS key later.

- [ ] **Step 2: Verify.**
  - `bash -n deploy/setup.sh deploy/backup.sh tools/*.sh` → no output.
  - `./gradlew :server:buildFatJar` → BUILD SUCCESSFUL.
  - `FMX_DATA_DIR=$(mktemp -d) FMX_DESTINATION=fake java -jar server/build/libs/*-all.jar &`, then `curl -s localhost:8080/api/version` → JSON and `curl -s localhost:8080/ | grep 'Download Fieldmaxx for Android'` → match.
  - With a throwaway key: `tools/make-release-key.sh /tmp/test.keystore && ./gradlew :app:assembleRelease -PversionCode=1` → APK under `app/build/outputs/apk/release/`.
  - If Docker is available: `docker build -f server/Dockerfile .` → success.

- [ ] **Step 3: Run the full test suite.** Run: `./gradlew test :app:testDebugUnitTest`. Expected: PASS.

- [ ] **Step 4: Commit.** `git add -A && git commit -m "build: docker deployment, release signing and apk publishing"`

### Task 33: Live check against the test folder and the owner's phone checklist

**Files:** Create `docs/acceptance-checklist.md`; Test `server/src/test/kotlin/com/fieldmaxx/server/LiveDeliveryTest.kt`

- [ ] **Step 1: Write `LiveDeliveryTest`** (`@EnabledIfEnvironmentVariable FMX_LIVE_TEST_LINK`). It runs the server delivery worker with the real `SharePointLinkDestination` against the test folder, under `_fieldmaxx_it/<timestamp>/` as a fake job's Field Notes: a POLE 33 photo backup is taken over and delivered to `POLE/POLE_33/<name>`; a reuse photo is copied from it; both are verified by size.

- [ ] **Step 2: Run it.** Run: `FMX_LIVE_TEST_LINK='<owner test link>' ./gradlew :server:test --tests '*LiveDeliveryTest'`. Expected: PASS.

- [ ] **Step 3: Write `docs/acceptance-checklist.md`.** It's a numbered checklist for the owner on a real phone with a debug APK, each step stating its expected result:
  - install from the download page;
  - sign in;
  - import `A05J9XE.pdf` and see "Found 7 POLE points";
  - set the test folder link;
  - map keeps its view after closing and reopening;
  - photo with the live stamp; the compass changes when turning;
  - photo goes ✓ in the test folder within 30 s on LTE;
  - airplane mode → photos wait (⏳) → back online → ✓;
  - force-stop mid-upload → reopen → ✓ without duplicates;
  - a second phone sees the grey point and your dot, and the dot turns grey "app closed" when you leave the app;
  - reuse photo on a query point;
  - can't shoot;
  - add POLE 14B;
  - delete a point;
  - pole data export;
  - finish job;
  - in-app update.
  Then a pilot on one real EES job before the crew switches.

- [ ] **Step 4: Commit.** `git add -A && git commit -m "test: live delivery check and phone acceptance checklist"`
