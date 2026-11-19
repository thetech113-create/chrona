# Adding a new alarm mission

Mission types are bare string literals repeated across 5 files. There is no registry and no
enum, so the compiler cannot tell you what you missed. **Every site below fails silently when
it does not recognise a mission id** — `mapNotNull` drops it, a whitelist returns `null`, a
flag stays `false`, or `catch (_: Exception) {}` swallows it.

That is why a missed site does not crash. It surfaces days later as a blank screen, a mission
that never runs, or an alarm that will not switch off.

Line numbers are from the Speech-to-Text (`"stt"`) work and will drift. Search by symbol name.

Worked examples to copy from: **`"stt"`** (Speech-to-Text) and **`"face"`** (Face Verify).
Both are user-paced, so both appear in the `startTimeout` skip-list, and both stage their
config before the alarm id exists — see §5a.

---

## 1. Registration — miss these and the mission never runs at all

| File | Symbol | If you miss it |
|---|---|---|
| `sequencer/SequencerMissionCodec.kt` | `parseMissionToken` (~19) | **Worst one.** Callers use `mapNotNull`, so the mission is silently dropped from the queue and never runs. No error anywhere. |
| `sequencer/SequencerMissionCodec.kt` | `serializeMissionToken` (~33) | Falls through `else -> mission.id`, so it usually works by luck. Add it explicitly anyway. |
| `sequencer/SequencerMissionCodec.kt` | `displayName` (~60) | Alarm card shows a raw id instead of a readable name. |
| `AlarmForegroundService.kt` | fallback whitelist in `createFullScreenIntent` (~535) | **Blank screen on first ring.** The sequencer isn't running yet, so this whitelist is the only resolver; a miss leaves `mission_type = "sequencer"` and nothing renders. Tapping the notification still works, which makes this one confusing to diagnose. |
| `sequencer/MissionSequencer.kt` | `startTimeout` skip-list (~1453) | **Mission auto-fails after 30s.** Only list a mission here if it drives its own lifecycle (user-paced). Symptoms: the Start Mission button vanishes partway through, and the alarm never toggles off. |
| `sequencer/MissionSequencer.kt` | timeout `when` (~205) | Mission gets the 30s default. Harmless if it is in the skip-list above, but keep the two consistent. |

## 2. AlarmActivity state — miss these and the screen is blank

There is **one `missionXEnabled` flag per mission**, assigned in 11 places. The flag must be set
before `setContent` runs, or the first frame renders with no mission and you get a blank screen.

| Line | Context | Notes |
|---|---|---|
| ~233 | `bindDpsMissionFromSnapshot` | Direct Boot path |
| ~363 | mission activation | |
| ~615 | `sequencerComplete` reset | Set to `false` |
| ~653 | `onNewIntent` | Also reset per-mission scratch state here |
| ~1324 | **`onCreate`** | **The blank-screen fix.** Runs synchronously before `setContent`. |
| ~1382 | preview branch | Preview is a *separate* path — it reads `preview_mission`, not `mission_type` |
| ~1750, ~1772 | two `LaunchedEffect`s | Run *after* first composition, so they cannot cover ~1324 |
| ~3750 | dismiss reset | Set to `false` |
| ~4607 | `"none"` restore-block reset | Set to `false` |
| ~4615 / ~4574 | SharedPreferences restore / save | |
| ~4671 / ~4652 | `onRestoreInstanceState` / `onSaveInstanceState` | Survives rotation and process death |

## 3. UI gates — miss these and you get the wrong screen, or a bypass hole

| Line | Gate | If you miss it |
|---|---|---|
| ~3335, ~3342 | `hasMission` (protected + normal branches) | No mission UI renders at all |
| ~3568 | `dismissButtonVisible` | **Security hole.** The Dismiss button renders next to your challenge and the user can skip it entirely. |
| ~3391 | password-fallback block | Your UI and the password UI both render |
| ~742 | "active mission detected" guard | State gets wiped on unlock |

## 4. Creation UI — `MainActivity.kt`

| Line | Symbol |
|---|---|
| ~4176 | mission chooser `listOf(...)` |
| ~4001 | `missionLabel` |
| ~4009 | `missionSubtitle` |
| ~4017 | `missionTag` |
| ~4025 | `missionIcon` |
| ~4033 | `missionAccent` |
| ~3494 | `normalizeMissionSelection` — miss it and the mission is dropped when **editing** an existing alarm |
| ~4367 | per-mission settings card, if the mission needs configuration |

## 5. Mission-specific extras

- A support object in `sequencer/` (see `SttMissionSupport`, `MathMissionSupport`,
  `FaceMissionSupport`) holding the id, config persistence and validation.
- Any new permission in `AndroidManifest.xml`, plus a runtime request. A denied permission must
  never count as mission success.
- If the mission holds a system resource (microphone, camera), release it in `onDestroy` — not
  just on the success path. An orphaned resource survives a swipe-away and blocks the relaunched
  activity from acquiring it again.

### 5a. Config captured before the alarm exists

The alarm id does not exist until the user taps Create Alarm, so anything configured in the
dialog (a phrase, a face, a difficulty) must be **staged, then committed**:

```
dialog: savePendingX(context, value)          // no id yet
create: commitPendingX(context, alarmId)      // moves pending -> alarm_<id>, clears pending
ring:   getX(context, alarmId)                // per-alarm value only
```

Three rules learned the hard way with Speech and Face:

1. **Never read a pending value from a real alarm.** Gate any pending fallback behind
   `isPreview`, or an alarm with no config of its own will silently use whatever was last
   staged for a different alarm.
2. **Clear the pending value when the config card first appears** (`LaunchedEffect(Unit)`).
   Abandoning the dialog leaves it behind, and the next new alarm then claims it is already
   configured.
3. **Preview runs on a throwaway alarm id** (9100) with nothing committed to it, so it must
   read the staged value. Preview also takes a separate code path in `AlarmActivity` — it reads
   `preview_mission`, not `mission_type`.

### 5b. Releasing hardware acquired asynchronously

`ProcessCameraProvider.getInstance()` (and similar) resolves on a callback. Two distinct leaks:

- **Wrong lifecycle owner.** `LocalLifecycleOwner.current` inside a Compose `Dialog` is the
  **Activity**, which stays RESUMED after the dialog closes. The binding outlives the UI and the
  camera keeps running. Always unbind in `onDispose`.
- **Dispose/callback race.** If the UI closes before the provider callback fires, `onDispose`
  has nothing to release — then the callback binds with no owner left to free it. Keep a
  `disposed` flag and have the callback release instead of binding.

Both are in `FaceAuthDialog` / `FaceEnrollmentDialog` if you need a reference implementation.

---

## Verifying

Gradle cannot be assumed available from every shell; build from Android Studio
(**Build → Make Project**) if `./gradlew` fails to start its daemon.

Then test, in this order — each one catches a different class of miss:

1. Create a **fresh** alarm with the new mission. Alarms saved before a codec fix are stored
   corrupt and will keep failing no matter what you change.
2. Let it ring **without touching the notification** — catches section 1 and 2 misses.
3. Confirm the Dismiss button is **absent** — catches the section 3 bypass hole.
4. Let it sit >30s before completing — catches the `startTimeout` miss.
5. Complete it, then confirm a one-time alarm **toggles off**.
6. Swipe the alarm away mid-mission and confirm it relaunches and is still usable.
7. Preview it from the alarm-creation screen — a separate code path from a real alarm.
8. If the mission has config: enrol/set it, abandon the dialog, reopen — it must show as
   **unconfigured**, not carry over. Then create a second alarm and confirm it does not
   inherit the first one's config.
9. Verify Tap still needs 108 taps and Math still behaves, i.e. no regression.

## Making this checklist unnecessary

The durable fix is an `enum class MissionType` with **no `else` branches** in the `when`s above.
A non-exhaustive `when` over an enum is a compile error, so adding a mission would produce build
errors at exactly the sites that need updating — the compiler becomes this document.

The catch: those `else` branches currently double as safety nets for corrupt persisted data, so
each needs a deliberate decision (fail loudly vs. handle explicitly). Worth adding round-trip
tests first:

```kotlin
@Test fun everyMissionSurvivesCodecRoundTrip() {
    ALL_MISSION_IDS.forEach { id ->
        val spec = SequencerMissionCodec.parseMissionToken(id)
        assertNotNull("$id dropped by codec", spec)
        val roundTripped = SequencerMissionCodec.parseMissionToken(
            SequencerMissionCodec.serializeMissionToken(spec!!)
        )
        assertEquals(id, roundTripped?.id)
    }
}
```

That single test would have caught the worst bug in section 1 immediately, instead of it
surfacing as an unrelated blank screen.
