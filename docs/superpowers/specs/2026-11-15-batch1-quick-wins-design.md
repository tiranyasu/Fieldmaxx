# Batch 1: quick wins (design)

Status: proposed to the owner 2026-11-15. It changes the shipped v1 described in
`2026-11-13-fieldmaxx-design.md`; where the two disagree, this document wins for these four items.

## Why
The owner compared Fieldmaxx with Cadastrus and with a friend's web clone (Atlas). These four changes
remove daily friction:
- typing tags by hand;
- points clumping into bubbles;
- hunting for a nearby point's photos to answer a query;
- no quick way to rename a point or get directions to it.

Batches 2 (client skeleton and query links, new-point alerts) and 3 (saved no-signal map, folder
check) come later, each with its own design.

## Goals
1. **No clumping.** Every point always shows as its own marker, at every zoom level.
2. **Reuse by nearest point.** "Use existing photos" lists *points* that have photos, nearest first:
   - from any job, within 150 m, each with its distance;
   - tapping a point shows its photos to pick from;
   - the "This job" tab and the search by point label stay as they are.
3. **⋮ menu on every point**, with four entries:
   - **Edit / Rename:** type and number.
   - **Move:** the existing drag, with undo.
   - **Delete:** the existing rules. Anyone may delete a point until a photo of it reaches OneDrive;
     after that, only an admin.
   - **Directions:** opens Google Maps navigation to the point.

   If photos of a point are already in OneDrive, renaming it warns that new photos go to the new
   folder name and the earlier photos stay in the old folder. The share link can't move files.
4. **Tags and a stamp preview.**
   - Tags apply to every point type, not just poles.
   - The crew shares one tag list. Anyone can add a tag from the point screen with "+ New tag". An
     admin can rename or delete tags in Admin settings. The starting list is the current six (N/A,
     Carrier label, Grounding, No ground, No fiber, Damaged cable).
   - The point screen shows a live stamp preview above the camera button: exactly the lines that
     will be printed.
   - The stamp gains one last line, shown only when it has content: the point's tags, then
     `HOA <line height>` for poles, then a per-point comment, joined by ` · `. Cadastrus prints tags
     the same way. **HOA is the existing "line height" field**, not a new one.
   - Tags and the comment stay saved on the point, so the pole data export keeps its Tags column and
     every photo of the point gets the same last line.

## Non-goals
- Batches 2 and 3.
- Moving or renaming files already in OneDrive.
- Per-phone tag lists.
- Tags per photo, as opposed to per point.

## Anti-goals
- A tag spelled several ways across the crew. A shared list prevents this; an admin cleans up.
- The stamp preview differing from what is actually printed.
- Renaming a point silently splitting its photos across two folders. The rename warns first.

## Approaches
There is one sensible way for each item, so no alternatives were put to the owner. These were
considered and set aside:
- **Per-phone tags, as Atlas does.** Rejected: the spreadsheet would fill with near-duplicates.
- **Tags per photo, as Atlas does.** Rejected: Fieldmaxx's export and point model keep tags on the
  point, and per-photo tags would break that.
- **Keeping clustering at very low zoom.** Rejected: the owner wants no clumping at all, and the
  native map renders hundreds of markers without it.

## Decided
- HOA = line height (the owner's answer).
- The tag list is server data, served with the app settings. An unknown tag already on a point
  still shows, so renames and deletes never lose data.
- The comment is a new optional point field. It syncs like the other point fields.

## Left to the builder
- Where the tag list lives on the server: a settings row or its own table. The app receives it with
  the config.
- Exact layout of the ⋮ menu and the stamp preview, following the existing point sheet.
- How the stamp's last line wraps when it is long. It must stay readable and match the preview.
