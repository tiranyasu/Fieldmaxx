# Fieldmaxx Implementation Plan

> **SUPERSEDED (2026-11-13):** this plan is for the v1 web-app design. The owner switched to a native Android app (design v2). A new plan will replace this file; do not execute this one.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Fieldmaxx, an installable web app and server where a crew imports AT&T job files (PDF/DOCX), works the points on a satellite map, records pole data, and has stamped photos delivered reliably into each job's OneDrive "Field Notes" folder.

**Architecture:** npm-workspaces monorepo with three packages: `shared` (types and pure logic used by both sides), `server` (Fastify + SQLite + WebSocket; importer, sync, photo intake, OneDrive delivery worker), and `web` (React PWA with Leaflet, Dexie offline store, photo pipeline). The phone keeps every photo until the server reports it confirmed in OneDrive; the server keeps its copy until OneDrive confirms. OneDrive access goes through a `PhotoDestination` interface whose first implementation uses the job's "anyone with the link" share URL.

**Tech Stack:** Node.js 22, TypeScript (strict), Vitest, Fastify 5 (+ @fastify/websocket, @fastify/cookie, @fastify/static, @fastify/multipart), better-sqlite3, @node-rs/argon2, pdfjs-dist (legacy Node build) + @napi-rs/canvas, jszip + fast-xml-parser, exceljs, archiver, React 18 + Vite 5 + vite-plugin-pwa, react-leaflet 4 + leaflet 1.9 + leaflet.markercluster, Dexie 4 (+ dexie-react-hooks, fake-indexeddb for tests), suncalc, piexifjs, @testing-library/react, Playwright (preinstalled Chromium), pdf-lib (test fixtures only).

**Spec:** `docs/superpowers/specs/2026-11-13-fieldmaxx-design.md`. Read it before starting any task: section numbers below (§) refer to it.

## Global Constraints

- Node.js 22; TypeScript `strict: true` in every package; ES modules.
- Accepted job files: PDF and Word `.docx` only. Anything else → HTTP 415 with message `Only PDF and Word (.docx) files`.
- No point in a file is ever silently dropped: every coordinate either becomes a point or appears on the check screen as flagged/unread.
- Never overwrite and never delete anything in OneDrive (the share link has no delete permission, §5.5). Check for an existing same-name same-size file before every upload.
- Point folder: reuse an existing folder (any common naming style), otherwise create `<Category>/<TYPE>_<ID>` (e.g. `POLE/POLE_33`, `QUERY 1/Query_1-43`).
- Photo file name: `<TYPE>_<ID>_<lat 6dp>_<lon 6dp>_<counter>.jpg`, e.g. `POLE_1_39.636130_-119.691104_6018.jpg`.
- Stamp (bottom-left, white text, dark shadow, 5 lines): `A05J9XE · POLE 1` / `39.636130, -119.691104` / `±16 ft · alt 4430 ft` / `NE 47°` / `11/05/26 13:35:47`.
- Upload copy: full resolution capped at 16,000,000 pixels; JPEG quality 0.85 (Standard) or 0.95 (Maximum, admin setting); EXIF kept/written (GPS, altitude, image direction, DateTimeOriginal).
- Phone → server: 2–3 photos in parallel (use 3), resumable chunks. Server → OneDrive: 4 in parallel, starts on receipt.
- Phone copies deleted 3 days after confirmation. Server copies deleted 7 days after confirmation; above 80% disk, oldest confirmed first down to 75%; unconfirmed never purged.
- Sessions: cookie `fmx_session`, 90 days, sliding. Admin-created accounts only. Login lockout: 10 failures per username per 15 min → HTTP 429.
- Crew positions: send when moved > 5 m or every 15 s; dots faded after 10 min, hidden after 1 h. Everyone sees every job and every tech.
- Deletes of jobs and points are soft. Job: creator or admin may delete; admin restores within 30 days, purged after. Point: any user while none of its photos reached the server; otherwise admin only; admin restores.
- Map: Esri World Imagery `https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}`; street labels overlay `https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}` at opacity 0.9; map view remembered per user per job.
- Colours from Cadastrus: bars `rgba(15,23,42,0.92)` (`#0f172a`), accent `#2563eb`, done `#22c55e`, warning `#fbbf24`.
- English only. All frontend camera/GPS/compass/storage access goes through `web/src/platform/*` (Capacitor-swappable, §7.4).
- Real customer files are never committed: tests use synthetic replicas; the real samples go in gitignored `server/test/fixtures-private/`.

## Review Focus

1. **A tech shoots dozens of photos with no signal, the phone kills the app, they reopen it hours later**: every photo uploads exactly once, none lost. Test: Task 25 `queue survives reload and uploads each photo once`.
2. **Two techs change the same point at once** (one fills pole data, the other drags it): both changes survive. Test: Task 13 `concurrent field edits both persist`.
3. **Field Notes link wrong, expired, or sign-in-only**: the check fails with a readable reason, photos wait on the server, and the job shows a banner. Tests: Task 12 `link check reports failure reason`, Task 17 `forbidden destination sets job deliveryProblem`.
4. **48 MP camera photo on an iPhone** (canvas limit): scaled to ≤ 16 MP, never a blank image. Test: Task 25 `targetSize caps 48MP to 16MP keeping aspect`.
5. **The same file imported twice into a job**: no doubled points. Test: Task 12 `duplicate import skips identical points`.

---

## Milestone A: Foundation (`shared`)

### Task 1: Workspace and coordinate parsing

**Files:**
- Create: `package.json`, `tsconfig.base.json`, `vitest.config.ts`, `.gitignore`
- Create: `shared/package.json`, `shared/tsconfig.json`, `shared/src/index.ts`, `shared/src/types.ts`, `shared/src/geo.ts`
- Test: `shared/test/geo.test.ts`

**Interfaces:**
- Produces (`shared/src/geo.ts`):
```ts
export interface LatLon { lat: number; lon: number }
export interface CoordMatch { lat: number; lon: number; index: number; length: number; swapped: boolean; format: 'decimal' | 'dms' }
export function normalizeText(raw: string): string
export function findCoordinatePairs(text: string): CoordMatch[]
export function distanceMeters(a: LatLon, b: LatLon): number
export function metersToFeet(m: number): number
```
- Produces (`shared/src/types.ts`): every shared type used below. Define them now exactly as written:
```ts
export type Role = 'admin' | 'tech';
export interface User { id: string; username: string; displayName: string; role: Role; disabled: boolean }
export type PointStatus = 'open' | 'done' | 'cant_shoot';
export type CantShootReason = 'not_found' | 'no_access' | 'not_applicable' | 'other';
export const POLE_TAGS = ['N/A', 'Carrier label', 'Grounding', 'No ground', 'No fiber', 'Damaged cable'] as const;
export type PoleTag = typeof POLE_TAGS[number];
export interface PoleData { lineHeightFt: number | null; anchor: boolean; attachment: 'self_supporting' | 'lashed' | null; tags: PoleTag[] }
export type ImportFlag = 'swapped' | 'sign_mismatch' | 'far_from_job' | 'duplicate_label' | 'unlabelled' | 'no_coordinates';
export interface DraftPoint { layer: string; type: string; num: string; label: string; lat: number | null; lon: number | null; description: string; flags: ImportFlag[]; sourcePage: number | null; refImageIds: string[]; rawText: string }
export interface UnreadLine { text: string; sourcePage: number | null; reason: string }
export interface RefImageInfo { id: string; kind: 'page' | 'item'; page: number | null }
export interface ImportPreview { importId: string; fileName: string; kind: 'pdf' | 'docx'; layerSuggestion: string; notes: string; points: DraftPoint[]; unread: UnreadLine[]; refImages: RefImageInfo[] }
export interface Job { id: string; name: string; notes: string; fieldNotesUrl: string | null; status: 'active' | 'finished'; createdBy: string; createdAt: number; finishedAt: number | null; deletedAt: number | null; deliveryProblem: string | null }
export interface Point { id: string; jobId: string; layer: string; type: string; num: string; label: string; lat: number; lon: number; origLat: number | null; origLon: number | null; description: string; status: PointStatus; cantReason: CantShootReason | null; cantText: string | null; pole: PoleData | null; comment: string; folderPath: string | null; folderCandidates: string[] | null; refImageIds: string[]; flags: ImportFlag[]; addedBy: string | null; updatedAt: number; updatedBy: string | null; deletedAt: number | null }
export type PhotoStatus = 'uploading' | 'received' | 'confirmed' | 'stuck' | 'removed';
export interface PhotoInfo { id: string; jobId: string; pointId: string; userId: string; fileName: string | null; size: number; status: PhotoStatus; destPath: string | null; lastError: string | null; takenAt: number; receivedAt: number | null; confirmedAt: number | null; uploadMs: number | null; reusedFrom: string | null }
export interface PhotoUploadMeta { id: string; jobId: string; pointId: string; size: number; sha256: string; takenAt: number; lat: number | null; lon: number | null; accuracyM: number | null; headingDeg: number | null }
export interface CrewPosition { userId: string; displayName: string; lat: number; lon: number; accuracyM: number; at: number }
export type Op =
  | { kind: 'point.update'; opId: string; pointId: string; fields: Partial<{ pole: PoleData; comment: string }> }
  | { kind: 'point.move'; opId: string; pointId: string; lat: number; lon: number }
  | { kind: 'point.close'; opId: string; pointId: string; photoIds: string[] }
  | { kind: 'point.cantShoot'; opId: string; pointId: string; reason: CantShootReason; text: string }
  | { kind: 'point.reopen'; opId: string; pointId: string }
  | { kind: 'point.add'; opId: string; jobId: string; pointId: string; layer: string; type: string; num: string; lat: number; lon: number }
  | { kind: 'point.delete'; opId: string; pointId: string }
  | { kind: 'point.restore'; opId: string; pointId: string }
  | { kind: 'point.setFolder'; opId: string; pointId: string; folderPath: string }
  | { kind: 'photo.reuse'; opId: string; photoId: string; fromPhotoId: string; pointId: string }
  | { kind: 'photo.remove'; opId: string; photoId: string };
export interface OpResult { opId: string; ok: boolean; error?: string }
export interface Change { seq: number; jobId: string | null; entity: 'job' | 'point' | 'photo'; entityId: string; data: Job | Point | PhotoInfo; at: number }
export type ServerMessage = { type: 'change'; change: Change } | { type: 'position'; pos: CrewPosition } | { type: 'hello'; seq: number };
export type ClientMessage = { type: 'position'; lat: number; lon: number; accuracyM: number; altitudeM: number | null };
export interface JobSnapshot { job: Job; points: Point[]; photos: PhotoInfo[]; refImages: RefImageInfo[]; files: Array<{ id: string; name: string; layer: string }>; crew: Array<{ userId: string; displayName: string }>; seq: number }
export interface CreateJobInput { name: string; fieldNotesUrl: string | null; imports: JobImportInput[] }
export interface JobImportInput { importId: string; layer: string; notes: string; points: DraftPoint[] }
```

- [ ] **Step 1: Scaffold the workspace.** Root `package.json` with `"private": true`, `"type": "module"`, `"workspaces": ["shared", "server", "web"]`, scripts `test` (`vitest run`), `typecheck` (`tsc -b`). `vitest.config.ts` uses `test.projects: ['shared', 'server', 'web']`. `.gitignore`: `node_modules/`, `dist/`, `data/`, `server/test/fixtures-private/`, `*.log`, `test-results/`, `playwright-report/`. `shared/package.json` name `@fieldmaxx/shared`, main `src/index.ts` (consumed as source by server and web).

- [ ] **Step 2: Write the failing tests** in `shared/test/geo.test.ts`:
```ts
test('decimal pair, no space', () => { const [m] = findCoordinatePairs('39.63609452036168,-119.69104360818118'); expect([m.lat, m.lon]).toEqual([39.63609452036168, -119.69104360818118]); expect(m.format).toBe('decimal') });
test('decimal pair, space after comma', () => expect(findCoordinatePairs('39.6361531972, -119.675338878')[0].lon).toBe(-119.675338878));
test('number split after minus across a line break', () => expect(findCoordinatePairs(normalizeText('39.63612682461489,-\r119.68173217216633'))[0].lon).toBe(-119.68173217216633));
test('label and coords separated by tab + CR', () => expect(normalizeText('POLE 1 = \t\r39.6,-119.6')).toBe('POLE 1 =\n39.6,-119.6'));
test('DMS pair', () => { const [m] = findCoordinatePairs(`39°38'09.9"N 119°41'27.8"W`); expect(m.lat).toBeCloseTo(39.636083, 5); expect(m.lon).toBeCloseTo(-119.691056, 5); expect(m.format).toBe('dms') });
test('swapped pair is corrected and flagged', () => { const [m] = findCoordinatePairs('-119.69104,39.63609'); expect([m.lat, m.lon, m.swapped]).toEqual([39.63609, -119.69104, true]) });
test('rejects too few decimals and out of range', () => { expect(findCoordinatePairs('1.5, 2.5')).toEqual([]); expect(findCoordinatePairs('123.4567, 200.1234')).toEqual([]) });
test('en dash separator and multiple pairs in order', () => { const ms = findCoordinatePairs('VERIFY HH – 39.35535679, -119.81288376 and 39.1234,-119.5678'); expect(ms.map(m => m.lat)).toEqual([39.35535679, 39.1234]); expect(ms[0].index).toBe('VERIFY HH – '.length) });
test('distance and feet', () => { expect(distanceMeters({ lat: 39.636, lon: -119.691 }, { lat: 39.636, lon: -119.690 })).toBeCloseTo(85.7, 0); expect(metersToFeet(4.877)).toBeCloseTo(16, 0) });
```

- [ ] **Step 3: Run to verify failure.** Run: `npx vitest run shared/test/geo.test.ts`. Expected: FAIL, `findCoordinatePairs` is not exported.

- [ ] **Step 4: Implement `shared/src/geo.ts` and `shared/src/types.ts`.** `normalizeText`: `\r\n`/`\r` → `\n`; `\t` → space; collapse runs of spaces; trim each line; join a `-` or `,` at end of line with a following line that starts with a digit (`-\n119` → `-119`, `,\n-119` → `,-119`). Decimal pairs: two signed numbers with 1–3 integer digits and ≥ 3 decimals, separated by a comma (optional spaces) or whitespace; both in range, or swapped (`|first| > 90` and `|second| ≤ 90`), else rejected. DMS: `D°M'S"[NS]` then `D°M'S"[EW]` (also `′ ″`). `distanceMeters`: haversine, R = 6371008.8.

- [ ] **Step 5: Run to verify pass.** Run: `npx vitest run shared/test/geo.test.ts`. Expected: PASS (9 tests).

- [ ] **Step 6: Commit.** `git add -A && git commit -m "feat(shared): workspace, shared types, coordinate parsing"`

### Task 2: Naming rules (labels, folders, file names)

**Files:**
- Create: `shared/src/naming.ts`
- Test: `shared/test/naming.test.ts`

**Interfaces:**
- Produces:
```ts
export const KNOWN_TYPES = ['POLE', 'PED', 'HH', 'MH', 'BU', 'SAI', 'PFP', 'MDU'] as const;
export const ADD_CATEGORIES = ['POLE', 'PED', 'HH', 'MH', 'BU', 'SAI', 'PFP', 'MDU', 'CULVERT', 'Drainage', 'CREEK IMAGE', 'MAIL BOX IMAGES', 'Street images', 'Rear images', 'VACANT LOT', 'UTILITIES', 'Aerial Fielding', 'F3 BUILDING DATA'];
export interface PointKey { layer: string; type: string; num: string; label: string }
export function isQueryLayer(layer: string): boolean;               // /^query\s*\d+$/i
export function pointLabel(layer: string, type: string, num: string): string;
export function defaultFolderName(label: string): string;           // whitespace runs → '_'
export function normalizeFolderName(name: string): string;          // lowercase, remove whitespace and '_'
export function categoryMatches(folderName: string, layer: string): boolean;
export function pointFolderMatches(folderName: string, p: PointKey, where: 'category' | 'top'): boolean;
export function photoFileName(folderName: string, lat: number, lon: number, counter: number): string;
export function markerCode(type: string): string;
export function suggestAddedPoleNum(existingNums: string[], nearestNum: string): string;
export function nextNum(existingNums: string[]): string;
```

- [ ] **Step 1: Write the failing tests** in `shared/test/naming.test.ts`:
```ts
const pole1 = { layer: 'POLE', type: 'POLE', num: '1', label: 'POLE 1' };
const pole14b = { layer: 'POLE', type: 'POLE', num: '14B', label: 'POLE 14B' };
const q43 = { layer: 'QUERY 1', type: 'QUERY 1', num: '43', label: 'Query 1-43' };
test('labels', () => { expect(pointLabel('POLE', 'POLE', '14B')).toBe('POLE 14B'); expect(pointLabel('QUERY 1', 'QUERY 1', '25A')).toBe('Query 1-25A') });
test('default folder names', () => { expect(defaultFolderName('POLE 14B')).toBe('POLE_14B'); expect(defaultFolderName('Query 1-43')).toBe('Query_1-43') });
test('category match is case/space/underscore insensitive, plural allowed', () => { expect(categoryMatches('QUERY 1', 'Query 1')).toBe(true); expect(categoryMatches('Query_1', 'QUERY 1')).toBe(true); expect(categoryMatches('POLES', 'POLE')).toBe(true); expect(categoryMatches('Query 3', 'QUERY 1')).toBe(false) });
test('point folder in category: bare number, prefixed, mixed case', () => { for (const n of ['1', 'POLE_1', 'Pole 1', 'pole1', 'P1']) expect(pointFolderMatches(n, pole1, 'category')).toBe(true); expect(pointFolderMatches('POLE_14b', pole14b, 'category')).toBe(true); expect(pointFolderMatches('10', pole1, 'category')).toBe(false) });
test('top level: bare number only for POLE', () => { expect(pointFolderMatches('1', pole1, 'top')).toBe(true); expect(pointFolderMatches('43', q43, 'top')).toBe(false); expect(pointFolderMatches('Query 1-43', q43, 'top')).toBe(true); expect(pointFolderMatches('QUERY_1-43', q43, 'category')).toBe(true); expect(pointFolderMatches('43', q43, 'category')).toBe(true) });
test('photo file name matches Cadastrus', () => expect(photoFileName('POLE_1', 39.63613, -119.691104, 6018)).toBe('POLE_1_39.636130_-119.691104_6018.jpg'));
test('marker codes', () => { expect(markerCode('POLE')).toBe('P'); expect(markerCode('MH')).toBe('MH'); expect(markerCode('QUERY 1')).toBe('Q'); expect(markerCode('Street images')).toBe('ST') });
test('added pole suggestions', () => { expect(suggestAddedPoleNum(['14'], '14')).toBe('14B'); expect(suggestAddedPoleNum(['14', '14B'], '14')).toBe('14C'); expect(suggestAddedPoleNum(['14', '14b'], '14')).toBe('14C') });
test('next number', () => { expect(nextNum(['1', '2', '10'])).toBe('11'); expect(nextNum([])).toBe('1') });
```

- [ ] **Step 2: Run to verify failure.** Run: `npx vitest run shared/test/naming.test.ts`. Expected: FAIL (module not found).

- [ ] **Step 3: Implement `shared/src/naming.ts`.** `pointFolderMatches` normalizes the folder name (`normalizeFolderName`, keeps `-`) and compares against a candidate set: for non-query points `{num, type+num, firstLetterOfType+num}` (lowercased, e.g. `1`, `pole1`, `p1`); for query points `{num, 'query'+n+'-'+num, 'q'+n+'-'+num}`. At `where: 'top'` the bare `num` candidate is allowed only when `type === 'POLE'` (§6.6: the A05J9XE job has pole folders `1`…`7` at the top level). `markerCode`: KNOWN_TYPES map to `P` for POLE and themselves otherwise; query layers → `Q`; anything else → first two letters uppercased.

- [ ] **Step 4: Run to verify pass.** Run: `npx vitest run shared/test/naming.test.ts`. Expected: PASS.

- [ ] **Step 5: Commit.** `git commit -am "feat(shared): point, folder and photo naming rules"`

### Task 3: Photo stamp text and layout

**Files:**
- Create: `shared/src/stamp.ts`
- Test: `shared/test/stamp.test.ts`

**Interfaces:**
- Produces:
```ts
export interface StampMeta { jobName: string; pointLabel: string; lat: number | null; lon: number | null; accuracyM: number | null; altitudeM: number | null; headingDeg: number | null; takenAt: Date; gpsStale: boolean; fromGallery: boolean }
export function compass8(deg: number): 'N' | 'NE' | 'E' | 'SE' | 'S' | 'SW' | 'W' | 'NW';
export function formatStampTime(d: Date): string;   // local time, MM/DD/YY HH:MM:SS
export function stampLines(m: StampMeta): string[];
export function stampLayout(width: number, height: number, lineCount: number): { fontPx: number; x: number; firstBaselineY: number; lineHeightPx: number; shadowBlurPx: number };
```

- [ ] **Step 1: Write the failing tests:**
```ts
const base = { jobName: 'A05J9XE', pointLabel: 'POLE 1', lat: 39.63613, lon: -119.691104, accuracyM: 4.877, altitudeM: 1350.26, headingDeg: 47, takenAt: new Date(2026, 10, 5, 13, 35, 47), gpsStale: false, fromGallery: false };
test('full stamp', () => expect(stampLines(base)).toEqual(['A05J9XE · POLE 1', '39.636130, -119.691104', '±16 ft · alt 4430 ft', 'NE 47°', '11/05/26 13:35:47']));
test('missing values', () => expect(stampLines({ ...base, lat: null, lon: null, accuracyM: null, altitudeM: null, headingDeg: null })).toEqual(['A05J9XE · POLE 1', 'No GPS', '', 'Compass n/a', '11/05/26 13:35:47'].filter(l => l !== '')));
test('no altitude', () => expect(stampLines({ ...base, altitudeM: null })[2]).toBe('±16 ft'));
test('stale GPS and gallery markers', () => { const l = stampLines({ ...base, gpsStale: true, fromGallery: true }); expect(l[1]).toBe('39.636130, -119.691104 (GPS stale)'); expect(l[4]).toBe('11/05/26 13:35:47 (from gallery)') });
test('compass boundaries', () => { expect(compass8(0)).toBe('N'); expect(compass8(22.4)).toBe('N'); expect(compass8(22.5)).toBe('NE'); expect(compass8(359)).toBe('N'); expect(compass8(200)).toBe('S') });
test('layout for 3000x4000, 5 lines', () => expect(stampLayout(3000, 4000, 5)).toEqual({ fontPx: 84, x: 84, firstBaselineY: 3496, lineHeightPx: 105, shadowBlurPx: 21 }));
```

- [ ] **Step 2: Run to verify failure.** Run: `npx vitest run shared/test/stamp.test.ts`. Expected: FAIL.

- [ ] **Step 3: Implement `shared/src/stamp.ts`.** Layout: `fontPx = round(min(w,h) * 0.028)`, `x = fontPx`, `lineHeightPx = round(fontPx * 1.25)`, `firstBaselineY = h - fontPx - lineHeightPx * (lineCount - 1)`, `shadowBlurPx = round(fontPx * 0.25)`. Feet rounded to whole numbers. When there is no GPS, line 2 is `No GPS` and the accuracy line is omitted.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git commit -am "feat(shared): photo stamp lines and layout"`

---

## Milestone B: Importer (`server/src/import`)

### Task 4: Server package and labelled-point parser

**Files:**
- Create: `server/package.json`, `server/tsconfig.json`
- Create: `server/src/import/labelled.ts`, `server/src/import/sanity.ts`
- Test: `server/test/import/labelled.test.ts`

**Interfaces:**
- Consumes: `normalizeText`, `findCoordinatePairs`, `CoordMatch` (Task 1); `pointLabel`, `KNOWN_TYPES` (Task 2); `DraftPoint` (Task 1).
- Produces:
```ts
export interface TextGroup { source: 'annotation' | 'field' | 'pageText' | 'docx'; page: number | null; text: string }
export interface LabelledResult { points: DraftPoint[]; unlabelled: CoordMatch[]; leftoverText: string }
export function extractLabelledPoints(group: TextGroup): LabelledResult;
export function addSanityFlags(points: DraftPoint[]): void;   // mutates flags
```

- [ ] **Step 1: Create `server/package.json`** (name `@fieldmaxx/server`, `type: module`, deps from the Tech Stack for the server, dev deps `vitest`, `tsx`, `pdf-lib`, `@types/*`; scripts `dev` `tsx watch src/index.ts`, `build` `tsc -p .`, `start` `node dist/index.js`). Run `npm install` at the root.

- [ ] **Step 2: Write the failing tests.** The A05J9XE replica uses the spec's exact text shape (§5.1) with these synthetic coordinates: `const AERIAL = 'POLE 1 = \t\r39.10000000000001,-119.10000000000001\rPOLE 2 = \t\r39.20000000000002,-119.20000000000002\rPOLE 3 = 39.30000000000003,-119.30000000000003\rPOLE 4 = 39.40000000000004,-119.40000000000004\rPOLE 5 = \t\r39.5000000005, -119.500000005\rPOLE 6 = 39.6000000006, -119.600000006\rPOLE 7 = 39.70000000000007,-119.70000000000007'` (put it in `server/test/fixtures/replicas.ts` for reuse).
```ts
test('aerial replica → 7 POLE points, nothing left over', () => { const r = extractLabelledPoints({ source: 'annotation', page: 1, text: AERIAL }); expect(r.points.map(p => p.label)).toEqual(['POLE 1','POLE 2','POLE 3','POLE 4','POLE 5','POLE 6','POLE 7']); expect(r.points[4]).toMatchObject({ layer: 'POLE', type: 'POLE', num: '5', lat: 39.5000000005, lon: -119.500000005, sourcePage: 1 }); expect(r.unlabelled).toEqual([]); expect(r.leftoverText.trim()).toBe('') });
test('suffix letter is uppercased', () => expect(extractLabelledPoints({ source: 'docx', page: null, text: 'POLE 14b = 39.1234,-119.1234' }).points[0].label).toBe('POLE 14B'));
test('mixed-case known type, colon, DMS', () => expect(extractLabelledPoints({ source: 'pageText', page: 2, text: `Pole 3: 39°38'09.9"N 119°41'27.8"W` }).points[0]).toMatchObject({ label: 'POLE 3', sourcePage: 2 }));
test('trailing text is the description', () => expect(extractLabelledPoints({ source: 'pageText', page: 1, text: 'MH 2 = 39.1234,-119.1234 existing 40ft\nMH 3 = 39.2234,-119.2234' }).points[0].description).toBe('existing 40ft'));
test('coordinates without a label are returned as unlabelled', () => expect(extractLabelledPoints({ source: 'pageText', page: 1, text: 'see 39.1234,-119.1234' }).unlabelled).toHaveLength(1));
test('sanity flags', () => { const pts = [p('POLE 1', 39.1, -119.1), p('POLE 2', 39.11, 119.11), p('POLE 3', 39.12, -119.12), p('POLE 4', 41.5, -119.1), p('POLE 1', 39.2, -119.2)]; addSanityFlags(pts); expect(pts[1].flags).toContain('sign_mismatch'); expect(pts[3].flags).toContain('far_from_job'); expect(pts[0].flags).toContain('duplicate_label'); expect(pts[4].flags).toContain('duplicate_label'); expect(pts[2].flags).toEqual([]) });
```
(`p(label, lat, lon)` is a local helper building a `DraftPoint` with empty flags.)

- [ ] **Step 3: Run to verify failure.** Run: `npx vitest run server/test/import/labelled.test.ts`. Expected: FAIL.

- [ ] **Step 4: Implement.** Normalize the text, find coordinate pairs, and for each pair test the slice between the previous pair's end and this pair's start against the label pattern anchored at the slice end: `(?:^|[^A-Za-z])(TYPE)\s*#?\s*(\d{1,4}[A-Za-z]?)\s*[=:\-–]?\s*$`, where TYPE is a KNOWN_TYPES word (case-insensitive) or else any `[A-Z]{2,8}` (case-sensitive). Text after a point's coordinates, up to the next label, is its description (trimmed). Swapped pairs get the `swapped` flag. `addSanityFlags`: `sign_mismatch` when the longitude sign differs from the majority; `far_from_job` when more than 50 km from the median lat/lon; `duplicate_label` on every point sharing a label with different coordinates (> 1 m apart).

- [ ] **Step 5: Run to verify pass.** Expected: PASS.

- [ ] **Step 6: Commit.** `git add -A && git commit -m "feat(import): labelled point parser with sanity flags"`

### Task 5: DOCX to ordered blocks, with Word list numbering

**Files:**
- Create: `server/src/import/blocks.ts`, `server/src/import/docx.ts`
- Create: `server/test/helpers/make-docx.ts`
- Test: `server/test/import/docx.test.ts`

**Interfaces:**
- Produces:
```ts
// blocks.ts
export type DocBlock = { kind: 'para'; text: string; listLabel: string | null; page: number | null } | { kind: 'image'; imageId: string; page: number | null };
export interface ExtractedImage { id: string; mime: 'image/png' | 'image/jpeg'; data: Buffer }
export class ImportError extends Error { constructor(public code: 'unreadable_pdf' | 'unreadable_docx' | 'unsupported_type', message: string) { super(message) } }
// docx.ts
export async function docxToBlocks(buf: Buffer): Promise<{ blocks: DocBlock[]; images: ExtractedImage[] }>;
export function renderListLabel(lvlText: string, levels: Array<{ value: number; fmt: string }>): string;
// test helper
export interface DocxSpec { lists: Array<{ numId: number; start: number; fmt: 'decimal' | 'upperLetter'; lvlText: string }>; body: Array<{ text: string; numId?: number; ilvl?: number; styleNumId?: number } | { image: Buffer } | { table: string[][] }> }
export async function makeDocx(spec: DocxSpec): Promise<Buffer>;
```

- [ ] **Step 1: Write `makeDocx`** with jszip: `[Content_Types].xml`, `_rels/.rels`, `word/document.xml` (paragraphs with `w:numPr`; image paragraphs with `a:blip r:embed`; tables `w:tbl/w:tr/w:tc/w:p`), `word/numbering.xml` (one `w:abstractNum` per list with `w:start`, `w:numFmt`, `w:lvlText`, plus `w:num` pointing at it), `word/styles.xml` (style `NumberedStyle` carrying `w:numPr` for `styleNumId`), `word/_rels/document.xml.rels` and `word/media/imageN.png`.

- [ ] **Step 2: Write the failing tests:**
```ts
test('decimal list numbers', async () => { const { blocks } = await docxToBlocks(await makeDocx({ lists: [{ numId: 1, start: 1, fmt: 'decimal', lvlText: '%1.' }], body: [{ text: 'a', numId: 1 }, { text: 'b', numId: 1 }, { text: 'c', numId: 1 }] })); expect(paras(blocks).map(b => b.listLabel)).toEqual(['1.', '2.', '3.']) });
test('list starting at 14', async () => expect(labels(await doc([{ numId: 4, start: 14, fmt: 'decimal', lvlText: '%1.' }], ['x', 'y'].map(t => ({ text: t, numId: 4 }))))).toEqual(['14.', '15.']));
test('upper-letter list', async () => expect(labels(await doc([{ numId: 3, start: 1, fmt: 'upperLetter', lvlText: '%1-' }], [{ text: '39.1,-119.1', numId: 3 }, { text: '39.2,-119.2', numId: 3 }]))).toEqual(['A-', 'B-']));
test('typed number stays in text, no list label', async () => { const b = paras(await blocksOf({ lists: [], body: [{ text: '12. NO STREET VIEW' }] })); expect(b[0]).toMatchObject({ text: '12. NO STREET VIEW', listLabel: null }) });
test('images in order with bytes', async () => { const png = tinyPng(); const { blocks, images } = await docxToBlocks(await makeDocx({ lists: [], body: [{ text: 'one' }, { image: png }, { text: 'two' }] })); expect(blocks.map(b => b.kind)).toEqual(['para', 'image', 'para']); expect(images[0].data.equals(png)).toBe(true); expect(images[0].mime).toBe('image/png') });
test('numbering inherited from paragraph style', async () => expect(labels(await doc([{ numId: 2, start: 1, fmt: 'decimal', lvlText: '%1.' }], [{ text: 'a', styleNumId: 2 }]))).toEqual(['1.']));
test('table cell paragraphs are emitted in order', async () => expect(paras(await blocksOf({ lists: [], body: [{ text: 'before' }, { table: [['c1', 'c2']] }, { text: 'after' }] })).map(b => b.text)).toEqual(['before', 'c1', 'c2', 'after']));
test('garbage buffer', async () => await expect(docxToBlocks(Buffer.from('nope'))).rejects.toMatchObject({ code: 'unreadable_docx' }));
```
(`paras`, `labels`, `doc`, `blocksOf`, `tinyPng` are small local helpers in the test file.)

- [ ] **Step 3: Run to verify failure.** Run: `npx vitest run server/test/import/docx.test.ts`. Expected: FAIL.

- [ ] **Step 4: Implement `docx.ts`.** Parse with jszip + fast-xml-parser (`preserveOrder: true, ignoreAttributes: false`). Walk `w:body` in order (paragraphs, and paragraphs inside `w:tbl` cells). Numbering, which is not determined by the tests alone:
```
numPr = paragraph pPr numPr ?? numPr of the paragraph's style (styles.xml)
if numPr missing or numId == '0' → listLabel = null
lvl = abstractNum(num(numId)).levels[ilvl]; start = num.lvlOverride[ilvl].startOverride ?? lvl.start ?? 1
counters[numId] ??= []
counters[numId][ilvl] = counters[numId][ilvl] === undefined ? start : counters[numId][ilvl] + 1
counters[numId].length = ilvl + 1                 // reset deeper levels
listLabel = renderListLabel(lvl.lvlText, levels 0..ilvl with their counters and numFmt)
```
`renderListLabel` replaces `%k` with level k-1's value in its format (`decimal`, `upperLetter` A…Z then AA…, `lowerLetter`, `upperRoman`, `lowerRoman`). Image ids are `img-<n>` in order of appearance.

- [ ] **Step 5: Run to verify pass.** Expected: PASS.

- [ ] **Step 6: Commit.** `git add -A && git commit -m "feat(import): DOCX blocks with Word list numbering and images"`

### Task 6: Query-item parser

**Files:**
- Create: `server/src/import/query.ts`
- Modify: `server/test/fixtures/replicas.ts` (add `QUERY_BLOCKS` and `QUERY_DOCX_SPEC`)
- Test: `server/test/import/query.test.ts`

**Interfaces:**
- Consumes: `DocBlock` (Task 5), `findCoordinatePairs` (Task 1), `pointLabel` (Task 2).
- Produces:
```ts
export function layerFromFileName(fileName: string): string;   // 'QUERY_1_-_A05K51V.docx' → 'QUERY 1'; 'A05J9XE.pdf' → 'A05J9XE'
export interface QueryResult { points: DraftPoint[]; notes: string[]; unattachedImageIds: string[] }
export function extractQueryItems(blocks: DocBlock[], layer: string): QueryResult;
```

- [ ] **Step 1: Add the replica of the sample query file** to `replicas.ts`, matching §5.2 exactly in structure, with synthetic coordinates (`39.3500xxxx, -119.8100xxxx` style; values of your choice, ≥ 7 decimals) and the same text: list A (`numId 1, start 1, '%1.'`) holding 10 `VERIFY …` items and `NO STREET VIEW PROVIDE ALL UTILITY DETAILS – <coords>`, each followed by one image; typed `12. NO STREET VIEW PROVIDE ALL UTILITY DETAILS  - <coords>` and `13.. NO STREET VIEW … – <coords>` (no list), each with an image; list B (`numId 4, start 14, '%1.'`) holding the 12 instruction items from §5.2 in order, each with its coordinates in the **next** unnumbered paragraph and images as in the sample (items 14, 18, 19, 20, 21, 24 one image; item 23 two images; items 15, 16, 17, 22 none); item 25 `POLE DATA` followed by two coordinate-only paragraphs in list C (`numId 3, '%1-'` upper letters) and one image. Export both `QUERY_DOCX_SPEC` (for `makeDocx`) and `QUERY_BLOCKS` (the `DocBlock[]` that `docxToBlocks` should produce).

- [ ] **Step 2: Write the failing tests:**
```ts
test('file name → layer', () => { expect(layerFromFileName('QUERY_1_-_A05K51V.docx')).toBe('QUERY 1'); expect(layerFromFileName('query 2.pdf')).toBe('QUERY 2'); expect(layerFromFileName('A05J9XE.pdf')).toBe('A05J9XE') });
test('replica → 26 points with Cadastrus-style labels', () => { const r = extractQueryItems(QUERY_BLOCKS, 'QUERY 1'); expect(r.points.map(p => p.num)).toEqual([...Array.from({ length: 24 }, (_, i) => String(i + 1)), '25A', '25B']); expect(r.points[0].label).toBe('Query 1-1'); expect(r.points[25].label).toBe('Query 1-25B') });
test('description strips coordinates and dash', () => { const r = extractQueryItems(QUERY_BLOCKS, 'QUERY 1'); expect(r.points[0].description).toBe('VERIFY HH'); expect(r.points[11].description).toBe('NO STREET VIEW PROVIDE ALL UTILITY DETAILS'); expect(r.points[13].description).toBe('PROVIDE WIDE ANGLE IMAGES FROM POLE 5 TO POLE 4') });
test('coordinates on the next paragraph belong to the item', () => expect(extractQueryItems(QUERY_BLOCKS, 'QUERY 1').points[13].lat).not.toBeNull());
test('images attach to the preceding item', () => { const pts = extractQueryItems(QUERY_BLOCKS, 'QUERY 1').points; expect(pts[0].refImageIds).toHaveLength(1); expect(pts[22].refImageIds).toHaveLength(2); expect(pts[15].refImageIds).toHaveLength(0); expect(pts[24].refImageIds).toEqual(pts[25].refImageIds) });
test('item without coordinates is flagged, not dropped', () => { const r = extractQueryItems([{ kind: 'para', text: 'CHECK SOMETHING', listLabel: '1.', page: null }], 'QUERY 1'); expect(r.points[0]).toMatchObject({ lat: null, lon: null, flags: ['no_coordinates'] }) });
test('unnumbered text+coords continues numbering, flagged', () => { const r = extractQueryItems([{ kind: 'para', text: 'A – 39.1234,-119.1234', listLabel: '1.', page: null }, { kind: 'para', text: 'B – 39.2234,-119.2234', listLabel: null, page: null }], 'QUERY 1'); expect(r.points[1]).toMatchObject({ num: '2', flags: ['unlabelled'] }) });
test('plain text after an item without coords extends its description', () => expect(extractQueryItems([{ kind: 'para', text: 'POLE DATA', listLabel: '1.', page: null }, { kind: 'para', text: 'near the creek', listLabel: null, page: null }, { kind: 'para', text: '39.1234,-119.1234', listLabel: null, page: null }], 'QUERY 1').points[0]).toMatchObject({ description: 'POLE DATA near the creek', lat: 39.1234 }));
```

- [ ] **Step 3: Run to verify failure.** Run: `npx vitest run server/test/import/query.test.ts`. Expected: FAIL.

- [ ] **Step 4: Implement `query.ts`.** The algorithm, which the tests don't fully pin down:
```
typed = /^\s*(\d{1,4})\s*\.+\s*(.*)$/s
for block in blocks:
  image → current item ? current.images.push : unattached.push
  para  → number = listLabel digits (if numeric) ?? typed number ?? null; letter = listLabel letters (if alphabetic) ?? null
          body = text without typed number; coords = findCoordinatePairs(body); words = body minus coords minus [–-:,] has letters?
          if words && (number || coords.length) → new item(num = number ?? prev+1 with flag 'unlabelled' when number null, text = words, coords)
          elif !words && coords.length && current → current.coords.push(...coords with letter)
          elif words && current && current.coords.length == 0 → current.text += ' ' + words
          elif words → notes.push(text)
points: 0 coords → one point lat/lon null flag no_coordinates; 1 → num; n>1 → num + (letter ?? 'A','B',…)
```
All points of one item share its images.

- [ ] **Step 5: Run to verify pass.** Expected: PASS.

- [ ] **Step 6: Commit.** `git add -A && git commit -m "feat(import): numbered query item parser"`

### Task 7: PDF to groups (annotations, fields, page text, images, page renders)

**Files:**
- Create: `server/src/import/pdf.ts`
- Create: `server/test/helpers/make-pdf.ts`
- Test: `server/test/import/pdf.test.ts`

**Interfaces:**
- Consumes: `DocBlock`, `ExtractedImage`, `ImportError` (Task 5).
- Produces:
```ts
export interface PdfGroup { source: 'annotation' | 'field' | 'pageText'; page: number; blocks: DocBlock[] }
export async function pdfToGroups(buf: Buffer, opts?: { renderScale?: number }): Promise<{ groups: PdfGroup[]; images: ExtractedImage[]; pageRenders: ExtractedImage[] }>;   // pageRenders ids 'page-<n>', PNG
// test helper (pdf-lib)
export interface PdfSpec { pages: Array<{ lines?: Array<{ text: string; x: number; y: number }>; images?: Array<{ png: Buffer; x: number; y: number; w: number; h: number }>; freeText?: string[] }> }
export async function makePdf(spec: PdfSpec): Promise<Buffer>;
```

- [ ] **Step 1: Write `makePdf`** with pdf-lib: text via `drawText`, images via `embedPng` + `drawImage`, FreeText annotations as raw dicts (`/Type /Annot /Subtype /FreeText /Rect … /Contents (…)`, plus a `/DA` string) appended to the page's `/Annots`.

- [ ] **Step 2: Write the failing tests:**
```ts
test('FreeText annotation becomes its own group, one block per line', async () => { const { groups } = await pdfToGroups(await makePdf({ pages: [{ freeText: [AERIAL] }] })); const g = groups.find(g => g.source === 'annotation')!; expect(g.page).toBe(1); expect(g.blocks.filter(b => b.kind === 'para').length).toBeGreaterThanOrEqual(7) });
test('page text lines top-to-bottom', async () => { const { groups } = await pdfToGroups(await makePdf({ pages: [{ lines: [{ text: 'second', x: 50, y: 600 }, { text: 'first', x: 50, y: 700 }] }] })); expect(paraTexts(groups.find(g => g.source === 'pageText')!)).toEqual(['first', 'second']) });
test('image placed between the lines it sits between', async () => { const { groups, images } = await pdfToGroups(await makePdf({ pages: [{ lines: [{ text: 'item one', x: 50, y: 700 }, { text: 'item two', x: 50, y: 400 }], images: [{ png: tinyPng(), x: 50, y: 500, w: 100, h: 100 }] }] })); expect(groups.find(g => g.source === 'pageText')!.blocks.map(b => b.kind)).toEqual(['para', 'image', 'para']); expect(images).toHaveLength(1) });
test('one PNG render per page', async () => { const { pageRenders } = await pdfToGroups(await makePdf({ pages: [{}, {}] })); expect(pageRenders.map(r => r.id)).toEqual(['page-1', 'page-2']); expect(pageRenders[0].data.subarray(1, 4).toString()).toBe('PNG') });
test('garbage buffer', async () => await expect(pdfToGroups(Buffer.from('nope'))).rejects.toMatchObject({ code: 'unreadable_pdf' }));
```

- [ ] **Step 3: Run to verify failure.** Run: `npx vitest run server/test/import/pdf.test.ts`. Expected: FAIL.

- [ ] **Step 4: Implement `pdf.ts`** with `pdfjs-dist/legacy/build/pdf.mjs`, rendering through `@napi-rs/canvas`. Annotation text: `contentsObj?.str ?? contents`, split on `\r|\n` into para blocks. Widget annotations with a `fieldValue` become `field` groups. Page text: group `getTextContent()` items into lines when their baselines are within 2 pt, sort lines by descending y, then items by x. Images: walk `getOperatorList()` tracking the CTM through `save`/`restore`/`transform`; at `paintImageXObject` take the image's top y; fetch pixels with `page.objs.get(name)` and encode PNG via `@napi-rs/canvas`. Interleave images into the line order by y. Render scale defaults to 2.

- [ ] **Step 5: Run to verify pass.** Expected: PASS.

- [ ] **Step 6: Commit.** `git add -A && git commit -m "feat(import): PDF groups, images and page renders"`

### Task 8: `importFile`: orchestrate a file into an import preview

**Files:**
- Create: `server/src/import/index.ts`
- Test: `server/test/import/importFile.test.ts`, `server/test/import/real-fixtures.test.ts`

**Interfaces:**
- Consumes: Tasks 4–7.
- Produces:
```ts
export interface ImportResult { fileName: string; kind: 'pdf' | 'docx'; layerSuggestion: string; notes: string; points: DraftPoint[]; unread: UnreadLine[]; images: ExtractedImage[]; refImages: RefImageInfo[] }
export async function importFile(fileName: string, buf: Buffer): Promise<ImportResult>;
```

- [ ] **Step 1: Write the failing tests:**
```ts
const aerialPdf = () => makePdf({ pages: [{ freeText: ['     NOTE FOR AERIAL PATH\r1. IDENTIFY THE FEASIBILITY OF ATTACHING FIBER ON POLES THROUGHOUT THE PROPOSED CABLE ROUTE ON POLE.\r2. PLEASE MARK THE POLES IN BETWEEN THE CABLE PATH IN CASE IF WE HAVE MISSED ANY OF THEM.', AERIAL], images: [{ png: tinyPng(), x: 0, y: 200, w: 595, h: 186 }] }] });
test('aerial PDF → 7 poles + notes, nothing unread', async () => { const r = await importFile('A05J9XE.pdf', await aerialPdf()); expect(r.points.map(p => p.label)).toEqual(['POLE 1','POLE 2','POLE 3','POLE 4','POLE 5','POLE 6','POLE 7']); expect(r.notes).toContain('NOTE FOR AERIAL PATH'); expect(r.unread).toEqual([]); expect(r.points[0].refImageIds).toContain('page-1'); expect(r.layerSuggestion).toBe('POLE') });
test('query DOCX → 26 points, layer from file name', async () => { const r = await importFile('QUERY_1_-_A05K51V.docx', await makeDocx(QUERY_DOCX_SPEC)); expect(r.points).toHaveLength(26); expect(r.layerSuggestion).toBe('QUERY 1'); expect(r.points[22].refImageIds).toHaveLength(2) });
test.skipIf(!hasSoffice())('query DOCX converted to PDF gives the same labels', async () => { const pdf = await sofficeToPdf(await makeDocx(QUERY_DOCX_SPEC)); const r = await importFile('QUERY_1_-_A05K51V.pdf', pdf); expect(r.points.map(p => p.label)).toEqual((await importFile('QUERY_1_-_A05K51V.docx', await makeDocx(QUERY_DOCX_SPEC))).points.map(p => p.label)) });
test('unparseable point line is reported', async () => { const r = await importFile('x.pdf', await makePdf({ pages: [{ freeText: ['POLE 8 = 39.6361,-119.69\rPOLE 9 = 39.63,abc'] }] })); expect(r.points).toHaveLength(1); expect(r.unread).toEqual([expect.objectContaining({ text: 'POLE 9 = 39.63,abc', sourcePage: 1 })]) });
test('stray coordinates become flagged unlabelled points', async () => expect((await importFile('x.pdf', await makePdf({ pages: [{ lines: [{ text: 'see 39.1234,-119.1234', x: 50, y: 700 }] }] }))).points[0].flags).toContain('unlabelled'));
test('same point in annotation and page text is not doubled', async () => expect((await importFile('x.pdf', await makePdf({ pages: [{ freeText: ['POLE 1 = 39.1234,-119.1234'], lines: [{ text: 'POLE 1 = 39.1234,-119.1234', x: 50, y: 700 }] }] }))).points).toHaveLength(1));
test('unsupported type', async () => await expect(importFile('job.xlsx', Buffer.from(''))).rejects.toMatchObject({ code: 'unsupported_type' }));
```
`real-fixtures.test.ts` uses `describe.skipIf(!existsSync(dir))` over `server/test/fixtures-private/`: `A05J9XE.pdf` → the 7 labels with the exact coordinates listed in spec §5.1 (`POLE 1` = 39.63609452036168, -119.69104360818118 … `POLE 7` = 39.63616773905345, -119.67284033048661); `QUERY_1_-_A05K51V.docx` → 26 points, `points[0]` = `Query 1-1` at 39.35535679, -119.81288376, last two `Query 1-25A` (39.3548480, -119.8126657) and `Query 1-25B` (39.3540205, -119.8126599); and, if soffice exists, the same DOCX converted to PDF → the same 26 labels.

- [ ] **Step 2: Copy the owner's samples** (not committed): `mkdir -p server/test/fixtures-private && cp /root/.claude/uploads/ba4638bf-5ef8-5958-b902-cca747642d63/958958f7-A05J9XE.pdf server/test/fixtures-private/A05J9XE.pdf && cp "/root/.claude/uploads/ba4638bf-5ef8-5958-b902-cca747642d63/a4755df1-QUERY_1_-_A05K51V.docx" server/test/fixtures-private/QUERY_1_-_A05K51V.docx`. Then confirm `git status` doesn't list them.

- [ ] **Step 3: Run to verify failure.** Run: `npx vitest run server/test/import/`. Expected: the new tests FAIL.

- [ ] **Step 4: Implement `importFile`.** Pick the kind from the extension (`.pdf` / `.docx`, case-insensitive), else `ImportError('unsupported_type')`. Groups are the PDF groups, or the single DOCX group. For each group:
  - a group with no coordinate pairs → its text goes to `notes`;
  - otherwise run `extractLabelledPoints` on the group text; if it found no labelled points, run `extractQueryItems` on the group's blocks with layer `layerFromFileName(fileName)`;
  - leftover coordinates → points with type/layer = file-name layer, num by sequence, flag `unlabelled`;
  - leftover lines matching `/\b(POLE|PED|HH|MH|BU|SAI|PFP|MDU)\b\s*\d/i` or `/\d{1,3}\.\d{3,}/` → `unread`, with reason `type and number without readable coordinates` or `number that looks like a coordinate`.
  Then de-duplicate (same label, coordinates within 1e-7). Add `page-<p>` to each point's `refImageIds` (PDF), run `addSanityFlags`, and set `layerSuggestion` to the most common layer. `refImages` lists page renders (`kind 'page'`) and item images (`kind 'item'`).

- [ ] **Step 5: Run to verify pass.** Run: `npx vitest run server/test/import/`. Expected: PASS, including the real-fixture tests on this machine.

- [ ] **Step 6: Commit.** `git add -A && git commit -m "feat(import): importFile orchestration with real-file checks"`

---

## Milestone C: Server core

### Task 9: Config, database, app factory, destination interface

**Files:**
- Create: `server/src/config.ts`, `server/src/clock.ts`, `server/src/db/index.ts`, `server/src/db/migrations/001_init.sql`, `server/src/app.ts`, `server/src/context.ts`, `server/src/index.ts`, `server/src/delivery/destination.ts`
- Test: `server/test/app.test.ts`, `server/test/helpers/test-app.ts`

**Interfaces:**
- Produces:
```ts
// config.ts
export interface Config { dataDir: string; port: number; publicOrigin: string; sessionDays: number; destination: 'sharepoint' | 'fake'; buildId: string; testRoutes: boolean }
export function loadConfig(env?: NodeJS.ProcessEnv): Config;   // FMX_DATA_DIR (./data), PORT (8080), FMX_PUBLIC_ORIGIN, FMX_DESTINATION ('sharepoint'), FMX_BUILD_ID (git sha or 'dev'), FMX_TEST_ROUTES ('1' enables); sessionDays 90
// clock.ts
export interface Clock { now(): number }
export const systemClock: Clock;
export class FakeClock implements Clock { constructor(t?: number); now(): number; advance(ms: number): void }
// db/index.ts
export type DB = import('better-sqlite3').Database;
export function openDb(file: string): DB;          // WAL, foreign_keys ON, runs migrations in order, records user_version
// delivery/destination.ts
export interface PhotoDestination { rootName(): Promise<string>; listFolders(relPath: string): Promise<string[]>; ensureFolder(relPath: string): Promise<void>; fileInfo(relPath: string): Promise<{ size: number } | null>; upload(relPath: string, data: Buffer): Promise<void> }
export type DestinationErrorCode = 'requires_sign_in' | 'forbidden' | 'not_found' | 'exists' | 'network' | 'unexpected';
export class DestinationError extends Error { constructor(public code: DestinationErrorCode, message: string) { super(message) } }
export type DestinationFactory = (fieldNotesUrl: string) => PhotoDestination;
// context.ts
export interface ChangeRecorder { record(c: { jobId: string | null; entity: Change['entity']; entityId: string; data: Change['data'] }): Change }
export interface Ctx { config: Config; db: DB; clock: Clock; destinations: DestinationFactory; changes: ChangeRecorder /* ChangeLog from Task 11 implements it; a no-op recorder until then */; delivery: { kick(): void } /* DeliveryWorker from Task 17; no-op until then */ }
// app.ts
export async function buildApp(ctx: Ctx): Promise<import('fastify').FastifyInstance>;
// test helper
export async function testApp(opts?: { clock?: FakeClock; destination?: PhotoDestination }): Promise<{ app: FastifyInstance; ctx: Ctx; login(username: string): Promise<string /* cookie */>; seedUser(u: { username: string; role: Role; password?: string }): Promise<User> }>;
```
`relPath` values are relative to the Field Notes folder, using `/` separators (`''` = the root).

- [ ] **Step 1: Write `001_init.sql`.** Times are INTEGER ms since epoch; JSON columns are TEXT. Tables:
  - `users(id, username UNIQUE COLLATE NOCASE, display_name, password_hash, role, disabled, created_at)`
  - `sessions(token_hash PK, user_id, created_at, expires_at)`
  - `login_failures(username, at)`
  - `jobs(id, name, notes, field_notes_url, status, created_by, created_at, finished_at, deleted_at, deleted_by, delivery_problem)`
  - `job_files(id, job_id, kind, original_name, stored_path, sha256, layer, uploaded_by, created_at)`
  - `imports(id, user_id, file_name, kind, stored_path, result_json, created_at)`
  - `ref_images(id, job_id, import_id, kind, page, stored_path, mime)`
  - `points(id, job_id, layer, type, num, label, lat, lon, orig_lat, orig_lon, description, status, cant_reason, cant_text, pole_json, comment, folder_path, folder_candidates_json, ref_image_ids_json, flags_json, source_file_id, source_page, added_by, created_at, updated_at, updated_by, deleted_at, deleted_by)`
  - `photos(id, job_id, point_id, user_id, file_name, size, sha256, taken_at, lat, lon, accuracy_m, heading_deg, status, received_bytes, stored_path, dest_path, attempts, next_attempt_at, last_error, received_at, confirmed_at, upload_ms, reused_from, purged_at, removed_at, removed_by)`
  - `counters(name PK, value)`, `settings(key PK, value)`
  - `ops_done(op_id PK, user_id, at, result_json)`
  - `changes(seq INTEGER PRIMARY KEY AUTOINCREMENT, job_id, entity, entity_id, data_json, at)`
  - `positions(user_id PK, lat, lon, accuracy_m, altitude_m, at)`
  - `events(id INTEGER PRIMARY KEY AUTOINCREMENT, job_id, user_id, kind, data_json, at)`
  Indexes: `points(job_id)`, `photos(point_id)`, `photos(status, next_attempt_at)`, `changes(job_id)`.

- [ ] **Step 2: Write the failing tests:**
```ts
test('migrations create all tables', () => { const db = openDb(':memory:'); const names = db.prepare("select name from sqlite_master where type='table'").all().map((r: any) => r.name); for (const t of ['users','sessions','jobs','points','photos','changes','positions','events','ops_done','imports','ref_images','job_files','counters','settings','login_failures']) expect(names).toContain(t) });
test('migrations are idempotent', () => { const f = tmpFile(); openDb(f).close(); expect(() => openDb(f)).not.toThrow() });
test('GET /api/version', async () => { const { app } = await testApp(); const r = await app.inject('/api/version'); expect(r.json()).toEqual({ build: 'test' }) });
test('config defaults', () => expect(loadConfig({})).toMatchObject({ port: 8080, sessionDays: 90, destination: 'sharepoint', testRoutes: false }));
```

- [ ] **Step 3: Run to verify failure.** Run: `npx vitest run server/test/app.test.ts`. Expected: FAIL.

- [ ] **Step 4: Implement** the files above. `buildApp` registers cookie, websocket, multipart (limit 60 MB) and the routes added by later tasks; `@fastify/static` serves `web/dist` with SPA fallback to `index.html` for non-`/api` paths when the directory exists. `index.ts` loads config, opens `<dataDir>/fieldmaxx.db`, builds the context, starts the app and the delivery worker. `testApp` uses `:memory:`, a temp `dataDir`, buildId `test`, and a `FakeDestination` (Task 15) once it exists; until then a stub that throws.

- [ ] **Step 5: Run to verify pass.** Expected: PASS.

- [ ] **Step 6: Commit.** `git add -A && git commit -m "feat(server): config, sqlite schema, app factory, destination interface"`

### Task 10: Accounts, sessions, admin user management

**Files:**
- Create: `server/src/auth/passwords.ts`, `server/src/auth/sessions.ts`, `server/src/auth/plugin.ts`, `server/src/auth/routes.ts`, `server/src/users/service.ts`, `server/src/users/routes.ts`, `server/src/cli.ts`
- Test: `server/test/auth.test.ts`, `server/test/users.test.ts`

**Interfaces:**
- Produces:
```ts
export async function hashPassword(pw: string): Promise<string>;
export async function verifyPassword(hash: string, pw: string): Promise<boolean>;
export function createSession(ctx: Ctx, userId: string): string;                 // returns raw token; stores sha256(token)
export function sessionUser(ctx: Ctx, token: string): User | null;               // slides expiry to now + sessionDays
export async function createUser(ctx: Ctx, input: { username: string; displayName: string; password: string; role: Role }): Promise<User>;
// Fastify: request.user (User); preHandlers requireUser, requireAdmin (exported from auth/plugin.ts)
// Routes: POST /api/auth/login {username,password} → {user}; POST /api/auth/logout; GET /api/auth/me → {user}; POST /api/auth/password {current,next}
//         GET /api/admin/users; POST /api/admin/users {username,displayName,password,role}; PATCH /api/admin/users/:id {displayName?,role?,disabled?,password?}
// CLI: `node dist/cli.js create-admin <username> "<Display Name>"` reads the password from FMX_ADMIN_PASSWORD
```

- [ ] **Step 1: Write the failing tests:**
```ts
test('login sets fmx_session cookie (httpOnly, 90 days)', async () => { const { app, seedUser } = await testApp(); await seedUser({ username: 'free.walters', role: 'tech', password: 'pw123456' }); const r = await app.inject({ method: 'POST', url: '/api/auth/login', payload: { username: 'Free.Walters', password: 'pw123456' } }); expect(r.statusCode).toBe(200); const c = r.cookies.find(c => c.name === 'fmx_session')!; expect(c.httpOnly).toBe(true); expect(c.maxAge).toBe(90 * 86400) });
test('wrong password → 401 "Wrong username or password"', …);
test('11th failure within 15 min → 429', …);
test('disabled user → 403', …);
test('session slides: valid after 89 days idle, then 89 more', …);   // FakeClock
test('session expires after 91 days idle', …);
test('tech cannot list users (403); admin can', …);
test('admin creates user, resets password, disables; usernames unique case-insensitively (409)', …);
test('change own password requires current password', …);
test('create-admin CLI creates an admin', …);   // call the exported cli function directly
```

- [ ] **Step 2: Run to verify failure.** Run: `npx vitest run server/test/auth.test.ts server/test/users.test.ts`. Expected: FAIL.

- [ ] **Step 3: Implement.** argon2id via `@node-rs/argon2`. Cookie: `httpOnly`, `sameSite: 'lax'`, `secure` when `publicOrigin` starts with `https`, `path: '/'`. Ids: `crypto.randomUUID()`. Lockout counts rows in `login_failures` for the username in the last 15 min; clear them on success.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(server): accounts, sessions, admin user management"`

### Task 11: Change log, WebSocket hub, crew positions

**Files:**
- Create: `server/src/sync/changes.ts`, `server/src/sync/hub.ts`, `server/src/sync/routes.ts`, `server/src/positions/service.ts`
- Test: `server/test/sync.test.ts`

**Interfaces:**
- Produces:
```ts
export class ChangeLog implements ChangeRecorder { constructor(db: DB, clock: Clock, hub: Hub); record(c: { jobId: string | null; entity: Change['entity']; entityId: string; data: Change['data'] }): Change; since(seq: number, limit?: number): { changes: Change[]; seq: number } }
export class Hub { add(socket: WebSocket, user: User): void; broadcast(msg: ServerMessage): void; size(): number }
export function savePosition(ctx: Ctx, user: User, m: ClientMessage): CrewPosition;
export function listPositions(ctx: Ctx): CrewPosition[];   // omits positions older than 1 h
// Routes: GET /api/changes?since=N → {changes, seq}; GET /api/positions; WS /api/ws (cookie auth; sends {type:'hello', seq} on open)
```

- [ ] **Step 1: Write the failing tests:** `seq increases and since() filters`; `since() caps at limit and returns latest seq`; `ws rejects without session (close code 4401)`; `ws hello carries current seq`; `change broadcast reaches all connected clients`; `position message is stored and broadcast to others with displayName`; `positions older than 1 h are not listed`. Run the server with `app.listen({ port: 0 })` and use the `ws` client package.

- [ ] **Step 2: Run to verify failure.** Run: `npx vitest run server/test/sync.test.ts`. Expected: FAIL.

- [ ] **Step 3: Implement.** `record` inserts into `changes` and broadcasts `{type:'change'}`. Positions are upserted per user and broadcast; they are not written to `changes`.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(server): change log, websocket hub, crew positions"`

### Task 12: Imports and jobs API

**Files:**
- Create: `server/src/imports/routes.ts`, `server/src/jobs/service.ts`, `server/src/jobs/routes.ts`, `server/src/links/routes.ts`
- Test: `server/test/jobs.test.ts`

**Interfaces:**
- Consumes: `importFile` (Task 8), `ChangeLog` (Task 11), `DestinationFactory` (Task 9), `pointLabel`, `defaultFolderName` (Task 2).
- Produces:
```ts
export function createJob(ctx: Ctx, user: User, input: CreateJobInput): { job: Job; skippedDuplicates: string[] };
export function addImportToJob(ctx: Ctx, user: User, jobId: string, imp: JobImportInput): { points: Point[]; skippedDuplicates: string[] };
export function deleteJob(ctx: Ctx, user: User, jobId: string): void;
export function restoreJob(ctx: Ctx, user: User, jobId: string): void;
export function purgeDeletedJobs(ctx: Ctx): number;
export function jobSnapshot(ctx: Ctx, jobId: string): JobSnapshot;
export function rowToJob(r: any): Job; export function rowToPoint(r: any): Point;   // reused by later tasks
// Routes:
// POST /api/imports (multipart 'file') → ImportPreview          GET /api/imports/:importId/ref/:refId → image
// POST /api/jobs CreateJobInput → {job, skippedDuplicates}       POST /api/jobs/:id/imports JobImportInput → {points, skippedDuplicates}
// GET /api/jobs?include=finished|deleted → Job[] (with counts: {pointsTotal, pointsDone})   GET /api/jobs/:id/snapshot → JobSnapshot
// PATCH /api/jobs/:id {name?, fieldNotesUrl?, notes?} (a new fieldNotesUrl clears deliveryProblem and calls ctx.delivery.kick())   DELETE /api/jobs/:id   POST /api/jobs/:id/restore
// GET /api/jobs/:id/ref/:refId → image   GET /api/jobs/:id/files/:fileId → original PDF/DOCX   POST /api/links/check {url} → {ok:true, rootName} | {ok:false, error}
// snapshot.crew = distinct users with events on the job; snapshot.files = job_files
```

- [ ] **Step 1: Write the failing tests:**
```ts
test('upload → preview with 7 poles and stored ref images', …);              // multipart with aerialPdf()
test('upload of .xlsx → 415 "Only PDF and Word (.docx) files"', …);
test('create job from preview: points open, origLat null, label kept', …);
test('draft point without coordinates is rejected (400 lists labels)', …);
test('every signed-in user sees every active job', …);
test('snapshot returns job, points, photos, refImages, files, crew, seq', …);
test('setting the Field Notes link clears deliveryProblem and kicks delivery', …);
test('creator deletes own job; other tech 403; admin may', …);
test('admin restores within 30 days; 410 after; purge removes after 30 days', …);   // FakeClock
test('duplicate import skips identical points', async () => { /* add same preview twice → second call skippedDuplicates has 7 labels, job still has 7 points */ });
test('same label with different coordinates is added and flagged duplicate_label', …);
test('link check ok with fake destination returns rootName', …);
test('link check reports failure reason', async () => { /* destination.rootName throws DestinationError('requires_sign_in', 'This link needs a Microsoft sign-in') → {ok:false, error:'This link needs a Microsoft sign-in'} */ });
test('creating a job records a change', …);
```

- [ ] **Step 2: Run to verify failure.** Run: `npx vitest run server/test/jobs.test.ts`. Expected: FAIL.

- [ ] **Step 3: Implement.** Store uploaded files under `<dataDir>/imports/<importId>/`, and images under `<dataDir>/refs/`. On job creation, move the source file to `<dataDir>/jobs/<jobId>/files/`, and link `ref_images` and the point `ref_image_ids` to the job. A duplicate is the same label within 1 m. Imports unused after 24 h are deleted by an hourly sweep, alongside `purgeDeletedJobs`.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(server): imports, jobs, link check"`

### Task 13: Point operations (offline-safe, idempotent)

**Files:**
- Create: `server/src/points/ops.ts`, `server/src/points/routes.ts`
- Test: `server/test/ops.test.ts`

**Interfaces:**
- Consumes: `Op`, `OpResult` (Task 1), `ChangeLog`, `rowToPoint` (Task 12).
- Produces: `export function applyOps(ctx: Ctx, user: User, ops: Op[]): OpResult[]`. Route `POST /api/ops {ops}` → `{results}`. Photo ops (`photo.reuse`, `photo.remove`) are dispatched to Task 14's functions; until then they return `{ok:false, error:'unsupported'}`.

- [ ] **Step 1: Write the failing tests:**
```ts
test('replaying an opId returns the stored result and records no new change', …);
test('point.update sets only the given fields', …);
test('concurrent field edits both persist', async () => { /* tech A point.update {pole}, tech B point.move → point has A's pole and B's lat/lon */ });
test('first move keeps original coordinates; later moves keep the first original', …);
test('point.close without photoIds → error needs_photo; with photoIds → done', …);
test('point.cantShoot needs a reason; "other" needs text', …);
test('reopen returns point to open and clears cant fields', …);
test('point.add creates label from pointLabel and addedBy', …);
test('tech may delete a point with no received photos; blocked with photos_uploaded once one is received; admin may', …);
test('point.restore is admin only', …);
test('point.setFolder stores folderPath and clears candidates', …);
test('ops on a deleted job → error job_deleted', …);
test('each successful op records one change and one event', …);
```

- [ ] **Step 2: Run to verify failure.** Run: `npx vitest run server/test/ops.test.ts`. Expected: FAIL.

- [ ] **Step 3: Implement** in one SQLite transaction per op. Store the result in `ops_done`. Set `updated_at`/`updated_by` on each change. The delete check for "received" photos is any status other than `uploading`/`removed`.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(server): idempotent point operations"`

---

## Milestone D: Photos and delivery

### Task 14: Photo intake (resumable chunked upload)

**Files:**
- Create: `server/src/photos/service.ts`, `server/src/photos/routes.ts`
- Modify: `server/src/points/ops.ts` (dispatch photo ops)
- Test: `server/test/photos.test.ts`

**Interfaces:**
- Consumes: `photoFileName`, `defaultFolderName` (Task 2), `ChangeLog`, `ctx.delivery.kick()`.
- Produces:
```ts
export function createOrResume(ctx: Ctx, user: User, meta: PhotoUploadMeta): { offset: number; status: PhotoStatus };
export async function appendChunk(ctx: Ctx, user: User, photoId: string, offset: number, body: Buffer): Promise<{ offset: number; status: PhotoStatus }>;
export function reusePhoto(ctx: Ctx, user: User, op: Extract<Op, { kind: 'photo.reuse' }>): OpResult;
export function removePhoto(ctx: Ctx, user: User, op: Extract<Op, { kind: 'photo.remove' }>): OpResult;
export function nextCounter(ctx: Ctx, name: string): number;
export function rowToPhoto(r: any): PhotoInfo;
// Routes: POST /api/photos PhotoUploadMeta → {offset,status}; PUT /api/photos/:id/data?offset=N (application/octet-stream, ≤ 2 MB) → {offset,status}
//         GET /api/photos/:id → PhotoInfo; GET /api/photos/:id/file → JPEG while the server copy exists (404 after purge)
```

- [ ] **Step 1: Write the failing tests:**
```ts
test('create returns offset 0, status uploading', …);
test('two chunks complete the upload → received, file name POLE_1_<lat>_<lon>_<n>.jpg, delivery kicked', …);
test('resume returns current offset', …);
test('wrong offset → 409 with current offset', …);
test('sha256 mismatch → 422 and the upload restarts at 0', …);
test('photo larger than 40 MB rejected (413)', …);
test('photo for a deleted point → 410', …);
test('no GPS uses the point coordinates in the file name', …);
test('photo.reuse makes a new received photo sharing the stored file, reusedFrom set', …);
test('photo.remove by its owner before delivery deletes the server copy; after confirmation it only hides (status removed)', …);
test('photo.remove by another tech → forbidden; admin allowed', …);
```

- [ ] **Step 2: Run to verify failure.** Run: `npx vitest run server/test/photos.test.ts`. Expected: FAIL.

- [ ] **Step 3: Implement.** Write chunks to `<dataDir>/photos/<id>.part`, `fsync`, rename to `<id>.jpg` on completion. Folder name for the file name = `defaultFolderName(point.label)`; counter from `nextCounter('photo')` (starts at 1). Every status change records a change.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(server): resumable photo intake, reuse and removal"`

### Task 15: Fake destination and folder resolver

**Files:**
- Create: `server/src/delivery/fake.ts`, `server/src/delivery/folder-resolver.ts`
- Modify: `server/test/helpers/test-app.ts` (use `FakeDestination`)
- Test: `server/test/delivery/folder-resolver.test.ts`

**Interfaces:**
- Consumes: `PhotoDestination`, `DestinationError` (Task 9); `categoryMatches`, `pointFolderMatches`, `defaultFolderName`, `PointKey` (Task 2).
- Produces:
```ts
export class FakeDestination implements PhotoDestination { constructor(root?: string /* 'Field Notes' */); folders: Set<string>; files: Map<string, Buffer>; calls: string[]; failNext(n: number, code: DestinationErrorCode): void; uploadDelayMs: number }
export type FolderResolution = { kind: 'found' | 'created'; path: string } | { kind: 'ambiguous'; candidates: string[] };
export class FolderResolver { constructor(dest: PhotoDestination); resolve(p: PointKey): Promise<FolderResolution> }
```

- [ ] **Step 1: Write the failing tests** (FakeDestination pre-seeded with folders):
```ts
test('A05J9XE layout: top-level "1" for POLE 1', async () => expect(await resolver(['1','2','3']).resolve(pole1)).toEqual({ kind: 'found', path: '1' }));
test('category folder with mixed-case added pole', async () => expect(await resolver(['POLE', 'POLE/POLE_14b']).resolve(pole14b)).toEqual({ kind: 'found', path: 'POLE/POLE_14b' }));
test('nothing exists → creates POLE/POLE_33', async () => { const d = fake([]); expect(await new FolderResolver(d).resolve(pole33)).toEqual({ kind: 'created', path: 'POLE/POLE_33' }); expect(d.folders.has('POLE/POLE_33')).toBe(true) });
test('existing QUERY 1 category, missing item → QUERY 1/Query_1-43', …);
test('query point never matches a top-level pole folder "3"', …);
test('category match preferred over top-level', …);
test('two candidates in category → ambiguous with both paths', …);
test('existing category name casing is reused (Query 3 stays Query 3)', …);
test('folder listings are cached per resolver', async () => { /* resolve two points; listFolders('') called once */ });
```

- [ ] **Step 2: Run to verify failure.** Run: `npx vitest run server/test/delivery/folder-resolver.test.ts`. Expected: FAIL.

- [ ] **Step 3: Implement** the resolver order from spec §6.6 step 6: category subfolders first, then the top level, else create `<existing-or-new category>/<defaultFolderName(label)>`. Invalidate the cached listing of a folder after creating inside it. `FakeDestination.upload` throws `DestinationError('exists')` if the path exists, and requires the parent folder to exist (`not_found`).

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(delivery): fake destination and folder resolver"`

### Task 16: SharePoint share-link destination

**Files:**
- Create: `server/src/delivery/sharepoint-link.ts`
- Test: `server/test/delivery/sharepoint-link.test.ts`, `server/test/helpers/mock-sharepoint.ts`

**Interfaces:**
- Produces:
```ts
export class SharePointLinkDestination implements PhotoDestination { constructor(shareUrl: string, opts?: { fetch?: typeof fetch; clock?: Clock }) }
export function spPathLiteral(serverRelativePath: string): string;   // doubles ' then percent-encodes each segment, keeps '/'
export function sharePointDestinations(): DestinationFactory;         // caches one instance per URL for 30 min
```

- [ ] **Step 1: Write the mock server** (`node:http`) emulating the calls proven in spec §5.5, recording every request:
  - `GET /:f:/g/personal/u/TOKEN?e=x` → `302`, `Location: /personal/u/_layouts/15/onedrive.aspx?id=%2Fpersonal%2Fu%2FDocuments%2FOSP%20FIELDING%2FField%20Notes&ga=1`, `Set-Cookie: FedAuth=abc`;
  - `POST /personal/u/_api/contextinfo` → `{FormDigestValue, FormDigestTimeoutSeconds: 1800}`;
  - `GET …/GetFolderByServerRelativePath(decodedurl='…')/Folders?$select=Name` → `{value:[{Name}]}`;
  - `POST …/folders/AddUsingPath(decodedurl='…',overwrite=false)`;
  - `GET …/GetFileByServerRelativePath(decodedurl='…')?$select=Length` → 200 or 404;
  - `POST …/GetFolderByServerRelativePath(decodedurl='…')/Files/AddUsingPath(decodedurl='name',overwrite=false)` → 200, or 400 with `already exists` when present.
  Switches simulate cookie expiry (403 `Access denied` until the share URL is fetched again) and a sign-in-only link (302 to `https://login.microsoftonline.com/...`).

- [ ] **Step 2: Write the failing tests:** `rootName is "Field Notes"`; `listFolders('POLE') requests the encoded server-relative path`; `apostrophes are doubled and spaces encoded` (`spPathLiteral("/a/O'Brien St/x")` → `/a/O''Brien%20St/x`); `ensureFolder creates each missing segment in order`; `upload sends X-RequestDigest, octet-stream body, overwrite=false`; `fileInfo returns {size} or null on 404`; `upload of an existing name → DestinationError exists`; `sign-in redirect → DestinationError requires_sign_in "This link needs a Microsoft sign-in"`; `digest refreshed after its timeout`; `cookie expiry: one re-resolve then success`; `persistent 403 → DestinationError forbidden "The link no longer allows uploads"`; `network failure → DestinationError network`. Also add `sharepoint-link.live.test.ts` with `describe.skipIf(!process.env.FMX_LIVE_SHARE_URL)`: rootName + listFolders('') only. It must be read-only, because the link can't delete test files.

- [ ] **Step 3: Run to verify failure.** Run: `npx vitest run server/test/delivery/sharepoint-link.test.ts`. Expected: FAIL.

- [ ] **Step 4: Implement.** Resolve with `fetch(shareUrl, { redirect: 'manual' })`: site URL = Location origin + path up to `/_layouts`; root path = the decoded `id` query parameter; cookies from `headers.getSetCookie()`. Send `Accept: application/json;odata=nometadata` everywhere. Cache the digest until 60 s before its timeout. On any 401/403, re-resolve once and retry.

- [ ] **Step 5: Run to verify pass.** Expected: PASS.

- [ ] **Step 6: Commit.** `git add -A && git commit -m "feat(delivery): SharePoint share-link destination"`

### Task 17: Delivery worker, retries, purge and disk guard

**Files:**
- Create: `server/src/delivery/worker.ts`, `server/src/delivery/purge.ts`
- Modify: `server/src/index.ts` (start worker; hourly purge), `server/src/context.ts`
- Test: `server/test/delivery/worker.test.ts`, `server/test/delivery/purge.test.ts`

**Interfaces:**
- Consumes: `FolderResolver` (Task 15), `PhotoDestination` (Task 9), `rowToPhoto` (Task 14), `ChangeLog`.
- Produces:
```ts
export function nextDelay(attempts: number): number;   // 15s, 30s, 60s, 2m, 4m, 8m, 16m, then 30m cap
export class DeliveryWorker { constructor(ctx: Ctx, opts?: { concurrency?: number /* 4 */ }); start(): void; stop(): Promise<void>; kick(): void; runOnce(): Promise<number> }
export function retryPhoto(ctx: Ctx, user: User, photoId: string): void;   // admin: attempts=0, next_attempt_at=now
export async function purgeConfirmed(ctx: Ctx, usage?: () => Promise<number /* used % */>): Promise<{ deleted: number }>;
export async function diskUsedPct(dir: string): Promise<number>;           // fs.statfs
```

- [ ] **Step 1: Write the failing tests** (FakeDestination, FakeClock):
```ts
test('received photo is delivered to POLE/POLE_1 and confirmed with timings', …);
test('existing same-size file counts as delivered without uploading again', …);
test('existing different-size file → counter bumped, new name uploaded', …);
test('3 failures then success: delays 15s, 30s, 60s; confirmed', …);
test('6 failures → stuck with last_error; still retried every 30 min; admin retry resets', …);
test('never more than 4 uploads in flight', async () => { /* 10 photos, uploadDelayMs 50, track max concurrent */ });
test('ambiguous folder: not uploaded, point gets folderCandidates; after point.setFolder it delivers', …);
test('forbidden destination sets job deliveryProblem; next success clears it', …);
test('job without field notes link: photos wait, job deliveryProblem "Add the Field Notes link"', …);
test('point folder chosen once is reused for later photos', …);
test('purge: confirmed > 7 days deleted, unconfirmed kept regardless of age', …);
test('disk guard: at 85% deletes oldest confirmed until usage ≤ 75%, never unconfirmed', …);
```

- [ ] **Step 2: Run to verify failure.** Run: `npx vitest run server/test/delivery/`. Expected: FAIL.

- [ ] **Step 3: Implement.** Due photos: status `received` or `stuck` with `next_attempt_at <= now`, ordered by `received_at`, with stored file present. Per photo:
  1. Resolve the folder (store `folder_path` on the point, record a change).
  2. Check `fileInfo(dest)`.
  3. Upload if needed, then verify the size.
  4. Mark `confirmed` with `confirmed_at` and `upload_ms`, and record a change.
  On error: `attempts++`; `next_attempt_at = now + nextDelay(attempts)`; `stuck` once attempts ≥ 6. Errors coded `requires_sign_in`/`forbidden` set `jobs.delivery_problem` to the error message and record a job change. `kick()` triggers an immediate run; otherwise poll every 5 s. Cache one resolver per job for 10 min.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(delivery): worker with retries, verification, purge, disk guard"`

### Task 18: Pole data export, photo ZIP, finish job

**Files:**
- Create: `server/src/exports/pole-data.ts`, `server/src/exports/zip.ts`, `server/src/jobs/finish.ts`, `server/src/exports/routes.ts`
- Test: `server/test/exports.test.ts`

**Interfaces:**
- Produces:
```ts
export const POLE_COLUMNS = ['Point', 'Original lat', 'Original lon', 'Moved lat', 'Moved lon', 'Line height (ft)', 'Anchor', 'Self-supporting/Lashed', 'Tags', 'Comment', 'Photos', 'Tech', 'Closed at'] as const;
export function poleDataRows(ctx: Ctx, jobId: string): string[][];   // POLE-type points, sorted by number then suffix
export function poleDataCsv(rows: string[][]): string;
export async function poleDataXlsx(rows: string[][]): Promise<Buffer>;
export async function writePhotosZip(ctx: Ctx, jobId: string, out: NodeJS.WritableStream): Promise<void>;
export async function finishJob(ctx: Ctx, user: User, jobId: string): Promise<{ ok: true } | { ok: false; pointsLeft: number; photosPending: number }>;
// Routes: GET /api/jobs/:id/pole-data.csv | .xlsx; GET /api/jobs/:id/photos.zip (admin); POST /api/jobs/:id/finish
```

- [ ] **Step 1: Write the failing tests:** `csv header equals POLE_COLUMNS joined by commas`; `row shows moved coordinates only when moved; tags joined with "; "; anchor Yes/No`; `rows sorted 1, 2, 10, 14, 14B`; `xlsx reopens with exceljs with the same header`; `zip contains POLE/POLE_1/<file>.jpg for stored photos (folderPath or default folder)`; `finish blocked lists points left and photos pending`; `finish ok uploads "<job>_pole_data.xlsx" to the Field Notes root (or "_2" if taken) and marks the job finished`.

- [ ] **Step 2: Run to verify failure.** Run: `npx vitest run server/test/exports.test.ts`. Expected: FAIL.

- [ ] **Step 3: Implement** with exceljs and archiver (store method, no recompression for JPEGs). "Points left" = open points; "photos pending" = photos not `confirmed`/`removed`.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(server): pole data export, photo zip, finish job"`

---

## Milestone E: Web app (`web`)

### Task 19: Web scaffold, sign-in, offline store and sync engine

**Files:**
- Create: `web/package.json`, `web/vite.config.ts`, `web/index.html`, `web/tsconfig.json`, `web/src/main.tsx`, `web/src/App.tsx`, `web/src/types.ts`, `web/src/styles/tokens.css`, `web/src/api/client.ts`, `web/src/api/ws.ts`, `web/src/store/db.ts`, `web/src/store/sync.ts`, `web/src/store/apply.ts`, `web/src/auth/LoginScreen.tsx`, `web/src/auth/session.ts`
- Test: `web/test/sync.test.ts`, `web/test/login.test.tsx`, `web/test/setup.ts`

**Interfaces:**
- Produces:
```ts
// types.ts: web-only types shared by later tasks
export interface Fix { lat: number; lon: number; accuracyM: number; altitudeM: number | null; at: number }
export interface CompassReading { deg: number; at: number; absolute: boolean }
export interface CaptureMeta { jobName: string; pointLabel: string; source: 'camera' | 'gallery'; fix: Fix | null; heading: CompassReading | null; takenAt: number }
export type PhotoView = { id: string; thumbUrl: string; status: 'phone' | 'server' | 'onedrive' | 'stuck'; error: string | null; destPath: string | null };
// api/client.ts
export class ApiError extends Error { constructor(public status: number, message: string, public code?: string) { super(message) } }
export interface Api { login(u: string, p: string): Promise<User>; logout(): Promise<void>; me(): Promise<User | null>; changePassword(c: string, n: string): Promise<void>; jobs(include?: string): Promise<Array<Job & { pointsTotal: number; pointsDone: number }>>; snapshot(jobId: string): Promise<JobSnapshot>; changes(since: number): Promise<{ changes: Change[]; seq: number }>; ops(ops: Op[]): Promise<OpResult[]>; importFile(f: File): Promise<ImportPreview>; createJob(i: CreateJobInput): Promise<{ job: Job; skippedDuplicates: string[] }>; addImport(jobId: string, i: JobImportInput): Promise<{ points: Point[]; skippedDuplicates: string[] }>; patchJob(id: string, p: Partial<Pick<Job, 'name' | 'fieldNotesUrl' | 'notes'>>): Promise<Job>; checkLink(url: string): Promise<{ ok: true; rootName: string } | { ok: false; error: string }>; deleteJob(id: string): Promise<void>; finishJob(id: string): Promise<{ ok: true } | { ok: false; pointsLeft: number; photosPending: number }>; positions(): Promise<CrewPosition[]>; photoCreate(m: PhotoUploadMeta): Promise<{ offset: number; status: PhotoStatus }>; photoChunk(id: string, offset: number, body: Blob): Promise<{ offset: number; status: PhotoStatus }> }
export const api: Api;
// api/ws.ts
export class Live { connect(): void; close(): void; send(m: ClientMessage): void; on(cb: (m: ServerMessage) => void): () => void; onState(cb: (s: 'open' | 'closed') => void): () => void }   // reconnect with backoff 1s→30s
// store/db.ts — Dexie 'fieldmaxx' v1
export interface LocalPhoto { id: string; jobId: string; pointId: string; original: Blob; upload: Blob | null; meta: CaptureMeta; state: 'saved' | 'ready' | 'uploading' | 'uploaded' | 'confirmed' | 'stuck'; offset: number; sha256: string | null; error: string | null; createdAt: number; confirmedAt: number | null }
export const db: Dexie & { jobs: Table<Job>; points: Table<Point>; photos: Table<PhotoInfo>; refImages: Table<RefImageInfo & { jobId: string }>; outbox: Table<{ seq?: number; op: Op }>; localPhotos: Table<LocalPhoto>; meta: Table<{ key: string; value: unknown }> };
// store/apply.ts
export function applyOpLocally(op: Op, now: number, userId: string): Promise<void>;
export function applyChange(c: Change): Promise<void>;
// store/sync.ts
export class SyncEngine { constructor(deps: { api: Api; live: Live; now?: () => number }); start(user: User): void; stop(): void; openJob(jobId: string): Promise<void>; enqueue(op: Op): Promise<void>; status(): 'online' | 'offline' | 'syncing'; onAuthRequired(cb: () => void): () => void }
```

- [ ] **Step 1: Scaffold `web`.**
  - **Vite:** React plugin; `vite-plugin-pwa` with `registerType: 'prompt'`, a manifest named "Fieldmaxx" (`theme_color #0f172a`, `display standalone`), and Workbox runtime caching for both Esri tile URLs (CacheFirst, 3000 entries, 30 days) and for `/api/jobs/*/ref/*` reference images (CacheFirst, 500 entries), so references work offline (§6.8).
  - **`index.html` meta tags:** viewport `width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no, viewport-fit=cover`, `theme-color #0f172a`, `apple-mobile-web-app-capable yes`, `apple-mobile-web-app-status-bar-style black-translucent`.
  - **Dev server:** proxy `/api` (http + ws) to `localhost:8080`.
  - **Tests:** `test/setup.ts` imports `fake-indexeddb/auto` and `@testing-library/jest-dom`; environment `jsdom`.

- [ ] **Step 2: Write the failing tests:**
```ts
test('enqueue applies the op locally at once', …);              // point.update → db.points has new comment
test('ops queued offline survive a new engine (reload) and flush in order when online', …);
test('server change overwrites local point', …);
test('reconnect asks for changes since last seq', …);
test('401 while flushing pauses the outbox and fires onAuthRequired without dropping ops', …);
test('login screen shows "Wrong username or password" on 401', …);
```

- [ ] **Step 3: Run to verify failure.** Run: `npx vitest run web/test/sync.test.ts web/test/login.test.tsx`. Expected: FAIL.

- [ ] **Step 4: Implement.** Outbox flush posts batches of ≤ 50 in order, retrying with backoff 2 s → 60 s. `meta.lastSeq` updates after applying changes. `openJob` stores the snapshot. `App.tsx` routes: login → job list → map. The session check runs at start via `api.me()`.

- [ ] **Step 5: Run to verify pass.** Expected: PASS.

- [ ] **Step 6: Commit.** `git add -A && git commit -m "feat(web): scaffold, sign-in, offline store and sync"`

### Task 20: Platform adapters and crew position sender

**Files:**
- Create: `web/src/platform/geolocation.ts`, `web/src/platform/compass.ts`, `web/src/platform/camera.ts`, `web/src/platform/storage.ts`, `web/src/crew/positionSender.ts`
- Test: `web/test/platform.test.ts`

**Interfaces:**
- Consumes: `Fix`, `CompassReading` from `web/src/types.ts` (Task 19); `Live` (Task 19).
- Produces:
```ts
export interface GeoSource { start(): void; stop(): void; latest(): Fix | null; subscribe(cb: (f: Fix) => void): () => void }
export const browserGeo: GeoSource;   // watchPosition, enableHighAccuracy, maximumAge 0
export interface CompassSource { requestPermission(): Promise<boolean>; start(): void; latest(): CompassReading | null }
export const browserCompass: CompassSource;
export function headingFromEvent(e: { webkitCompassHeading?: number; alpha: number | null; absolute?: boolean }): number | null;
export function pickPhoto(opts: { capture: boolean }): Promise<File | null>;   // hidden <input accept="image/*" [capture="environment"]>
export function requestPersistentStorage(): Promise<boolean>;
export function shouldSend(prev: { fix: Fix; sentAt: number } | null, next: Fix, now: number): boolean;   // > 5 m moved or ≥ 15 s
export function startPositionSender(geo: GeoSource, live: Live): () => void;
```

- [ ] **Step 1: Write the failing tests:** `first fix is always sent`; `4 m move within 15 s not sent; 6 m sent`; `15 s elapsed sends even without movement`; `iOS webkitCompassHeading used as is`; `Android absolute alpha 90 → heading 270`; `non-absolute alpha → null`; `pickPhoto creates input with capture="environment" only when capture is true`.

- [ ] **Step 2: Run to verify failure.** Run: `npx vitest run web/test/platform.test.ts`. Expected: FAIL.

- [ ] **Step 3: Implement.** Compass listens to `deviceorientationabsolute`, falling back to `deviceorientation`. `requestPermission` calls `DeviceOrientationEvent.requestPermission()` when present (iOS).

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(web): platform adapters and crew position sender"`

### Task 21: Job list, new job + check screen, job info

**Files:**
- Create: `web/src/jobs/JobList.tsx`, `web/src/jobs/NewJob.tsx`, `web/src/jobs/CheckScreen.tsx`, `web/src/jobs/checkModel.ts`, `web/src/jobs/JobInfo.tsx`, `web/src/jobs/FieldNotesLink.tsx`
- Test: `web/test/check.test.tsx`, `web/test/jobs.test.tsx`

**Interfaces:**
- Produces:
```ts
// checkModel.ts
export interface CheckState { previews: ImportPreview[]; edits: Record<string, DraftPoint[]>; ignoredUnread: Set<string>; layers: Record<string, string> }
export function summary(points: DraftPoint[]): string[];                   // ['Found 7 POLE points'] / ['Found 26 QUERY 1 points', '1 needs coordinates']
export function canSave(s: CheckState): boolean;                            // no point with lat null; every unread fixed or ignored
export function fixUnread(s: CheckState, importId: string, line: UnreadLine, label: string, coords: string): CheckState;   // parses coords via findCoordinatePairs; throws 'Coordinates not recognised'
export function toJobImports(s: CheckState): JobImportInput[];
```

- [ ] **Step 1: Write the failing tests:** `summary per type`; `save disabled while a no-coordinates point remains; enabled after fixing its coordinates or removing it`; `fixing an unread line adds a point and clears the line`; `ignoring an unread line enables save`; `check screen lists flags as badges ("Possibly swapped", "Sign differs", "Far from the rest", "Duplicate label", "No label", "No coordinates")`; `job list shows "12/159" progress and finished jobs under "Finished"`; `delete button visible only to creator or admin, confirm dialog names the job`; `Field Notes link "Check" shows root name or the error`; `saving without a link shows "Photos will wait until the Field Notes link is added"`.

- [ ] **Step 2: Run to verify failure.** Run: `npx vitest run web/test/check.test.tsx web/test/jobs.test.tsx`. Expected: FAIL.

- [ ] **Step 3: Implement.** Check screen layout:
  - top: summary lines and a small Leaflet preview of all points;
  - an editable table (label, lat, lon, description, flags, remove);
  - a "Couldn't read" section showing each raw line, a "Fix" form (label + coordinates) and "Ignore";
  - layer name field per file;
  - Field Notes link field with "Check";
  - Save.
  Adding files to an existing job reuses the same screen.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(web): job list, import check screen, job info"`

### Task 22: Map screen

**Files:**
- Create: `web/src/map/model.ts`, `web/src/map/viewMemory.ts`, `web/src/map/MapScreen.tsx`, `web/src/map/TopBar.tsx`, `web/src/map/ChipRow.tsx`, `web/src/map/ProgressStrip.tsx`, `web/src/map/PointMarkers.tsx`, `web/src/map/CrewLayer.tsx`, `web/src/map/MyLocation.tsx`, `web/src/map/MapControls.tsx`, `web/src/map/SearchPanel.tsx`, `web/src/map/LeftList.tsx`
- Test: `web/test/map-model.test.ts`, `web/test/map-screen.test.tsx`

**Interfaces:**
- Produces:
```ts
export function progress(points: Point[]): { done: number; total: number; pct: number; byType: Array<{ type: string; done: number; total: number }> };   // done + cant_shoot count as complete; deleted excluded
export function visiblePoints(points: Point[], f: { type: string | 'ALL'; hideShot: boolean }): Point[];
export function chipLabel(t: { type: string; done: number; total: number }): string;   // '✓ POLE (80/80)' or 'POLE (12/80)'
export function sunsetText(now: Date, lat: number, lon: number): string;            // '☀️ 5h 40m until sunset' | '🌙 After sunset'
export function remainingByDistance(points: Point[], from: LatLon | null): Point[];
export function crewDotState(p: CrewPosition, now: number): 'fresh' | 'faded' | 'hidden';
export function searchPoints(points: Point[], q: string): Point[];                  // label, num, description; case-insensitive
export function saveView(userId: string, jobId: string, v: { lat: number; lon: number; zoom: number }): void;
export function loadView(userId: string, jobId: string): { lat: number; lon: number; zoom: number } | null;   // key 'fmx:view:<user>:<job>'; try/catch
```

- [ ] **Step 1: Write the failing tests:** pure-model tests for each function, including:
  - `progress counts cant_shoot as complete and ignores deleted`;
  - `chipLabel complete vs incomplete`;
  - `sunsetText 5 h 40 min before suncalc's sunset for Reno (39.53, -119.81) is "☀️ 5h 40m until sunset"; 1 min after is "🌙 After sunset"`;
  - `crewDotState 9 min fresh, 11 min faded, 61 min hidden`;
  - `loadView returns null when localStorage throws`.
  Screen tests (react-leaflet with jsdom; assert the initial view props): `restores the saved view on mount`, `fits all points when no saved view`, `"Hide shot" hides done points`, `tapping a chip filters by type`, `pill shows "online", "offline" or "3 photos waiting"`, `job.deliveryProblem shows a red banner with its message under the top bar`.

- [ ] **Step 2: Run to verify failure.** Run: `npx vitest run web/test/map-model.test.ts web/test/map-screen.test.tsx`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - **Layers and controls:** Esri imagery plus the labels overlay, toggled by `Aa`; the house-numbers button `123` is wired in Task 27. Max zoom 21, max native zoom 19. Controls at bottom right: Aa, 123, center-on-me ⊙, zoom.
  - **Markers:** 30 px circles with `markerCode`, white 2 px border, one colour per type. Shot points are grey `#9ca3af`, opacity 0.55, struck through. A small orange badge marks points with unconfirmed photos.
  - **Clustering:** leaflet.markercluster with `disableClusteringAtZoom: 18`.
  - **Bars:** top bar, chip row and progress strip laid out as in spec §6.3, with "Left: N" opening `LeftList`.
  - **View memory:** save the view on `moveend`.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(web): map screen with filters, progress, crew, view memory"`

### Task 23: Point sheet

**Files:**
- Create: `web/src/point/PointSheet.tsx`, `web/src/point/PoleDataForm.tsx`, `web/src/point/QueryReference.tsx`, `web/src/point/CloseControls.tsx`, `web/src/point/MoveControl.tsx`, `web/src/point/FolderInfo.tsx`, `web/src/point/DeletePoint.tsx`, `web/src/point/gps.ts`, `web/src/photos/usePointPhotos.ts` (stub: `export function usePointPhotos(pointId: string): PhotoView[] { return [] }`, replaced in Task 25)
- Test: `web/test/point-sheet.test.tsx`

**Interfaces:**
- Consumes: `SyncEngine.enqueue` (Task 19), `Fix`, `PhotoView` (Task 19 `web/src/types.ts`).
- Produces:
```ts
export function navigateUrl(lat: number, lon: number): string;   // 'https://www.google.com/maps/dir/?api=1&destination=<lat>,<lon>'
export function gpsBadge(fix: Fix | null): { text: string; tone: 'green' | 'amber' | 'red' | 'none' };   // 'GPS ±12 ft'; ≤16 green, ≤50 amber, else red; null → 'No GPS yet'
export const TYPE_INSTRUCTIONS: Record<string, string>;          // { POLE: 'Photograph the pole and the carrier equipment on it.' }
export function canDeletePoint(user: User, photos: PhotoView[]): boolean;   // admin, or no photo past 'phone'
```

- [ ] **Step 1: Write the failing tests:**
  - `pole form enqueues point.update with lineHeightFt 25.5, anchor, lashed, tags`;
  - `Done disabled with 0 photos, enabled with 1 local photo`;
  - `Can't shoot: reason required, "Other" requires text`;
  - `Navigate link href`;
  - `Move: drag + Confirm enqueues point.move; "Undo move" within 10 s enqueues the previous coordinates`;
  - `Delete hidden for a tech once a photo reached the server; confirm warns "2 photos on this phone will be discarded"`;
  - `query point shows reference images (tap opens full size)`;
  - `ambiguous folder shows the candidates and choosing one enqueues point.setFolder`;
  - `folder line shows "→ Field Notes/1"`.

- [ ] **Step 2: Run to verify failure.** Run: `npx vitest run web/test/point-sheet.test.tsx`. Expected: FAIL.

- [ ] **Step 3: Implement** the bottom sheet from spec §6.4, in Cadastrus's order: header (name, ➤ Navigate, ⌖ Move, ✕), instructions/description, GPS badge, pole data, query reference, comment, Take photo / Pick from gallery / Use existing photos, photo strip, Done / Can't shoot / Reopen, delete. Comment and pole fields save on change (debounced 500 ms).

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(web): point sheet with pole data, move, close, delete"`

### Task 24: Add point flow and "Points I added"

**Files:**
- Create: `web/src/map/AddPointFlow.tsx`, `web/src/menu/PointsIAdded.tsx`
- Test: `web/test/add-point.test.tsx`

**Interfaces:**
- Consumes: `ADD_CATEGORIES`, `suggestAddedPoleNum`, `nextNum`, `pointLabel` (Task 2); `SyncEngine.enqueue`.
- Produces: `export function suggestName(points: Point[], type: string, layer: string, at: LatLon): string` (POLE → nearest pole's number with `suggestAddedPoleNum`; others → `nextNum` of that type's numbers).

- [ ] **Step 1: Write the failing tests:** `nearest pole 14 → suggests "POLE 14B"; with 14B present → "POLE 14C"`; `Street images with 1, 2 → "Street images 3"`; `type list = job layers then ADD_CATEGORIES without duplicates`; `confirm enqueues point.add at the crosshair and opens the new point`; `"Points I added" lists only the current user's added points`.

- [ ] **Step 2: Run to verify failure.** Run: `npx vitest run web/test/add-point.test.tsx`. Expected: FAIL.

- [ ] **Step 3: Implement.** The "+" button starts at the latest GPS fix (else the map centre) with a fixed centre crosshair; dragging the map adjusts it. A type picker and an editable number follow.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(web): add point flow and points I added"`

### Task 25: Photo pipeline: capture, stamp, queue, statuses

**Files:**
- Create: `web/src/photos/capture.ts`, `web/src/photos/process.ts`, `web/src/photos/exif.ts`, `web/src/photos/uploader.ts`, `web/src/photos/cleanup.ts`, `web/src/photos/PhotoStrip.tsx`, `web/src/photos/WherePhotosWent.tsx`, `web/src/photos/ReusePicker.tsx`
- Modify: `web/src/photos/usePointPhotos.ts` (replace the Task 23 stub)
- Test: `web/test/photos-process.test.ts`, `web/test/uploader.test.ts`, `web/test/photo-strip.test.tsx`

**Interfaces:**
- Consumes: `stampLines`, `stampLayout`, `StampMeta` (Task 3); `GeoSource`, `CompassSource`, `pickPhoto`, `requestPersistentStorage` (Task 20); `db.localPhotos`, `CaptureMeta`, `Fix`, `CompassReading`, `PhotoView` (Task 19); `Api.photoCreate`/`photoChunk`.
- Produces:
```ts
export async function savePhoto(file: File, jobId: string, pointId: string, meta: CaptureMeta): Promise<string>;   // writes original first; returns local id (uuid, also the server photo id)
export function targetSize(w: number, h: number, maxPixels?: number /* 16_000_000 */): { w: number; h: number };
export interface OriginalExif { lat?: number; lon?: number; altitudeM?: number; takenAt?: number; headingDeg?: number; make?: string; model?: string }
export function readOriginalExif(jpeg: ArrayBuffer): OriginalExif;
export function buildExif(e: { lat: number | null; lon: number | null; altitudeM: number | null; headingDeg: number | null; trueNorth: boolean; takenAt: Date; make?: string; model?: string }): string;   // piexif dump, Orientation=1
export function resolveStamp(meta: CaptureMeta, orig: OriginalExif, now: number): StampMeta;   // GPS stale when fix older than 10 s; gallery uses EXIF GPS/time when present
export async function makeUploadCopy(original: Blob, stamp: StampMeta, exifStr: string, quality: 0.85 | 0.95): Promise<Blob>;
export class Uploader { constructor(deps: { api: Api; concurrency?: number /* 3 */; chunkBytes?: number /* 524288 */ }); start(): void; stop(): void; kick(): void }
export async function cleanupConfirmed(now: number): Promise<number>;   // deletes blobs 3 days after confirmedAt
export function usePointPhotos(pointId: string): PhotoView[];           // thumbnail from the stamped upload copy once processed, else the original
export function pendingCount(local: LocalPhoto[], server: PhotoInfo[]): number;
```

- [ ] **Step 1: Write the failing tests:**
```ts
test('targetSize keeps 3000x4000', () => expect(targetSize(3000, 4000)).toEqual({ w: 3000, h: 4000 }));
test('targetSize caps 48MP to 16MP keeping aspect', () => expect(targetSize(8000, 6000)).toEqual({ w: 4618, h: 3464 }));
test('buildExif round-trips GPS, altitude, direction and time', …);           // piexif.load on the dump
test('resolveStamp: fresh fix used; 11 s old fix → gpsStale; gallery with EXIF GPS uses it and is marked fromGallery', …);
test('savePhoto stores the original before processing; a processing failure leaves it saved', …);
test('uploader resumes from the server offset after a dropped chunk', …);
test('uploader never runs more than 3 uploads at once', …);
test('queue survives reload and uploads each photo once', async () => { /* 30 photos saved while api rejects with network error; new Uploader instance; api online → photoCreate completes once per id, all reach "uploaded" */ });
test('401 pauses the uploader without changing photo states', …);
test('server confirmation (applyChange) marks local photo confirmed; cleanup removes blobs only after 3 days', …);
test('photo strip shows ⏳ on phone, ☁ on server, ✓ in OneDrive, ⚠ stuck with the reason', …);
test('pendingCount counts local not uploaded + server not confirmed, no double counting', …);
```

- [ ] **Step 2: Run to verify failure.** Run: `npx vitest run web/test/photos-process.test.ts web/test/uploader.test.ts web/test/photo-strip.test.tsx`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - **Capture:** on the first photo of a session, call `compass.requestPermission()` (iPhone's one-time prompt). Read the heading from the compass when the file returns; prefer the camera's EXIF image direction when present. Save the original Blob to Dexie (state `saved`) before anything else, then kick the processor.
  - **Processor:** `createImageBitmap(blob, { imageOrientation: 'from-image' })` → scale to `targetSize` → draw on `OffscreenCanvas` (fallback `<canvas>`).
  - **Stamp:** `ctx.font = '700 <fontPx>px system-ui'`, white fill, `shadowColor rgba(0,0,0,0.9)`, `shadowBlur shadowBlurPx`, lines at `firstBaselineY + i * lineHeightPx`.
  - **Encode:** `convertToBlob({ type: 'image/jpeg', quality })` (quality from `GET /api/settings`, default 0.85) → `piexif.insert(buildExif(...))`. Compute sha256 with `crypto.subtle`; state becomes `ready`.
  - **Uploader:** call `photoCreate` (gets the offset), then send chunks from that offset; on `received`, state becomes `uploaded`. Server photo changes drive `confirmed`/`stuck`.
  - **Startup and schedule:** call `requestPersistentStorage()` at app start; run cleanup at start and hourly.
  - **Screens:** `WherePhotosWent` lists, per point, each photo with destPath and status, plus a link to the Field Notes URL. `ReusePicker` lists this job's other photos (server copies via `/api/photos/:id/file` while available) and enqueues `photo.reuse`.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(web): photo capture, stamping, resumable queue, statuses"`

### Task 26: Menu, admin screens, update prompt

**Files:**
- Create: `server/src/admin/routes.ts`; `web/src/menu/Menu.tsx`, `web/src/admin/Users.tsx`, `web/src/admin/Jobs.tsx`, `web/src/admin/DeliveryHealth.tsx`, `web/src/admin/Activity.tsx`, `web/src/admin/Settings.tsx`, `web/src/app/UpdatePrompt.tsx`
- Test: `server/test/admin.test.ts`, `web/test/admin.test.tsx`

**Interfaces:**
- Produces (server): `GET /api/admin/health → { diskUsedPct: number; photos: PhotoInfo[] /* not confirmed, stuck first */; timings: { phoneToServerMedianS: number | null; serverToOneDriveMedianS: number | null } /* photos confirmed in the last 24 h: receivedAt − takenAt, confirmedAt − receivedAt */ }`; `POST /api/admin/photos/:id/retry` (calls `retryPhoto`); `GET /api/admin/activity?jobId= → Array<{ at: number; user: string; kind: string; label: string | null; detail: string }>`; `GET /api/settings → { photoQuality: 'standard' | 'maximum' }`; `PUT /api/admin/settings { photoQuality }`. All admin routes 403 for techs.

- [ ] **Step 1: Write the failing tests:** server `health lists stuck before received, admin only`; `health timings are medians over the last 24 h`; `retry resets attempts`; `activity lists can't-shoot with reason`; `settings default standard, admin can set maximum`. Web `menu shows admin section only for admins`; `menu shows a red count on Delivery health when any photo is stuck`; `delivery health shows disk %, an alert above 80%, and typical times per hop`; `finish job shows "7 points left, 3 photos not in OneDrive yet" when blocked`; `users screen creates a tech and shows the temporary password once`; `jobs screen restores a deleted job`; `update prompt "Update available — tap to reload" appears when the service worker reports a new version`.

- [ ] **Step 2: Run to verify failure.** Run: `npx vitest run server/test/admin.test.ts web/test/admin.test.tsx`. Expected: FAIL.

- [ ] **Step 3: Implement** the menu from spec §6.11. "Map imagery" isn't in v1; the build id comes from `/api/version`. Admin screens are plain lists using the shared dark styles.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat: menu, admin screens, update prompt"`

### Task 27: House-number overlay

**Files:**
- Create: `web/src/map/houseNumbers.ts`, `web/src/map/HouseNumbersLayer.tsx`
- Test: `web/test/house-numbers.test.ts`

**Interfaces:**
- Produces: `export function overpassQuery(b: { s: number; w: number; n: number; e: number }): string`; `export function parseHouseNumbers(json: unknown): Array<{ lat: number; lon: number; text: string }>` (nodes, plus ways via `out center`).

- [ ] **Step 1: Write the failing tests:** `query contains "addr:housenumber" and the bbox in s,w,n,e order with "out center"`; `parse nodes and way centers; skips entries without a number`.

- [ ] **Step 2: Run to verify failure.** Run: `npx vitest run web/test/house-numbers.test.ts`. Expected: FAIL.

- [ ] **Step 3: Implement.** POST to `https://overpass-api.de/api/interpreter` on `moveend` at zoom ≥ 17, debounced 600 ms, caching per rounded bbox. Draw labels in a non-interactive pane at z-index 390 using the `house-number-label` style (white bold 12 px with dark text shadow, from Cadastrus's CSS). Toggle with the `123` button.

- [ ] **Step 4: Run to verify pass.** Expected: PASS.

- [ ] **Step 5: Commit.** `git add -A && git commit -m "feat(web): house-number overlay"`

### Task 28: End-to-end tests

**Files:**
- Create: `e2e/playwright.config.ts`, `e2e/global-setup.ts`, `e2e/field.spec.ts`, `e2e/fixtures/photo-3000x4000.jpg` (generated in global setup), `server/src/test-routes.ts`
- Modify: `server/src/app.ts` (register test routes when `config.testRoutes`)

**Interfaces:**
- Produces: `GET /__test/destination` → `{ folders: string[]; files: Array<{ path: string; size: number }> }` and `GET /__test/destination/file?path=` (bytes). Enabled only when `FMX_TEST_ROUTES=1` and `FMX_DESTINATION=fake`.

- [ ] **Step 1: Write `global-setup.ts`.** Build web and server; start the server on port 8099 with a temp data dir, `FMX_DESTINATION=fake` and `FMX_TEST_ROUTES=1`; create an admin and two techs via the CLI; write the aerial replica PDF (Task 8 helper) and a 3000×4000 JPEG to `e2e/fixtures/`. Config: `--trace off`, Chromium with geolocation permission.

- [ ] **Step 2: Write the specs:**
  - `tech imports a job, shoots POLE 1, photo is confirmed in OneDrive`: upload the PDF → "Found 7 POLE points" → Save with a dummy link → 7 markers → open POLE 1 → `setInputFiles` on the camera input → strip shows ✓ within 30 s. `/__test/destination` has `POLE/POLE_1/POLE_1_*.jpg`, 3000×4000, and EXIF GPS equal to the context geolocation.
  - `reload keeps the map view`: zoom/pan, reload, centre within 1e-5 and same zoom.
  - `offline photo uploads after reconnect`: `context.setOffline(true)`, take photo → ⏳; reload (app shell from the service worker) → still ⏳; `setOffline(false)` → ✓.
  - `second tech sees the closed point and the first tech's dot within 5 s`: two contexts, geolocations set.
  - `tech cannot delete a point after its photo reached the server`.

- [ ] **Step 3: Run.** Run: `npx playwright test -c e2e --trace off`. Expected: all specs PASS. Fix code, not tests, on failure (use superpowers:systematic-debugging).

- [ ] **Step 4: Commit.** `git add -A && git commit -m "test: end-to-end field workflow"`

### Task 29: Deployment

**Files:**
- Create: `Dockerfile`, `docker-compose.yml`, `deploy/Caddyfile`, `deploy/setup.sh`, `deploy/backup.sh`, `deploy/README.md`
- Modify: `server/src/cli.ts` (add `speedtest`)
- Test: `server/test/cli-speedtest.test.ts`

**Interfaces:**
- Produces: `node dist/cli.js speedtest <shareUrl>`, which uploads 3 synthetic 9 MB JPEGs into `_fieldmaxx_speedtest/` and prints `Upload: <n> MB/s (3 files, <s> s)`. The README says to use a throwaway shared folder and delete it afterwards, because the link can't delete.

- [ ] **Step 1: Write the failing test:** `speedtest uploads 3 files to FakeDestination under _fieldmaxx_speedtest/ and prints MB/s`.

- [ ] **Step 2: Run to verify failure.** Run: `npx vitest run server/test/cli-speedtest.test.ts`. Expected: FAIL.

- [ ] **Step 3: Implement.**
  - **`Dockerfile`:** multi-stage on `node:22-bookworm-slim`. Build stage: `npm ci`, build web and server. Runtime: production deps, `server/dist`, `web/dist`, volume `/data`, `ENV FMX_DATA_DIR=/data`, `EXPOSE 8080`.
  - **`docker-compose.yml`:** `app` plus `caddy:2` (ports 80/443, Caddyfile `{$DOMAIN} { reverse_proxy app:8080 }`). Volumes `fmx-data` and `caddy-data`.
  - **`setup.sh`:** for Ubuntu. Installs Docker, asks for the domain (suggesting `<ip>.sslip.io`), the admin username, display name and password, writes `.env`, runs `docker compose up -d --build`, then `docker compose exec app node server/dist/cli.js create-admin …`, and installs a nightly cron for `backup.sh`.
  - **`backup.sh`:** `sqlite3 .backup` into `/data/backups/fieldmaxx-YYYYMMDD.db`, keeping 14.
  - **`README.md`:** the server requirements from spec §7.3 (US-West, ≥ 1 Gbps, ≥ 4 TB transfer, ≥ 80 GB disk), setup steps, how to run the speed test, updating (`git pull && docker compose up -d --build`), and restoring a backup.

- [ ] **Step 4: Verify.** Run: `npx vitest run server/test/cli-speedtest.test.ts` → PASS. Run `bash -n deploy/setup.sh deploy/backup.sh` → no output. If Docker is available, also run `docker build -t fieldmaxx .` → success. If not, run `npm run build -w web && npm run build -w server && FMX_DATA_DIR=$(mktemp -d) FMX_DESTINATION=fake node server/dist/index.js` and confirm `curl -s localhost:8080/api/version` returns JSON and `curl -s localhost:8080/` returns the app HTML.

- [ ] **Step 5: Final check.** Run: `npm test && npm run typecheck && npx playwright test -c e2e --trace off`. Expected: all PASS.

- [ ] **Step 6: Commit.** `git add -A && git commit -m "feat: docker deployment, setup and backup scripts, speed test"`
