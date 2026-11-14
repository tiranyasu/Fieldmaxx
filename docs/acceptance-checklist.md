# Fieldmaxx phone checklist

This is the last check before the crew uses Fieldmaxx. You try every part of the app on a real phone, against your test
folder, and tick off each step. Each step says what to do, then **Expect:** what you should see. When you see
something else, write down the step number, what you saw and the time, and take a screenshot. Your developer needs
those three things to fix it.

## Before you start

- **Your phone:** Android 9 or newer, with good LTE signal for most steps.
- **A second phone** with Fieldmaxx, signed in as another crew member (steps 30 to 36).
- **The test version of the app.** Your developer publishes it on your server's download page. For step 41 they
  publish a second, newer test version. The second version must be built where the first one was, on the same
  computer, so both carry the same test signature: Android only lets a version update one with the same signature.
  (For the developer: both debug builds must use the same `~/.android/debug.keystore`; a new cloud session makes a
  new one.)
- **Two files on the phone,** for example in Downloads: the job file `A05J9XE.pdf` and the query file
  `QUERY_1_-_A05K51V.docx`.
- **Your test folder's link** (the OneDrive folder you use for testing), copied so you can paste it.

Good to know:

- Everything the app sends goes into your test folder. Fieldmaxx never overwrites or deletes anything in OneDrive, and
  the link can't delete. You remove the test files yourself at the end (step 42).
- The marks on a photo: ⏳ on the phone, ⬆ uploading, ✓ in OneDrive, ⚠ stuck (tap it for the reason and
  **Retry now**). A small 🛡 means the photo is also backed up on your server.
- **Force stop:** Android Settings > Apps > Fieldmaxx > Force stop. **Airplane mode:** in the quick settings at the
  top of the screen.

## A. Install and sign in

1. ☐ **Install from the download page.** On the phone, open `https://<your-domain>/` in the browser. Tap
   **Download Fieldmaxx for Android** and follow the three steps on the page.
   **Expect:** Android asks once to allow installs from this source. After that, Fieldmaxx installs and its icon is on
   the phone.
2. ☐ **Sign in.** Open Fieldmaxx. Check the server address (fill it in if it's empty), type your username and
   password, and tap **Sign in**.
   **Expect:** the Jobs screen.

## B. The test job

3. ☐ **Import the job file.** Tap **New job** and pick `A05J9XE.pdf`. You can also share the file to Fieldmaxx from
   the Files app.
   **Expect:** "Reading…", then the check screen with **"Found 7 POLE points"**.
4. ☐ **Set the test folder link.** In **Field Notes link**, paste your test folder's link and tap **Check**. Then tap
   **Save job**.
   **Expect:** a green "✓ Folder: …" with your test folder's name. After **Save job**, the job's map opens with all 7
   points in view.

## C. Location

5. ☐ **Location is only asked "while using the app".** The first time the map opens, Android asks about location.
   Read the choices, then tap **Don't allow**.
   **Expect:** Android offers location only while you use the app (Android 9 just says "Allow"), and never "Allow all
   the time". After **Don't allow**, the map still works and shows **"Location off"**, with no blue dot.
6. ☐ **Allow location.** Go back to the job list and open the job again. When Android asks, choose **While using
   the app**. Step outside for a good fix.
   **Expect:** your blue dot, with a pale circle around it that shows how exact the position is.

## D. The map

7. ☐ **Imagery and street names.** Zoom in on the poles, then tap **Aa** twice.
   **Expect:** satellite imagery. Street names are on to begin with. The first tap hides them, the second shows them
   again.
8. ☐ **Markers and clusters.** Look at the 7 poles, zoom out until they merge, then tap the merged circle.
   **Expect:** each pole is a coloured circle marked **P**. Zoomed out, nearby points merge into one circle with a
   number. Tapping it zooms in until they separate.
9. ☐ **House numbers.** Zoom in close on a street with houses and tap **123**. Then zoom out, and tap **123** again.
   **Expect:** house numbers appear on the buildings when you're zoomed in close, and not when you're zoomed out. The
   second tap hides them.
10. ☐ **Zoom while the map moves.** Flick the map so it keeps gliding, and tap **+** while it moves. Then tap **+**
    twice quickly.
    **Expect:** the map zooms in where it is now and doesn't jump back. Two quick taps zoom in two steps.
11. ☐ **Touch the map while a job loads.** Open the job on weak signal, or right after adding it, and pinch and drag
    the world map before the points appear.
    **Expect:** when the points arrive, the map still moves to show the whole job.
12. ☐ **Rotate the phone.** Pan and zoom to a spot. Turn the phone to landscape, then back.
    **Expect:** the same spot and zoom each time.
13. ☐ **The map keeps its view.** Pan and zoom to a spot. Close Fieldmaxx (swipe it away in the recent apps), open it
    again and open the job.
    **Expect:** the map shows the same spot and zoom as when you left.

## E. Photos

14. ☐ **The point screen.** Tap the marker of POLE 1.
    **Expect:** a sheet showing "POLE 1", **Navigate**, **Move point**, "Photograph the pole and the carrier
    equipment on it.", a GPS badge like "GPS ±12 ft", the pole data, **Take photo**, **Pick from gallery**, and a
    folder line like "→ Field Notes/POLE/POLE_1".
15. ☐ **The live stamp and compass.** Tap **Take photo** (allow the camera when Android asks). Look at the text in the
    bottom-left corner, then turn slowly on the spot.
    **Expect:** the stamp shows five lines: "A05J9XE · POLE 1", the coordinates, "±… ft · alt … ft", a compass line
    like "NE 47°", and the date and time. The compass line changes as you turn.
16. ☐ **Camera controls.** Check that the screen shows what the photo will show. Pinch to zoom, tap the flash button
    (it goes Flash auto, Flash on, Flash off), then take 3 photos in a row without leaving the camera.
    **Expect:** zoom and flash work, "3 photos taken" shows above the shutter, and the point has 3 photos.
17. ☐ **The compass follows the camera.** Hold the phone upright as for a photo and face a known direction (a compass
    app helps). Do it in portrait, then in landscape, then again with auto-rotate locked.
    **Expect:** each time, the compass line shows the direction the camera faces, and it follows when you turn.
18. ☐ **Compass calibration.** Hold the phone next to something magnetic, like a car door or a speaker, until the
    camera says **"Compass: calibrate"**. Step away and move the phone in a figure-8 a few times.
    **Expect:** the message goes away.
19. ☐ **A photo reaches your test folder within 30 seconds.** On good LTE, take one photo of POLE 1 and watch its
    thumbnail on the point.
    **Expect:** ⏳, then ⬆, then ✓ within about 30 seconds, and a 🛡 too. While it's on its way, the pill at the top
    says "1 photo waiting" and POLE 1's marker has a small badge. Both go once it's ✓. In your test folder, POLE/POLE_1
    holds the photo, named like `POLE_1_<latitude>_<longitude>_<number>.jpg`, and it opens with the stamp on it. (If you
    look while it still shows ⬆, the file can be there with 0 bytes. That's normal: OneDrive fills it in when the
    upload finishes.)
20. ☐ **Done.** On POLE 1, tap **Done**.
    **Expect:** the marker turns grey, faded and struck through. (Done needs at least one photo.)
21. ☐ **The marks show properly.** Look at the photos' marks (⏳ ⬆ ✓ ⚠ 🛡) on the point and in
    **Menu > Where the photos went**.
    **Expect:** each mark shows clearly, never as an empty square.

## F. Photo safety

22. ☐ **No signal.** Turn on airplane mode and take 2 photos of POLE 2. Then pan around an area of the map you looked
    at earlier. Turn airplane mode off.
    **Expect:** both photos show ⏳ and the pill says "Offline · 2 photos waiting". POLE 2's marker has the badge. The
    imagery you looked at earlier still shows (places you never looked at stay blank, but the points still show).
    Back online, both photos go ✓ within a minute or so.
23. ☐ **Force stop during an upload.** Take a photo of POLE 3. While it shows ⬆, force stop Fieldmaxx. Open it again.
    **Expect:** the photo carries on and goes ✓. In your test folder, POLE/POLE_3 has exactly one file for it, full
    size: not two files of the same photo, and no empty file.
24. ☐ **Cut off halfway through an upload.** Take a photo of POLE 4. While it shows ⬆, turn on airplane mode and
    force stop Fieldmaxx straight away. Turn airplane mode off and open Fieldmaxx.
    **Expect:** the photo goes ✓, with exactly one file for it in POLE/POLE_4.
25. ☐ **Cut off overnight.** In the evening, take a photo of POLE 5 and turn on airplane mode while it shows ⬆. Leave
    the phone like that until the next morning (OneDrive gives up on an unfinished upload after a while). Turn
    airplane mode off and open Fieldmaxx.
    **Expect:** the photo goes ✓, with exactly one file for it in POLE/POLE_5, full size. Write down how long it took
    to reach ✓, and whether it showed ⚠ first.
26. ☐ **Dead battery (only if you're willing).** Right after taking a photo, let the battery run out, or take it out
    on a phone where you can. Charge the phone, turn it on and open Fieldmaxx.
    **Expect:** the photo is still there and goes ✓.
27. ☐ **The gallery copy.** In **Menu > Settings**, "Copy photos to the phone's gallery" is on. Take a photo, then
    look in the phone's Gallery or Photos app. Then switch the setting off and take another photo.
    **Expect:** with the setting on, the stamped photo is in an album called **Fieldmaxx**. On Android 9, Fieldmaxx
    asks for storage access the first time (allow it). On Android 10 and newer it doesn't ask, and the album is the
    Pictures/Fieldmaxx folder. With the setting off, no new photo goes to the album.

## G. Camera permission and gallery photos

28. ☐ **"Only this time" (Android 11 and newer).** In Android Settings > Apps > Fieldmaxx > Permissions > Camera,
    choose "Ask every time". Open the camera from a point and choose **Only this time**. Take a photo, press Home, wait
    a minute, then go back to Fieldmaxx's camera.
    **Expect:** Android asks again, or the camera shows a message with **Open settings** and **Pick from gallery**.
    It is never just a black screen.
29. ☐ **A photo from the gallery.** Take a photo with the phone's own camera app, with location on. In Fieldmaxx, open
    a point, tap **Pick from gallery** and choose that photo.
    **Expect:** the stamp shows that photo's own location and time, and its time line ends **"(from gallery)"**.
    Write down what Android asked you, for example about access to photo locations.

## H. The crew

30. ☐ **What the second phone sees.** On the second phone, open the same job. Then, on your phone, press Home to
    leave Fieldmaxx, and watch the second phone. Open Fieldmaxx on your phone again afterwards.
    **Expect:** the second phone shows POLE 1 grey (you shot it), and your dot with your name and how long ago it
    moved. A few seconds after you leave the app, your dot turns grey and reads "<your name> · app closed · … ago".
    After 10 minutes it's faded, and after an hour it's gone. Back in the app, your dot is normal again.

## I. Points

31. ☐ **Add the query file.** Go back to the Jobs screen. On the job's ⋮ menu, choose **Add files**, pick
    `QUERY_1_-_A05K51V.docx`, and tap **Add to job**.
    **Expect:** the query's points (named like "Query 1-12") are added to the job, marked **Q** on the map. They may
    be some way from the poles: zoom out, or use the search in the top bar.
32. ☐ **Reuse a photo on a query point.** Open a query point and tap **Use existing photos**. On **This job**, pick one
    of your POLE photos.
    **Expect:** the photo appears on the query point marked "reused from POLE … · A05J9XE · <date>", and goes ✓ within
    a minute. Your test folder gets it under "QUERY 1/Query_1-<number>/" with a new name and the original stamp.
33. ☐ **Move a point.** On POLE 6, tap **Move point** and drag the marker. While the bar says "Drag the marker to
    where the point is.", also pan the map around it. Tap **Confirm**, then tap **Undo move**.
    **Expect:** the map can be dragged around the bar. After **Confirm**, "POLE 6 moved · Undo move" shows for about
    10 seconds, and **Undo move** puts the point back where it was.
34. ☐ **Can't shoot.** On POLE 7, tap **Can't shoot** and choose **Other**. Try **Save**, then type a reason in "Why
    not?" and tap **Save**.
    **Expect:** **Save** stays greyed out until you type a reason. Then the point closes, grey, showing "Can't shoot:
    Other · " and your reason.
35. ☐ **Add POLE 14B.** Tap the **+** at the bottom left, drag the map so the cross is on the spot, and tap **Next**.
    Choose **POLE**. The name suggested is the nearest pole's number plus the next free letter (like POLE 7B): change
    it to **POLE 14B** and tap **Add point**.
    **Expect:** a new POLE 14B marker. **Menu > Points I added** lists it with your name, and the second phone shows
    it too.
36. ☐ **Delete a point.** Open POLE 14B (it has no photo) and tap **Delete point**, then **Delete**.
    **Expect:** the question "Delete POLE 14B?" says the point goes for the whole crew and that an admin can restore
    it. After **Delete**, it disappears from your map and the second phone's.

## J. The menu

37. ☐ **Where the photos went.** Open **Menu > Where the photos went**, then tap **Open the Field Notes folder**.
    Go back and tap **Menu > Field Notes link** too.
    **Expect:** every photo with its place in OneDrive (like "→ Field Notes/POLE/POLE_1/POLE_1_…jpg") and its mark.
    The button and the menu entry both open your test folder, in the browser or the OneDrive app.
38. ☐ **Export photos to the OneDrive app.** Turn on airplane mode and take a photo, so it waits. Open
    **Menu > Export photos to OneDrive app** and choose the OneDrive app or Files in the share sheet. Turn airplane
    mode off afterwards.
    **Expect:** a ZIP laid out like Field Notes (POLE/POLE_…/…jpg) that holds the waiting photo.
39. ☐ **Pole data export.** On POLE 1, fill in **Line height (ft)**, **Anchor**, **Self-supporting** or **Lashed**,
    a tag and a comment. Then open **Menu > Pole data export**, choose **Excel (.xlsx)** and **Save to Downloads**.
    Do it again with **CSV (.csv)**.
    **Expect:** "Saved to Downloads: …" each time. The files open with one row per point, and POLE 1's values filled
    in.
40. ☐ **Finish the job.** Open **Menu > Finish this job** while some points are still open. Then close the rest
    (**Done**, or **Can't shoot**), wait until every photo is ✓, and finish again.
    **Expect:** the first time, "Not finished yet" lists what's left. The second time, **Finish** marks the job
    Finished on the Jobs screen, and your test folder gets `A05J9XE_pole_data.xlsx` at its top level.
41. ☐ **In-app update.** Ask your developer to publish the newer test version. Restart Fieldmaxx, or tap
    **Menu > Check for update**.
    **Expect:** **"Update available — tap to install"**. Tapping it downloads the update and opens Android's
    installer. After **Install**, Fieldmaxx opens with your jobs and photos still there, and the menu shows the new
    version.

    If Android's installer says "App not installed", or names a conflict, the two test versions were signed
    differently: they weren't built on the same computer. That's a mix-up in the build, not a Fieldmaxx fault, so
    write it down and tell your developer. The way out: once every photo on the phone shows ✓, uninstall Fieldmaxx,
    install the newer test version from the download page, and sign in again. Uninstalling deletes every photo that
    isn't in OneDrive yet, so don't uninstall while any photo is still waiting.

A test version can't update the crew's version, and the crew's version can't update a test version: they are signed
differently. Moving a phone from one to the other means uninstalling Fieldmaxx first, and uninstalling deletes every
photo that isn't in OneDrive yet. Do it only when every photo on that phone shows ✓.

## K. Clean up

42. ☐ **Delete the test files.** In OneDrive, delete by hand what these steps made in your test folder: the photos in
    POLE and QUERY 1, and `A05J9XE_pole_data.xlsx`. Also delete the folder `_fieldmaxx_it`: the automatic tests left
    it there. On the Jobs screen, delete the test job (⋮ > Delete).
    **Expect:** your test folder holds only what you want to keep.

## L. The pilot

43. ☐ **Pilot on one real EES job.** Run one real EES job with Fieldmaxx alongside the normal process, using that
    job's real Field Notes link, before the crew switches.
    **Expect:** **Check** on the link says ✓. That is the first time Fieldmaxx uses a company OneDrive link (your test
    folder is a personal one). The photos land in the job's own point folders (for example a folder named 1 for
    POLE 1, if the job has those) or in new POLE_<number> folders. They are named like the normal process's, and
    nothing is overwritten. **Finish this job** writes the pole data file into the job's Field Notes folder, and
    everything matches the normal process. Only then does the crew switch: jobs already started finish the old way,
    and new jobs start in Fieldmaxx.

While you're in job A05J9XE's real Field Notes folder: the very first upload test left a folder
`_FIELDMAXX_TEST_DELETE_ME` there. Someone with full access to that folder should delete it.
