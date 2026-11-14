# Remember Me for Android («ذكرني») — design spec 1.0.0

*FastKiwi design council, 2026-11-15. Status: **decided by the council** (owner: «اقترح توزيع الازرار الواجه الشكل العام
على المجلس دعه يقرر»), reconciled with the feature council's [docs/FEATURES.md](docs/FEATURES.md). Ready for the app agent.*
*Applies to the Android app 1.0.0 (Kotlin, Jetpack Compose, Material 3, minSdk 26, targetSdk 36, Compose BOM 2025.12.01).*

This spec re-decides the whole interface for 1.0.0; it is not a patch of 0.1. The 0.1 spec (verdicts V1–V17) is
history in git and in `design/` (v1 mockups, untouched). Where a V-verdict still holds it is named below.

Only **stable Material 3 components** already on the classpath (`androidx.compose.material3`, `foundation`), the bundled
Noto Sans Arabic, and Material Symbols Rounded drawables (Apache-2.0). No Expressive or alpha APIs, no new library
dependency, no network. Permissions are FEATURES.md's (0.1.1 + `FOREGROUND_SERVICE`); the design adds none.

| Path | What |
|---|---|
| `DESIGN-SPEC.md` | this spec |
| `design/v2/mockups/*.html` | 36 mockups (HTML/CSS, Arabic RTL; light/dark by `prefers-color-scheme`; `?fs=2` = 200 % text) |
| `design/v2/kit/kit.css`, `kit.js`, `fonts/` | the mockup kit (tokens = §2), icon stand-ins, Android 14 non-linear 200 % font table |
| `design/v2/shots/<screen>-light.png`, `-dark.png` | 72 renders at 412×915 dp, device scale 2 (824×1830 px) |
| `design/v2/shots/home-ar-200.png`, `alert-ar-200.png` | Arabic at 200 % text |
| `design/v2/shots/home-tablet-*.png` | tablet / unfolded foldable, 1280×800 dp |
| `design/v2/shots/compare-*.png` | before (`screenshots/qc/ar-light-*.png`, 0.1.x) and after, side by side (6 pairs) |
| `design/v2/tools/render.js` | Playwright renderer (Edge channel) |
| `design/v2/tools/contrast.mjs` → `contrast-report.md` | WCAG 2.2 check of every colour pair v2 uses (exit 1 on a failure); palette from `design/tools/palette.json` |

Re-render: `$env:IC_PLAYWRIGHT_DIR="E:\fast kiwi pro neq\tools\pw"; node design\v2\tools\render.js [screen …]`.
Re-check colours: `node design\v2\tools\contrast.mjs` (today: every required pair passes, light and dark).

---

## 0. What changed from 0.1 (short)

| Area | 0.1 | 1.0.0 |
|---|---|---|
| Navigation | one list, top bar [search, settings] | still **one list, no NavigationBar** (V16 kept after a re-vote, W1); top bar gains **«طريقة العرض»** (list / agenda / day / week / month); lists are **chips** above the list |
| Adding | FAB → full editor | FAB → **Quick Add sheet** with a mandatory date preview; «خيارات أكثر» → editor (W2, W3) |
| Editor | 8 equal blocks | PRD §6.2 order; date + time as two chips on one row; **several alerts** as a list; alert options in **one collapsed group «خيارات التنبيه»** with a summary line (W4) |
| Row | time hero, meta line | same, plus state/alert-style/steps tags, **swipe تم / أجّل** with Undo (W5, W6) |
| Reminder screen | 2×3 snooze grid + «تم» | pair **«أجّل 10 دقائق» · «تم»** (64 dp, 16 dp gap, 1 s tap guard) + 5 presets + «أجّل حتى…»; alert-style tag; late reason (W7, W8) |
| Allow background | permissions only | **«صحة التذكيرات»**: 5 groups incl. sound, DND, ringer, scheduling; **«اختبر التنبيه الآن»** with six results (W9, W10) |
| Settings | 5 groups | 9 groups, **searchable** (W11) |
| New screens | — | ringtone sheet, alert-style sheet, DND help, snooze sheet, templates, lists & tags, 4 calendar views, bulk selection, history, restore summary, compatibility report, what's new, prayer time, widget «تم», Quick Settings tile |
| Icons | `Icons.Rounded` (material-icons-extended) | **Material Symbols Rounded** vector drawables with explicit RTL mirroring (W19) |
| Colour, type, shape | v1 tokens | **unchanged palette and type scale** (V6, V8 kept); new component tokens only |

---

## 1. Diagnosis: 0.1 against the 1.0.0 features

Read from `screenshots/qc/` (ar/en, light/dark, 200 %) and `app/…/ui/*.kt`. 0.1 already implements the v1 spec well
(time-first rows, grouped settings, docked reminder screen); the problems are about **room for 1.0.0**, not polish.

1. **Home has no slot for organisation.** Lists/tags/saved filters (F-062/063), the four views (F-064) and bulk mode
   (F-066) have nowhere to live: the top bar holds only search and settings, and the only row gesture is the Done circle.
2. **«تذكير جديد» opens a long form** (`02-new`, `03-edit`): title, note, a switch, date, time, repeat, early notice,
   priority, category, each 64–80 dp. Adding several alerts, tone, alert style, full-screen, pin, lock-screen privacy and
   read-aloud would make the editor three screens long. PRD §6.2 asks that a new user not see them all.
3. **One early-notice row cannot hold several alerts** (F-046), and the «التصنيف» segmented row cannot hold tags.
4. **The reminder screen's only big button is «تم»** (`04-alert`); the snooze choice the notification already offers
   («أجّل 10 دقائق») is not on the screen, the grid has a disabled cell («اليوم، 6:00 م»), and there is no sign of the
   alert style or of a late arrival (F-025).
5. **«السماح بالعمل في الخلفية»** (`06-background`) checks permissions only. Health (F-008/F-023) adds ringer mode, DND,
   volumes, tones, full-screen, lock-screen, scheduling and the alert test: the screen must become a checklist with
   sources and one fix per row, and the title changes.
6. **Settings** (`05-settings`) fits on two screens today; FEATURES adds about 25 rows (tones, alert style, ring length,
   vibration pattern, full-screen, pin, lock screen, Hijri, prayer, morning summary, history, auto-backup, encryption,
   .ics import, alert log, delete all, privacy). It needs more groups and search.
7. **No undo outside the app**, no «تم» in the widget, no Quick Settings entry (F-052, F-121, F-123).
8. **Icons**: `material-icons-extended` is frozen at the old Material Icons set; several 1.0.0 symbols (checklist, mosque,
   ringtone, do-not-disturb variants) are missing or older-looking there.

---

## 2. Benchmark (patterns only)

Studied for layout and interaction patterns only; no branding, art, colours or icons copied. Sources where checked:
the Android navigation-bar guidance (3–5 top-level destinations), TickTick's customisable tab bar release note, and
reporting on Google Clock's alarm screen (buttons in the Material 3 Expressive redesign, then a user setting "Tap or
Swipe" to dismiss from version 8.5, Jan 2026). The rest is from the council's working knowledge of the current apps.

| App | Pattern noted | Taken? |
|---|---|---|
| Google Tasks | No bottom bar; lists as tabs/chips at the top; add sheet with a title field and small date chip; completed folded | **Yes**: chips for lists, sheet for adding |
| Todoist | Quick Add parses dates and **highlights the parsed part**; bottom bar Today/Upcoming/Search/Browse; swipe actions per side, configurable; Undo snackbar | Preview chip, swipe + Undo; **not** the bottom bar |
| TickTick | Customisable bottom tab bar (tasks, calendar, more); calendar day/week/month/agenda | Calendar views as **modes of Home**, not tabs |
| Microsoft To Do | Lists as the home; "Planned" grouped by Overdue/Today/Tomorrow/Later; add bar at the bottom | Sections (V1 kept) |
| Samsung Reminder | Time-first cards; one add button; categories as a grid of filters | Time hero (V2 kept) |
| Google Clock (ringing) | Time largest; Snooze and Stop at the bottom; swipe optional because taps were too easy to hit | Bottom pair, spacing and a tap guard; **no** mandatory swipe (WCAG 2.5.7) |
| Android Settings 15/16 | Grouped rows, 20/4 dp corners, search at the top | Settings and Health |

---

## 3. Design system

### 3.1 Colour (unchanged from 0.1, V6)

The complete Material 3 scheme generated from the brand seed `#2F6249` with the lavender bell `#C5B3FF` as tertiary
(`design/tools/palette.mjs` → `palette.json`, `palette.kt.txt`) is **already in `ui/Theme.kt`** and stays exactly as is.
No reason was found to change it: every 1.0.0 pair passes (`design/v2/tools/contrast-report.md`, 37 pairs per theme).

Key values (light / dark): primary `#246A4B` / `#8FD5AF`; primaryContainer `#ABF2CA` / `#005235`; secondaryContainer
`#D0E8D8` / `#364B3F`; tertiary `#645498` / `#CDBDFF`; tertiaryContainer `#E8DEFF` / `#4B3C7E`; error `#BA1A1A` /
`#FFB4AB`; errorContainer `#FFDAD6` / `#93000A`; surface `#F7FAF5` / `#101412`; surfaceContainerLow / – / High / Highest
`#F1F5EF` `#EBEFEA` `#E5E9E4` `#E0E3DE` / `#181D1A` `#1C211E` `#272B28` `#313632`; inverseSurface `#2D312E` / `#E0E3DE`;
inversePrimary `#8FD5AF` / `#246A4B`; outline `#707972` / `#8A938C`.

**Role map (what means what; always with an icon and a word, never colour alone)**

| Meaning | Role | Words + icon |
|---|---|---|
| overdue, essential missing, failed | `error` text / `errorContainer` surface | «متأخر …» + warning; «مطلوب», «لم ينجح» + error |
| ready, done, primary action | `primary` / `primaryContainer` | «جاهز», «نجح» + check |
| priority, alert style «منبّه مهم», bell | `tertiaryContainer` tag | «مهم» + flag; «منبّه» + alarm |
| snoozed, selected, nav/segment selection | `secondaryContainer` | «مؤجّل» + snooze; check in chips |
| neutral facts (alert style normal, info) | `surfaceContainerHighest` tag, `onSurfaceVariant` | «للعلم», «غير معروف» |
| recommended / restricted | `tertiary` text + info icon | «مقيّدة», «غير مفعّل» |

| Surface | Role |
|---|---|
| page | `surface` |
| rows, groups, Health rows | `surfaceContainer` |
| sheets, reminder-screen dock, tablet detail pane | `surfaceContainerLow` |
| dialogs, contextual (selection) top bar, Quick Add preview card, search field | `surfaceContainerHigh` |
| Quick Add text field | `surfaceContainerHighest` |
| snackbar | `inverseSurface` + `inversePrimary` action |

Dynamic colour stays off (V7 kept; FEATURES F-209 deferred). The notification accent stays light `primary`.

### 3.2 Type (unchanged scale, V8 rules kept)

Noto Sans Arabic 400/500/700 (bundled, OFL), letter spacing 0, no italics, no all-caps, line height in sp,
`LineHeightStyle(Center, Trim.None)`, no fixed text heights. **Western digits 0–9 only** (brain D-012), `tnum` for
times and counts; the Arabic comma «، » between items in Arabic (` · ` only in English); ranges use «إلى» (never a dash,
which reorders in RTL: «14 إلى 20 نوفمبر»). Scale as in `Theme.kt`: displayLarge 57/72 500 (reminder-screen time),
headlineMedium 28/40 700 (reminder title), headlineSmall 24/36 700 (dialog titles), titleLarge 22/32 700 (top bars, sheet
titles), `RowTime` 22/30 500 tnum, titleMedium 17/26 500, titleSmall 15/22 700 (group headers), bodyLarge 16/28,
bodyMedium 14/24, bodySmall 13/20 (never smaller for Arabic), labelLarge 15/22 500, labelMedium 13/18 500, labelSmall
12/16 500 (counts only).

New text styles (Kotlin in §7.1): `AgendaTime` 17/24 500 tnum; `DayNumber` 16/24 500 tnum; `HijriNumber` 12/16 400 tnum
(the only 12 sp text besides counts: it is a secondary numeral, always with the Gregorian number).

### 3.3 Shape, spacing, elevation (v1 tokens kept, new component tokens)

Shapes: extraSmall 4, small 8, medium 12, large 16, extraLarge 28 dp; groups 20/4 dp with 2 dp gaps; full = pills.
8 dp grid (`Dimens.xs 4, s 8, m 12, l 16, xl 24, xxl 32`). Touch targets ≥ 48 dp everywhere.

| New token | Value | Use |
|---|---|---|
| `Dimens.sheetCorner` | 28 dp (top corners) | Quick Add, ringtone, alert style, snooze, prayer sheets |
| `Dimens.rowCompact` | 56 dp | alert rows in the editor, agenda rows, sheet options |
| `Dimens.timeColumn` | 76 dp | agenda, widget |
| `Dimens.alertButton` / `alertGap` | 64 dp / 16 dp | reminder-screen pair |
| `Dimens.fabClearance` | 88 dp (kept) | list bottom padding under the FAB |
| `Dimens.listPane` | 440 dp | tablet list pane |
| `Dimens.twoPane` | 840 dp | window width from which list + detail show side by side |
| `Dimens.maxContent` | 640 dp | content max width between 600 and 839 dp |
| `SwipeThreshold` | 40 % of row width | swipe actions |
| `UndoMillis` | 10 000 ms | snackbars (= `SnackbarDuration.Long`), widget and notification undo |
| `AlertTapGuardMs` | 1 000 ms | reminder screen ignores pointer taps for this long after it appears |

Elevation: tonal only; shadows on the FAB (level 3), sheets, dialogs and menus (level 3 + scrim `#000000` at 32 %),
snackbar (level 1).

### 3.4 Icons: Material Symbols Rounded, with mirroring rules (W19)

**Family:** Material Symbols **Rounded**, weight 400, grade 0, optical size 24, fill 0 (fill 1 only for a selected or
"done" state). **Delivery:** vector drawables vendored into `res/drawable/ms_<name>.xml` from Google's
`material-design-icons` repository (Apache-2.0; the `symbols/android/<name>/materialsymbolsrounded/<name>_24px.xml`
files), drawn with `painterResource`. One family only: when this lands, every `Icons.Rounded.*` /
`Icons.AutoMirrored.*` call is replaced in the same step and `material-icons-extended` is removed from
`app/build.gradle.kts` (smaller build, no mixed strokes). Sizes: 24 dp in bars and rows, 20 dp in buttons, 18 dp in
chips, 16 dp in tags and metadata. Every icon-only button has a `contentDescription` and a long-press `PlainTooltip`
with the same text (`TooltipIconButton`, exists).

**Mirroring rule:** a symbol is mirrored in RTL **only when it shows direction or reading order**. Add
`android:autoMirrored="true"` to these drawables (and nothing else): `arrow_back`, `arrow_forward`, `chevron_left`,
`chevron_right` (row chevrons, previous/next day/week/month), `label`, `list`, `format_list_bulleted`, `checklist`,
`sort`, `notes`, `undo`, `redo`, `volume_up`, `volume_off`, `drive_file_move`, `receipt_long`, `open_in_new`, `send`.
**Never mirrored:** clocks and alarms (hands turn clockwise everywhere), `repeat`, `snooze`, `check`, `close`, `add`,
`search`, bells, `event`/`calendar_month`, `push_pin`, `vibration`, `music_note`, flags, `tag`, `mosque`,
`health_and_safety`, `do_not_disturb_on`, `visibility_off`, media `play_arrow` (media controls follow the medium, not
the text), progress. Previous/next: "previous" sits at the **start** and points to the start (right in RTL).

| Use | Symbol | Mirror |
|---|---|---|
| back / close | `arrow_back` / `close` | yes / no |
| view menu «طريقة العرض» | `calendar_view_month` | no |
| search / settings / more | `search` / `settings` / `more_vert` | no |
| new reminder, add alert, add step | `add` | no |
| Done circle → done | `radio_button_unchecked` → `check_circle` (fill 1) | no |
| overdue / warning | `warning` | no |
| repeat / snooze / «أجّل حتى…» | `repeat` / `snooze` / `event` | no |
| priority high / medium | `flag` (fill 1) / `flag` | no |
| category / tag / list / filter | `label` / `tag` / `list` / `filter_alt` | yes / no / yes / no |
| alert at time, early alerts | `notifications` | no |
| alert style normal / important | `notifications` / `alarm` | no |
| tone / built-in tones / from files / silent | `music_note` / `library_music` / `folder` / `vibration` | no |
| preview play / stop | `play_arrow` / `stop` | no |
| full-screen / pin / lock privacy / read aloud | `fullscreen` / `push_pin` / `visibility_off` / `record_voice_over` | no |
| steps (checklist) / drag handle | `checklist` / `drag_indicator` | yes / no |
| Health / test / DND | `health_and_safety` / `notifications_active` / `do_not_disturb_on` | no |
| ok / needed / info / unknown | `check_circle` / `error` / `info` / `help` | no |
| maker steps / battery | `phone_android` / `battery_alert` | no |
| check now | `refresh` | no |
| history / templates / restore / share / copy | `history` / `dashboard_customize` / `settings_backup_restore` / `share` / `content_copy` | no |
| selection: select all / move / delete | `select_all` / `drive_file_move` / `delete` | no / yes / no |
| prayer / Hijri / morning summary | `mosque` / `calendar_month` / `wb_twilight` | no |
| voice input | `mic` | no |

The mockups use hand-drawn stand-ins in the same style; the app uses the symbols above.

### 3.5 Motion (reduced motion respected)

M3 standard easing; durations are scaled by the system animator scale automatically.

| Transition | Spec | Reduced motion (`LocalReducedMotion`, exists) |
|---|---|---|
| Screen push/pop | shared-axis X, 300 ms in / 200 ms out, direction follows layout direction; predictive back on | crossfade 150 ms |
| Sheet open/close | `ModalBottomSheet` default | default (system already shortens) |
| Home ↔ calendar view | crossfade 200 ms (views are modes, not pages) | instant |
| Row Done / swipe away | circle fills 150 ms, haptic `CONFIRM`, `animateItem()` 250 ms | `snap()` |
| Swipe background | icon scales 0.8 → 1 when the threshold is crossed (state change, not decoration) | no scale |
| Reminder screen in | fade + scale 0.92 → 1, 250 ms; nothing loops, pulses or flashes (WCAG 2.3.1) | fade only |
| FAB | label collapses while scrolling down | same (it is a state, not an animation) |

### 3.6 Large text (200 %) rules

Android 14+ non-linear scaling (8→16, 14→26, 18→30, 24→36, 30→38 sp…); icons do not scale. Shots: `home-ar-200.png`,
`alert-ar-200.png`.
- Paired buttons are a `FlowRow`; when they wrap, **the primary goes on top** (`alert-ar-200`: «تم» above «أجّل …»).
- At font scale ≥ 1.5: a `SingleChoiceSegmentedButtonRow` that would truncate becomes a single-choice dialog row
  (calendar views, priority); chips rows scroll horizontally (never wrap Home's chips); metadata lines wrap.
- The reminder-screen dock never scrolls; the hero above it scrolls. «تم» is always fully visible (blocking defect).
- Top bars keep the title on one line with ellipsis; the full title is the `paneTitle`.

### 3.7 Adaptive layouts (cheap, no new dependency)

Read `LocalConfiguration.current.screenWidthDp` (or `BoxWithConstraints`); no `material3-adaptive` artifact.
- **< 600 dp**: the phone layouts below.
- **600–839 dp** (small tablets, foldables half-open, landscape phones): same single pane, content centred with
  `widthIn(max = Dimens.maxContent)`; sheets become centred dialogs of 560 dp max width.
- **≥ 840 dp** (`home-tablet-*.png`): **list pane 440 dp at the start + detail pane** (`surfaceContainerLow`, 28 dp
  corners, 16 dp margin). Selecting a row shows its details on the end pane with «أجّل» (S) · «تم» (P) at the pane's
  bottom end and edit/⋮ at its top; editing opens the editor in that pane. The FAB «تذكير جديد» sits at the bottom of the
  list pane. No NavigationRail (there are no destinations to rail). The reminder screen stays full screen on all sizes.

---

## 4. Navigation and global button rules

**Model (W1): one Home list, pushed screens, sheets; no NavigationBar, no NavigationRail, no drawer.**

```
Home (list)  ── top bar: [brand «ذكرني»] … [طريقة العرض ▾] [بحث] [الإعدادات]
 │  views (same screen, top-bar menu): القائمة · أجندة · يوم · أسبوع · شهر   (Back from a view → the list)
 │  chips (only with 2+ lists or a saved filter): الكل · مهم · <قوائمي> · <فلاتر> · «القوائم…» → Lists & tags
 ├─ FAB «تذكير جديد» → Quick Add sheet ─ «خيارات أكثر» → Editor ─ ⋮ السجل / حفظ كقالب / نسخ / حذف
 │                                     └ «من قالب» → Templates → Editor (prefilled)
 ├─ row tap → Editor (or the reminder screen when due/overdue);  long-press → Bulk selection
 ├─ swipe start→end «تم» (Undo) · swipe end→start «أجّل» → Snooze sheet
 └─ Settings ─ «صحة التذكيرات» (Health) ─ «اختبر التنبيه الآن» (Alert test) · DND help · maker steps
             ├ groups (§5.14) · search
             └ «القوائم والوسوم», «القوالب», backups → Restore summary, «تقرير التوافق», trash, about
Reminder screen (full-screen intent or a row tap when due) ─ Snooze presets · «أجّل حتى…» · edit · «لاحقًا»
Outside the app: notification (تم / أجّل 10 دقائق / أجّل حتى…), widget («تم», +), Quick Settings tile, launcher shortcuts
```

**Global rules (kept from 0.1 unless noted)**
- **Exactly one primary (filled) action per screen**, in the bottom thumb zone. On Home it is the FAB.
- **Paired buttons: secondary first (start = right in RTL), primary last (end)**, each `weight(1f)`; they stack with the
  primary on top when they do not fit. A lone primary is full width.
- **Dialogs: dismiss first, confirm last**; destructive confirms in `error`.
- **Bottom action bars: labelled, at most 5 items**, primary last (bulk mode is the only 5-item bar).
- Destructive actions live in ⋮ menus or bulk bars, never beside a primary; they are undoable (snackbar 10 s) or
  confirmed (dialog) — confirm when it cannot be undone **or** when it touches 10+ items (W16).
- Sheets are for choices that apply to the current thing (tone, style, snooze, Quick Add); pushed screens for places
  (Settings, Health, Lists); dialogs for a single decision.

---

## 5. Screens

Legend: **P** primary, **S** secondary, **T** tertiary. Positions are for RTL (start = right, end = left); LTR mirrors.
Each screen lists its shots in `design/v2/shots/`.

### 5.1 Home — `home-*.png`, `home-ar-200.png`, `compare-home.png`

| Level | Action | Position | Component |
|---|---|---|---|
| P | **«تذكير جديد»** | bottom centre, 16 dp above the navigation-bar inset | `ExtendedFloatingActionButton(containerColor = primary)`, `FabPosition.Center`, icon `add`; collapses on scroll down; opens Quick Add (§5.3) |
| S | Done (per row) | row start | 48 dp `toggleable(role = Checkbox)`, cd «تم: <title>» (unchanged) |
| S | «طريقة العرض» | top bar end, first | `TooltipIconButton(calendar_view_month)` → `DropdownMenu` with 5 radio items «القائمة»، «أجندة»، «يوم»، «أسبوع»، «شهر» |
| S | search, settings | top bar end | unchanged (cd «بحث», «الإعدادات») |
| S | quick filters | chips row under the top bar | `FilterChip`s in a `LazyRow`: «الكل» (selected by default), «مهم», each list (saved tag filter), each pinned saved filter, then an `AssistChip` «القوائم…» (`list` icon) → Lists & tags. **Shown only when the user has 2+ lists or 1+ saved filter** (F-063: a one-list user sees Home unchanged) |
| T | swipe | row | §5.2 |
| T | long-press | row | enters bulk selection (§5.17); also TalkBack custom action «تحديد» |
| T | health banner | under the top bar | `StatusBanner` (unchanged): «قد لا تصل التذكيرات في وقتها.» + «إصلاح» → Health |

Rows (V2 kept; `ReminderRow`): Done target · line 1 time (`RowTime`) + label · line 2 title (bodyLarge, 2 lines) ·
line 3 metadata, 16 dp icons, `FlowRow`, in this order and only when present:
1. repeat (`repeat` + text);
2. **state tag** «مؤجّل» (`snooze`, `secondaryContainer`) on line 1 after the time when snoozed (the time shown is the
   snoozed time); «وصل متأخرًا» stays in the Overdue section wording;
3. priority tag «مهم» (`flag` fill, `tertiaryContainer`) / «متوسط» (outlined);
4. **alert style tag «منبّه»** (`alarm`, neutral `surfaceContainerHighest`) only for «منبّه مهم» (the normal style shows
   nothing); its contentDescription says «منبّه مهم» in full;
5. category `label` + name only when not «شخصي»; tags only inside search results and filters;
6. **steps progress** «2 من 5» (`checklist`) when the reminder has steps;
7. tone only when it is silent: «اهتزاز فقط» (`vibration`); several alerts: «تنبيهان» / «3 تنبيهات» (`notifications`).

The row's merged contentDescription joins: title، time، late/snoozed، repeat، priority، «منبّه مهم»، steps, with «، »;
custom actions «تم»، «أجّل»، «تحديد». Sections, empty state and «المنجزة» fold are unchanged (V1).

### 5.2 Swipe actions and Undo — `home-swipe-*.png`

`SwipeToDismissBox` per row (directions follow `LayoutDirection` automatically):

| Direction | In RTL the finger moves | Background (revealed at) | Effect |
|---|---|---|---|
| `StartToEnd` | right → left | `primary`, `check` + «تم» (start/right edge) | completes at once; haptic `CONFIRM`; snackbar «أُنجز «<title>»» + «تراجع» 10 s |
| `EndToStart` | left → right | `tertiaryContainer`, «أجّل» + `snooze` (end/left edge) | the row springs back and the **Snooze sheet** opens (§5.9); nothing changes until a choice |

Threshold 40 % (`positionalThreshold`), velocity default. Done rows and selection mode have no swipe. Settings › العرض ›
«السحب على التذكيرات» (switch, on) turns swiping off (X's condition, W6). TalkBack: the same two custom actions.
Undo restores exactly (F-052: a repeat returns to the completed occurrence).

### 5.3 Quick Add — `quickadd-*.png`, `quickadd-ask-*.png`, `compare-quickadd.png`

`ModalBottomSheet` (`surfaceContainerLow`, `skipPartiallyExpanded = true`), opened by the FAB, the launcher shortcut
«إضافة سريعة», the widget «+», the Quick Settings tile and Android share (F-049). It sits directly above the keyboard
(`imePadding`), the text field focused.

| Level | Action | Position | Component |
|---|---|---|---|
| P | **«حفظ»** | sheet bottom, end | `Button`; **disabled while the parser asks** (ambiguity) |
| S | «خيارات أكثر» | sheet bottom, start | `OutlinedButton` → the editor with title, date and repeat carried over |
| S | voice | inside the field, end | `IconButton(mic)`, cd «إدخال صوتي» (F-048; first use shows the network disclosure) |
| S | drop the date | on the preview card, end | `IconButton(close)`, cd «بلا موعد» |
| T | chips | under the preview | `AssistChip`s «من قالب» (→ Templates), «التصنيف: شخصي» (menu), «الأهمية» (menu) |

- Field: filled `TextField`, `surfaceContainerHighest`, label «اكتب التذكير وموعده», 1–3 lines.
- **Preview card (always visible before «حفظ»)**, `surfaceContainerHigh`, 16 dp corners, updated as you type (debounce
  150 ms): `event` + the parsed moment in bold «اليوم، 5:20 م» + the matched phrase muted «بعد ساعة» + repeat if any;
  under it «العنوان: أتصل بأمي» (the leading «ذكرني» is dropped by the parser). Nothing understood: `info` +
  «لم أفهم موعدًا. سيُحفظ النص عنوانًا بلا موعد.»
- **Ambiguity** (`quickadd-ask`): `info` in `tertiary` (not a warning) + the question («الساعة 9 صباحًا أم مساءً؟»,
  «هذا الأسبوع أم القادم؟», «أي ساعة؟», «هذا الوقت مضى. التالي؟») and **at most 4 dated choice chips** (40 dp,
  `FilterChip`, `event` icon, full date and time «الخميس 19 نوفمبر، 9:00 ص») plus «بلا موعد». The hint
  «اختر موعدًا أو «بلا موعد»» sits above the buttons; «حفظ» enables once a chip is chosen. **Nothing is ever saved with a
  date the user has not seen** (ROADMAP §4; UI test).
- After «حفظ»: the sheet closes, snackbar «أُضيف التذكير» + «تراجع» (10 s), the new row is scrolled into view.

### 5.4 Templates — `templates-*.png`

Pushed screen «من قالب» (back + title). Hint «القالب يملأ الحقول فقط. لا يُحفظ شيء قبل «حفظ».» Groups of rows (40 dp
`tertiaryContainer` icon disc, title, supporting = what it fills, chevron): **مناسبات** (عيد ميلاد «كل سنة، تنبيه قبل
يوم»؛ ذكرى سنوية «كل سنة، تنبيه قبل أسبوع»)، **الفواتير والمواعيد** (دفع فاتورة شهرية «كل شهر، تنبيه قبل يومين»؛ موعد
طبي «تنبيه قبل يوم وقبل ساعتين»؛ تجديد وثيقة «تنبيه قبل 30 يومًا»)، **الروتين** (دواء يومي «كل يوم، منبّه مهم»؛ صيانة
دورية «كل 6 أشهر»؛ متابعة أسبوعية «كل أسبوع»)، and **قوالبي** (the user's, from editor ⋮ «حفظ كقالب»; long-press →
rename/delete). No primary button: a tap opens the editor prefilled (F-047).

### 5.5 Editor — `edit-*.png` (top), `edit-more-*.png` (scrolled, options folded), `edit-advanced-*.png`, `compare-edit.png`

| Level | Action | Position | Component |
|---|---|---|---|
| P | **«حفظ»** | bottom, full width, 56 dp, above the keyboard | `BottomActionBar { Button }` (unchanged, V9) |
| S | close | top bar start | asks «تجاهل التغييرات؟» when changed (unchanged) |
| T | ⋮ (edit mode) | top bar end | «السجل» (when history is on), «حفظ كقالب», «نسخ», «حذف» (trash + Undo on Home) |

Content in **PRD §6.2 order** (16 dp margins, groups 20/4 dp, group headers titleSmall `primary`):
1. **«العنوان»** `OutlinedTextField` (unchanged label; error under it).
2. **«الموعد»**: one row: `event` + two tonal chips **[date «الاثنين 16 نوفمبر»] [time «10:30 ص»]** (each opens the
   stock `DatePickerDialog` / `TimePicker` dialog, or the prayer time sheet §5.24 when prayer times are approved and set
   up) + an × `IconButton` cd «بلا موعد». Without a date: one row «أضف موعدًا» (`add`). This replaces 0.1's switch row
   and two dialog rows (−128 dp). Hijri on: the date chip's supporting line shows «4 جمادى الآخرة 1448».
3. **«التكرار»** row → single-choice dialog (unchanged, with weekday toggles and steppers; «كل سنة هجرية» added when
   Hijri is on).
4. **«التنبيهات»**: a list: «عند الموعد» + up to **3 early alerts** («قبل يوم»، «قبل ساعتين» …), each a 56 dp row with
   the computed moment as supporting text («الأحد 15 نوفمبر، 10:30 ص») and a trailing `Switch` (on/off); tapping the row
   opens a dialog to change the offset or «حذف التنبيه». Last row «إضافة تنبيه» (`add`, `primary` text), hidden at 3.
5. **«التنظيم»**: «التصنيف» row (the fixed three: شخصي / عمل / دراسة → single-choice dialog) and «الوسوم» row (value =
   tags joined with «، » → a sheet with chips, an add field and the user's lists marked).
6. **«الأهمية»** `SingleChoiceSegmentedButtonRow` عادي / متوسط / مهم (unchanged).
7. **«الملاحظة والخطوات»**: the note field (2–8 lines) and the steps row «الخطوات» «0 من 2» → the steps section
   (§5.18) + «أضف خطوة».
8. **«خيارات التنبيه»** — **collapsed by default** (a new user sees the 0.1 editor): a 72 dp card row (`settings` icon,
   title, **summary line of the current values**, e.g. «تذكير عادي، النغمة الافتراضية، الشاشة الكاملة حسب الإعداد»,
   `expand_more`; `Role.Button`, stateDescription «مطويّة / ظاهرة»). Expanded (`edit-advanced`), rows:
   «نوع التنبيه» (→ §5.7) · «النغمة» (→ §5.6) · «الاهتزاز» (switch) · «الشاشة الكاملة» «حسب الإعداد العام» + «دائمًا»
   (→ §5.7) · «تثبيت التذكير على شاشة القفل» (switch, «يبقى ظاهرًا حتى تتعامل معه») · «اقرأ العنوان بصوت» (switch, «لا
   يُقرأ في الصامت ولا في «عدم الإزعاج»», F-031) · «على شاشة القفل» «حسب الإعداد العام / أظهر التفاصيل / أخفِ التفاصيل».
   The group opens by itself when any value differs from the defaults, so nothing is hidden silently.

### 5.6 Ringtone sheet — `ringtone-*.png`

Sheet «النغمة» + «لهذا التذكير» (Settings uses the same sheet for «نغمة التذكير الافتراضية» / «نغمة التذكيرات المهمة»).
Radio rows (whole row `selectable`, 56 dp; trailing 48 dp preview `IconButton` play/stop, cd «استمع» / «أوقف», plays with
the style's audio usage at the current volume, stops on sheet close):
«النغمة الافتراضية» (s: the current default) · «نغمات ذكرني» (3 bundled; opens a nested list) · «من نغمات الهاتف…»
(`RingtoneManager.ACTION_RINGTONE_PICKER`, F-014 extras) · «من ملفاتي…» (`ACTION_OPEN_DOCUMENT audio/*`; the chosen
file's name, «نسخة محفوظة داخل التطبيق») · «صامت (اهتزاز فقط)» (`vibration`). Honest note (`info`, muted): «في «تذكير
عادي» يتبع الصوت مستوى صوت الإشعارات؛ إن كان الهاتف صامتًا سيهتز فقط.» Actions: «إلغاء» (S) · «تم» (P).
Errors (file too big, > 10 tones, cannot play) appear as supporting text in `error` under the row, never a toast.

### 5.7 Alert style and full-screen — `alert-type-*.png`, `fullscreen-*.png`

**Sheet «طريقة التنبيه»** (per reminder): section «نوع التنبيه» with two selectable cards (whole card `selectable`,
`Role.RadioButton`; selected = 2 dp `primary` border + radio): **«تذكير عادي»** (`notifications`) «إشعار بصوت
الإشعارات. إن كان الهاتف صامتًا يهتز فقط، وفي «عدم الإزعاج» يبقى صامتًا.» / **«منبّه مهم»** (`alarm`) «يرنّ بصوت
المنبّه حتى والهاتف صامت، ويستمر حتى تتعامل معه. لا يتجاوز «عدم الإزعاج» إلا إذا سمحت له.» + `TextButton`
«السماح في «عدم الإزعاج»» (→ §5.13). Section «الشاشة الكاملة»: radios «حسب الإعداد العام (دائمًا)» / «اعرض الشاشة
الكاملة» / «إشعار فقط». Actions «إلغاء» · «تم».

**Settings › «الشاشة الكاملة»** (`fullscreen-*.png`): single-choice dialog «دائمًا» (default, F-020) / «للتذكيرات المهمة
فقط» / «مطفأة، إشعار عادي فقط», supporting text «إن لم يسمح أندرويد بعرضها، يصلك إشعار عادي.», «إلغاء».

The app never requests DND access and never bypasses DND (F-015); the design has no control that implies it can.

### 5.8 Reminder screen — `alert-*.png`, `alert-ar-200.png`, `compare-alert.png`

Full screen over the lock screen (`showWhenLocked`, `turnScreenOn`), `surface`, `safeDrawingPadding()`.

| Level | Action | Position | Component |
|---|---|---|---|
| P | **«تم»** | dock bottom row, **end**, 64 dp | `Button`, `check` 24 dp, 20 sp/700 |
| S | **«أجّل 10 دقائق»** (= the first snooze preset, its label from the preset) | dock bottom row, **start**, 64 dp, **16 dp gap** | `FilledTonalButton`, `snooze` |
| S | five more presets + «أجّل حتى…» | dock, above the pair, **3 × 2 grid**, 48 dp | `OutlinedButton`s; a preset that is not possible now is replaced by the next one (no disabled cells); «أجّل حتى…» → date then time |
| T | «تخطَّ هذه المرة» (repeats only) | dock, between grid and pair, centred | `TextButton` |
| T | edit | top row end | `TooltipIconButton(edit)` cd «فتح التذكير للتعديل» |
| T | «لاحقًا» | top row end, last | `TextButton` (the pinned notification stays) |
| T | queue «1 من 3» | top row start | pill, `liveRegion = Polite` |

- **Hero** (scrolls): 64 dp `tertiaryContainer` disc with the style icon (`alarm` or `notifications_active`); for
  «منبّه مهم» a tag «منبّه مهم» under it; **the time in displayLarge**; then «حان الوقت» or `warning` + «اليوم، متأخر
  ساعتين» (`error`) or, when the post came > 5 min late, «وصل متأخرًا 12 دقيقة» + reason when known («كان الهاتف
  مطفأ») (F-025); the title (headlineMedium, `paneTitle`, `liveRegion`); repeat + priority; the note card; steps
  progress «2 من 5» when present (tap → the steps list, ticking works here).
- **Accidental taps (W7):** tap buttons, no slide and no long-press (WCAG 2.5.7 needs a single-pointer alternative,
  2.5.1 path gestures; long-press is undiscoverable). Protection instead: (1) **pointer taps are ignored for 1 s after the
  screen appears** (`AlertTapGuardMs`; accessibility actions are never blocked); (2) the two big buttons are 64 dp with a
  16 dp gap and different weights (tonal vs filled), Snooze at the start where a resting right thumb lands, Done at the
  end; (3) no action anywhere in the hero; (4) Undo for «تم» via the quiet notification «أُنجز — تراجع» (F-052).
- Ringing: the **volume keys stop the sound** without dismissing (F-016); the screen states nothing about it unless the
  sound is playing, then a muted line «اضغط زر الصوت لإسكات الرنين» under the title.
- Before unlock after a reboot (F-026): title «لديك تذكير، افتح القفل لرؤيته», no note, same dock.
- Privacy «أخفِ التفاصيل» on the lock screen: title «لديك تذكير» until unlock (unchanged behaviour).
- After 3 snoozes of the same occurrence (F-045): a muted line above the dock «أجّلته 3 مرات. اختر وقتًا أنسب؟» (no
  automatic change).

### 5.9 Snooze sheet — `snooze-*.png`

Opened by swipe «أجّل», bulk «أجّل», and the notification's «أجّل حتى…» (which opens the picker directly). Title «أجّل»
+ the reminder title. Rows (tap applies at once, the sheet closes, snackbar «أُجّل حتى 4:30 م» + «تراجع»): the six
presets, each **with the resulting time at the end** («5 دقائق» … «حتى 4:25 م»; «غدًا في الوقت نفسه» «الأحد 2:00 م»),
a divider, «أجّل حتى…» (`event`). A muted line `repeat` + «التكرار لا يتغيّر». «إلغاء» `TextButton` at the end. No
filled button (choices apply on tap).

### 5.10 Notifications and lock screen — `notification-*.png`, `notification-lock-*.png`

- Due notification (pinned by default, F-021, small icon `ic_stat_bell`, accent light `primary`): title = reminder
  title; text «حان الوقت، 2:00 م، كل يوم» joined with «، »; BigText adds the note. **Actions in order: «تم»، «أجّل 10
  دقائق»، «أجّل حتى…»** (F-044). Header shows a pin icon when pinned. «منبّه مهم» uses the `due_alarm_v1` channel,
  header «منبّه», text «5:30 م، يرنّ حتى تتعامل معه».
- After «تم» from the notification: a quiet notification «أُنجز «دواء الضغط»» with «تراجع», `setTimeoutAfter(10 s)`.
- Lock screen «أخفِ التفاصيل»: `publicVersion` title **«لديك تذكير»**, text «افتح القفل لرؤيته», **with the same
  actions** (F-022). «أظهر التفاصيل»: the full card.
- Morning summary (opt-in, F-030): `summary_v1`, title «ملخص الصباح», text «لديك 4 تذكيرات اليوم», BigText «أولها
  5:30 م: اجتماع الفريق» (titles only when details are shown on the lock screen); action «افتح».

### 5.11 Health — `health-*.png`, `compare-health.png`

Pushed «صحة التذكيرات» (replaces «السماح بالعمل في الخلفية»), reached from Settings' top row and the Home banner.
Top bar: back, title, ⋮ («تقرير التوافق»، «سجل التنبيهات»).

1. **Summary card** (`errorContainer` when any essential is missing, otherwise `primaryContainer`): icon + plural
   «إذن ضروري واحد غير مفعّل» / «كل شيء ضروري جاهز» + «قد لا تصل التذكيرات في وقتها.» + muted «آخر فحص: 4:18 م» +
   `OutlinedButton` «افحص الآن» (`refresh`; runs the engine and re-checks; result announced politely).
2. **Groups** (header + count at the end «2 من 3 جاهز»):
   - **ضروري**: الإشعارات · المنبّهات في وقتها (s: which permission) · صوت قناة التذكيرات · (when FGS failed) تشغيل النغمة.
   - **يُنصح به**: الشاشة الكاملة · البطارية · التشغيل بعد إعادة التشغيل · شاشة القفل (what the phone shows).
   - **الهاتف الآن** (facts, never red): وضع الرنين («الهاتف صامت» + «التذكير العادي سيهتز فقط. المنبّه المهم يرنّ.») ·
     عدم الإزعاج («مطفأ» / «مفعّل، يسمح بالمنبّهات» / «غير معروف») · صوت المنبّه / صوت الإشعارات (warning at 0) ·
     النغمات (missing or fallback in the last 7 days).
   - **الشركة المصنعة**: one row per maker step, labelled «جُرِّب على هاتف» / «لم يُجرَّب على هاتف بعد» (F-013).
   - **الجدولة**: key/values «مجدولة: 12»، «تحتاج معالجة: 1»، «آخر تأخير مُقاس: 4 ثوانٍ» (from the local logs).
3. **Row anatomy**: 24 dp status icon (`check_circle` / `error` / `info` / `help`) · headline 16/500 with the **status
   word at the end** («جاهز»، «مطلوب»، «مقيّدة»، «غير مفعّل»، «للعلم»، «غير معروف»; also in the icon's cd) · supporting
   text · when not ready: a `.src` label «أندرويد» or «الشركة المصنعة» at the start and **one** action at the end:
   essential → filled `Button(containerColor = error)` «إصلاح»; recommended → `FilledTonalButton` «اسمح»; maker/tips →
   `OutlinedButton` «افتح الإعدادات». **Unknown is never green** (F-023).
4. **Bottom bar**: «اختبر التنبيه الآن» full width — `OutlinedButton` while an essential is missing (the row's
   «إصلاح» is the screen's primary), filled `Button` when all essentials are ready.

Re-check on `ON_RESUME`; announce changes with `liveRegion = Polite`.

### 5.12 Alert test — `alert-test-*.png`

Start (a sheet before the run, F-027): «نوع التنبيه» (عادي / مهم) and «متى» («بعد 10 ثوانٍ»، «بعد ساعة»، «غدًا 7:00
ص»), «ابدأ الاختبار» (P). Running: a card «سيصل تنبيه تجريبي بعد 10 ثوانٍ. اقفل الشاشة إن أردت اختبار الشاشة
الكاملة.» with a countdown («8 ثوانٍ»), `liveRegion`. **Results** (the mockup):
- card «انتهى الاختبار» + «4 نجحت، 1 لم ينجح، 1 ينتظر جوابك» + muted «اختبرتَ: تذكير عادي، بعد 10 ثوانٍ»;
- header «الأجزاء الستة», six rows, each status icon + word: **المنبّه في وقته** («وصل بعد 10 ثوانٍ، متأخرًا ثانية
  واحدة»), **الإشعار** («ظهر مع زرّي «تم» و«أجّل»»), **الصوت** (objective sub-check «بدأ تشغيل الصوت» + the user's
  answer: «هل سمعت الصوت؟» with two equal `OutlinedButton`s «لا» · «نعم»; honest line «الهاتف صامت: التذكير العادي سيهتز
  فقط.»), **الاهتزاز** («أجبتَ: نعم»), **الشاشة الكاملة** (fail: «أندرويد لم يسمح بعرضها فوق شاشة القفل» + src «أندرويد» +
  tonal «اسمح»), **الأزرار** («ضغطتَ «تم» في الإشعار»);
- muted «الاختبار لا يحفظ أي تذكير.»; bottom pair «أعد الاختبار» (S) · «تم» (P).
Never a single "success": with any fail or unknown the card says so.

### 5.13 DND help — `dnd-help-*.png`

Pushed «المنبّه المهم وعدم الإزعاج». Status card: «عدم الإزعاج الآن: مفعّل» and «ذكرني: غير مسموح له» (`error` icon +
word) or «مسموح» / «غير معروف». Intro «ذكرني لا يتجاوز «عدم الإزعاج» من تلقاء نفسه. إن أردت أن يرنّ المنبّه المهم
أثناءه، اسمح له من إعدادات الهاتف:». Numbered steps (28 dp `secondaryContainer` discs): 1 «افتح إعدادات «عدم الإزعاج»»،
2 «في «التطبيقات» أو «الاستثناءات» أضف «ذكرني»»، 3 «تأكّد أن «المنبّهات» مسموحة». Muted «تختلف الأسماء حسب الشركة
المصنعة للهاتف.» **P**: full-width «افتح إعدادات عدم الإزعاج» (`ACTION_ZEN_MODE_PRIORITY_SETTINGS`, fallback app details).

### 5.14 Settings and search — `settings-*.png`, `settings-search-*.png`, `compare-settings.png`

Grouped rows (V13 kept): 24 dp icon, headline 16/500, value as supporting text, trailing switch for booleans; choices
in single-choice dialogs; changes apply at once; no primary. Top bar: back, «الإعدادات», **search** (`search`).

| Group | Rows (default value) |
|---|---|
| (top, alone) | **«صحة التذكيرات»** — «كل الأذونات الضرورية جاهزة» (`check_circle`) or the plural need + `error`; chevron |
| **التنبيه** | نغمة التذكير الافتراضية «نغمة الهاتف للإشعارات» · نغمة التذكيرات المهمة «نغمة المنبّه في الهاتف» · التذكيرات المهمة «منبّه مهم» (s «يرنّ حتى والهاتف صامت») · الشاشة الكاملة «دائمًا» · مدة رنين المنبّه المهم «دقيقة» · خيارات التأجيل «5، 10، 15، 30 دقيقة، ساعة، غدًا» · إعادة التنبيه بالصوت «كل 5 دقائق، حتى 3 مرات» · الاهتزاز «عادي» (قصير / عادي / طويل / مطفأ) |
| **شاشة القفل** | على شاشة القفل «أظهر التفاصيل» · تثبيت التذكير على شاشة القفل (on) |
| **العرض** | اللغة · السمة · أسماء الأشهر · أول يوم في الأسبوع · التاريخ الهجري (off) + تعديل التاريخ الهجري (−2…+2, only when on) · السحب على التذكيرات (on) |
| **الصلاة** (if prayer reminders are used or being set up) | الطريقة (none until chosen) · المدينة |
| **ملخص الصباح** | ملخص الصباح (off) · الوقت «8:00 ص» |
| **التنظيم** | القوائم والوسوم · القوالب · سجل التغييرات (off) + «مسح السجل» · إكمال التذكير عند إنهاء كل الخطوات (off) |
| **بياناتك** | حفظ نسخة · نسخة تلقائية أسبوعية · تشفير النسخة · استعادة من ملف · نسخ الأمان · استيراد تقويم (.ics) · تصدير تقويم (.ics) · تقرير التوافق · سجل التنبيهات · سلة المحذوفات · حذف كل البيانات (`error` text) |
| **حول** | الخصوصية · التراخيص · حول ذكرني «الإصدار 1.0.0» |

**Search** (`settings-search`): the top bar becomes a search field («ابحث في الإعدادات», back at start, clear × at end).
Matches the row title, its value, its group and a small synonym list per row (e.g. «صوت» → tones, remind-again, channel
sound, «الهاتف صامت», alert-test sound). Results: count header «6 نتائج», rows with the match in bold `primary` and
supporting «في: التنبيه» / «في: صحة التذكيرات»; a tap opens the row's own control (dialog, switch, or the screen,
scrolled to the row and highlighted 1 s). Health rows are searchable too. Empty: «لا نتائج لـ«…»».

### 5.15 Lists and tags — `lists-*.png`

Pushed «القوائم والوسوم» (back, ⋮ «ترتيب») from the Home chip «القوائم…» and Settings › التنظيم. F-063: a **list is a saved
filter on a tag**; the three categories are fixed and shared with Windows.

| Section | Rows |
|---|---|
| **التصنيفات** («مشتركة مع ذكرني لويندوز») | شخصي 12 · عمل 5 · دراسة 2 (`label`, trailing count; tap = filter Home) |
| **قوائمي** (+ «ترتيب») | المنزل «الوسم: منزل» 3 · السفر «الوسم: سفر» 1 (`list`; drag handles in reorder mode; ⋮ rename / remove — removing a list never removes the tag) |
| **الوسوم** | chips with counts (عائلة 4، فواتير 3 …); long-press → rename / delete tag (confirm: «حذف الوسم من 4 تذكيرات؟») |
| **الفلاتر المحفوظة** | أولوية عالية · المتأخرة · هذا الأسبوع · بلا وسم + «فلتر جديد…» (`primary`) |
| **المنجزة والسلة** | المنجزة 130 · سلة المحذوفات 3 |

**P**: FAB «قائمة جديدة» (dialog: name + tag picker; «إلغاء» · «إنشاء»).

### 5.16 Calendar views — `cal-agenda-*.png`, `cal-day-*.png`, `cal-week-*.png`, `cal-month-*.png`

Modes of Home (F-064), chosen from «طريقة العرض»; **Home always opens in the list**; system Back from a view returns to
the list. Top bar: title = the period («نوفمبر 2026»), actions `TextButton` «اليوم» (jump to today), «طريقة العرض» (shown
active on `secondaryContainer`), «الإعدادات». Under it a full-width `SingleChoiceSegmentedButtonRow` «أجندة | يوم | أسبوع |
شهر» (≥ 1.5 font scale: a dialog row). **P**: FAB «تذكير جديد», prefilled with the selected day. Rows keep swipe and the
Done target.

- **Agenda**: continuous from today; date headers «اليوم، السبت 14 نوفمبر» (today in `primary`), «غدًا، الأحد 15 نوفمبر»…;
  days without reminders skipped; 56 dp rows: 76 dp time column (`AgendaTime`; «بلا وقت» for untimed) + title + meta;
  overdue rows `warning` + «متأخر ساعتين».
- **Day**: previous/next `IconButton`s (cd «اليوم السابق» / «اليوم التالي»; chevrons mirror) around «السبت 14 نوفمبر» +
  muted Hijri «4 جمادى الآخرة 1448» when on; a «بلا وقت» strip; an hour timeline (60 dp per hour, 56 dp hour-label
  column at the start, `outlineVariant` lines) with blocks (`surfaceContainerHigh`, 8 dp, time + title; overdue block
  `warning` + «متأخر»; snoozed `snooze` + «مؤجّل»); a now-line in `primary` labelled «الآن 4:20 م».
- **Week**: previous/next week around «14 إلى 20 نوفمبر»; 7 day rows (start column: day name + date number; today on a
  `primaryContainer` disc + the word «اليوم»), up to 3 lines «5:30 م اجتماع الفريق» then «+2 أخرى»; empty «لا شيء».
- **Month**: narrow weekday letters from the week-start setting (س ح ن ث ر خ ج; first at the start); 6 × 7 grid, cells
  ≥ 48 dp: Gregorian number (`DayNumber`), Hijri number under it (`HijriNumber`, only when on), and **a count pill with the
  number** (`secondaryContainer`, labelSmall) when the day has reminders — never a dot; past days show no count; days of
  other months at 60 % opacity; today = filled `primary` circle; selected = 2 dp `primary` outline. Below: «السبت 14
  نوفمبر، 5 تذكيرات» and that day's rows. Cell cd: «السبت 14 نوفمبر، 5 تذكيرات، اليوم».

### 5.17 Bulk selection — `bulk-*.png`, `bulk-confirm-*.png`

Long-press a row (or TalkBack action «تحديد») → selection mode. Contextual top bar (`surfaceContainerHigh`): close
(start, cd «إلغاء التحديد»), «3 محددة» (`liveRegion`), `select_all` (cd «تحديد الكل»), ⋮ «تصدير المحدد». Rows show a
selection ring in place of the Done circle (selected rows `secondaryContainer` + filled check); FAB, chips and swipe are
hidden. **Bottom action bar, 5 labelled items, primary last**: «حذف» · «الأهمية» · «نقل» (to a category or a list's tag) ·
«أجّل» (snooze sheet) · **«تم»** (filled pill). Fewer than 10 items: applies at once + snackbar «أُنجز 3 تذكيرات» /
«نُقلت 3 تذكيرات إلى السلة» + «تراجع». **10 or more** (or anything irreversible): `AlertDialog` first, e.g. «نقل 12
تذكيرًا إلى السلة؟» / «تبقى في السلة 30 يومًا ويمكنك استعادتها. 4 منها متكررة: تتوقف سلاسلها.» with «إلغاء» · «نقل
إلى السلة» (`error`). Back exits selection.

### 5.18 Steps (subtasks) — `subtasks-*.png`

In the editor (and tickable from the reminder screen and the tablet detail pane): header «الخطوات» with «2 من 5» at the
end and a `LinearProgressIndicator` (determinate, `primary` on `surfaceContainerHighest`); rows: 48 dp `Checkbox` · text
(done = `onSurfaceVariant` + strike-through; the state is also the checkbox and its stateDescription) · trailing
`drag_indicator` (cd «اسحب لإعادة الترتيب»; TalkBack actions «انقل لأعلى» / «انقل لأسفل»); an add row «أضف خطوة»
(Enter adds the next); a switch row «أنجز التذكير عند إكمال كل الخطوات» (off by default, F-071). Max 50.

### 5.19 History — `history-*.png`

Pushed «السجل» with the reminder title as a subtitle. Day headers («اليوم»، «12 نوفمبر»…); events with a 40 dp
`secondaryContainer` disc and a 2 dp `outlineVariant` connector: «أُجّل 10 دقائق» 9:05 ص · «غُيّر الموعد: من 5:00 م إلى
9:00 ص» · «تغيّر التكرار: كل شهر» · «أُضيف تنبيه: قبل يومين» · «أُنجز» · «أُنشئ». Footer (`lock`): «السجل محفوظ على هاتفك
فقط. يمكنك إيقافه أو مسحه من الإعدادات.» Off by default (F-072): ⋮ «السجل» is hidden while off.

### 5.20 Restore summary — `restore-*.png`

`AlertDialog` «استعادة هذه النسخة؟» after the file is fully decoded and verified: source («من ذكرني لويندوز» / «من ذكرني
لأندرويد» + date), `check_circle` «تم التحقق من سلامة الملف», a 2 × 2 grid of counts («نشطة 24»، «متكررة 9»، «منجزة 130»،
«في السلة 3»), «الملاحظات: كلها محفوظة», warnings with `warning` («مرفقان لن يُستعادا»، «3 تذكيرات تستعمل نغمة غير موجودة
على هذا الهاتف، وستستعمل النغمة الافتراضية»، prayer reminders without settings), then «سيحل هذا محل تذكيراتك الحالية
(31). نحفظ نسخة أمان قبل الاستبدال.» Actions **«إلغاء»** · **«استعادة»** (filled, last). Refusals (bad checksum, newer
format, not a backup, wrong password) are their own dialogs with one «حسنًا».

### 5.21 Compatibility report — `compat-report-*.png`

Pushed «تقرير التوافق». Intro «تقرير قصير عن هاتفك ونتائج الفحص، لتشاركه بنفسك إن أردت. لا يُرسَل شيء تلقائيًا.» A
`surfaceContainer` card shows **the exact text** that will be shared (Latin runs and number ranges isolated LTR). Then
«ما لا يتضمنه التقرير:» with `visibility_off` items: «العناوين والملاحظات والأوقات والقوائم»، «معرّفات الهاتف والحسابات»،
«الموقع والمنطقة الزمنية». Bottom pair «نسخ» (S) · «مشاركة» (P, `ACTION_SEND`).

### 5.22 Widget — `widget-*.png`

4×3 list (RemoteViews, theme-aware colours, 24 dp radius / system radius on 12+): header brand mark + «ذكرني» + 48 dp
`primary` «+» (cd «تذكير جديد» → Quick Add). Rows: **48 dp Done circle at the start** (cd «تم: <title>», F-121, only for
due/overdue/today) · 76 dp time column with a small label («متأخر» in `error` + a warning icon at the row end, «اليوم»,
«غدًا») · title (one line). After a tap the row shows a filled check + «أُنجز: <title>» + `TextButton` «تراجع» for 10 s.
2×1 «التالي»: label, big time, title, tonal «تم». Empty: «لا تذكيرات قادمة».

### 5.23 Quick Settings tile — `qs-tile-*.png`

`TileService` tile, label **«تذكير جديد»**, subtitle «ذكرني», icon `ic_stat_bell`, state active; tap → Quick Add over the
current app (`startActivityAndCollapse(PendingIntent)` on 34+); on the lock screen Android asks to unlock first.
Long-press → the app.

### 5.24 Prayer-relative time — `prayer-*.png` (F-050, approved 4–1)

The time chip opens a sheet «الوقت» with a segmented button «ساعة محددة | حسب الصلاة». Prayer: single-select chips
الفجر، الظهر، العصر، المغرب، العشاء; offset: segmented «قبل | بعد» + stepper (48 dp − / +, 5-minute steps) showing «15
دقيقة»; preview `event` + «اليوم نحو 3:05 م (تقريبي)» + muted «العصر 2:50 م في بغداد»; text buttons «المدينة: بغداد» and
«الطريقة: أم القرى» (the first use asks for both; no default, no location permission); note «تُحسب الأوقات على هاتفك من
المدينة التي تختارها، دون موقع ودون إنترنت.» Actions «إلغاء» · «تم». Rows show «بعد العصر بـ15 دقيقة» as the repeat-like
line with `mosque`.

### 5.25 What's new — `whatsnew-*.png` (F-106)

Once after updating from < 1.0.0: `AlertDialog` «ما الجديد في 1.0.0» with four icon lines: «التذكيرات المهمة صارت ترنّ
بصوت المنبّه حتى والهاتف صامت.» · «اختر نغمة لكل تذكير.» · «صحة التذكيرات واختبار التنبيه في الإعدادات.» · «إضافة
سريعة: اكتب «ذكرني أتصل بأمي بعد ساعة».» Actions «اجعل كل التذكيرات عادية» (S, first) · «حسنًا» (P, last).

### 5.26 Unchanged from 0.1

Trash, About (version text 1.0.0; licences add Material Symbols and the GeoNames city list), the editor's repeat dialog,
discard dialog and date/time pickers, the empty state, search mode on Home (now also matching tags, category and state,
F-065), dialogs and snackbars (V-rules in §4).

---

## 6. Components (Compose)

Existing in `ui/Components.kt` and kept: `TooltipIconButton`, `BrandMark`, `SectionHeader`, `Group`/`GroupItem`,
`SettingsRow`, `SwitchRow`, `ChoiceDialog`, `ConfirmDialog`, `BottomActionBar`, `ButtonPair`, `StatusBanner`, `TimeText`,
`TaskMeta`, `PriorityTag`. New (all stateless, previews in light/dark/200 %):

| Composable | Signature (essentials) | Notes |
|---|---|---|
| `MsIcon` | `(name: Int /*R.drawable.ms_…*/, contentDescription: String?, size: Dp = 24.dp, tint: Color = LocalContentColor.current)` | wraps `Icon(painterResource)` |
| `ViewMenuButton` | `(current: HomeView, onSelect: (HomeView) -> Unit)` | `enum class HomeView { LIST, AGENDA, DAY, WEEK, MONTH }` |
| `QuickFilterRow` | `(filters: List<QuickFilter>, selected: String, onSelect, onOpenLists)` | hidden when `filters.size < 2` |
| `SwipeableReminderRow` | `(task, onDone, onSnoozeRequest, enabled: Boolean, content)` | `SwipeToDismissBox`, 40 % threshold, resets to `Settled` after `EndToStart` |
| `StateTag` | `(kind: TagKind /*SNOOZED, ALARM, PRIORITY_HIGH, PRIORITY_MED, OK, BAD, NEUTRAL*/, text: String)` | icon + text always |
| `QuickAddSheet` | `(state: QuickAddState, onTextChange, onChoose(choice), onClearDate, onSave, onMore, onTemplate, onVoice, onDismiss)` | `QuickAddState(text, preview: Preview?, ask: Ask?, choices: List<Choice>)`; `canSave = ask == null || chosen != null` |
| `ParsedPreviewCard` | `(preview: Preview?, nothingUnderstood: Boolean, onClear)` | |
| `DateTimeChips` | `(date: LocalDate?, time: LocalTime?, hijri: String?, onDate, onTime, onClear)` | |
| `AlertListEditor` | `(alerts: List<AlertItem>, onToggle, onEdit, onAdd, max = 3)` | |
| `CollapsibleGroup` | `(title, summary, expanded, onToggle, content)` | `Role.Button` + stateDescription |
| `ChoiceCard` | `(icon, title, body, selected, onClick, extra: @Composable (() -> Unit)? = null)` | alert-style cards |
| `TonePickerSheet` | `(current: ToneChoice, onPreview, onPick, onDismiss)` | play/stop state hoisted |
| `SnoozeSheet` | `(title, presets: List<Pair<String, String>> /*label, result*/, onPick(index), onPickTime, onDismiss)` | |
| `AlertDock` | `(presets, defaultSnooze: String, showSkip: Boolean, onDone, onSnooze, onPreset, onPickTime, onSkip, guardMs = 1000)` | `pointerInput` guard; semantics actions unaffected |
| `HealthSummaryCard` / `HealthRow` | `(status: Status /*READY, NEEDED, RESTRICTED, OFF, INFO, UNKNOWN*/, title, detail, source: Source?, action: HealthAction?)` | status word from the enum, never colour-only |
| `TestResultRow` | `(part: TestPart, result: Result /*PASS, FAIL, WAITING, UNKNOWN*/, detail, onAnswer: ((Boolean) -> Unit)?, fix: HealthAction?)` | |
| `NumberedStep` | `(n: Int, text: String)` | DND help, maker steps |
| `SettingsSearchField` / `SettingsSearchResult` | `(query, onQuery, onBack)` / `(title, groupName, highlight: IntRange?, onClick)` | index built once from a static list of `SettingEntry(key, titleRes, groupRes, synonymsRes)` |
| `CalendarHeader` | `(title, view, onToday, onViewMenu)` + `ViewSegments(view, onSelect)` | segments → dialog row at fontScale ≥ 1.5 |
| `AgendaList`, `DayTimeline`, `WeekRows`, `MonthGrid` | `(days: List<DayBucket>, selected: LocalDate, onSelectDay, onOpen, …)` | Compose only, no calendar library |
| `SelectionTopBar` / `BulkActionBar` | `(count, onClose, onSelectAll, onExport)` / `(onDelete, onPriority, onMove, onSnooze, onDone)` | 5 labelled `NavigationBarItem`-like buttons in a `Row`, 64 dp |
| `StepsEditor` | `(steps, onToggle, onEdit, onAdd, onMove, autoComplete, onAutoComplete)` | `LinearProgressIndicator(progress = { done / total })` |
| `HistoryTimeline` | `(days: List<Pair<String, List<HistoryEvent>>>)` | |
| `CountTile` | `(label, value: Int)` | restore summary grid |
| `TwoPane` | `(list: @Composable () -> Unit, detail: @Composable () -> Unit)` | only when `screenWidthDp >= 840` |

---

## 7. Implementation plan (for the Compose developer)

**Order:** FEATURES.md's "reliability first" applies: steps 1–4 can land with the reliability work; screens for
features that are not built yet land with their feature. Each step one commit, `gradlew :app:testDebugUnitTest` green
before and after. Work on a `claude/design-1.0` branch.

### 7.1 Tokens (Kotlin, `ui/Theme.kt`)

```kotlin
// Colour schemes, AppTypography, AppShapes, GroupOuter/GroupInner: unchanged (already in Theme.kt).
val AgendaTime = style(17, 24, FontWeight.Medium).copy(fontFeatureSettings = "tnum")
val DayNumber = style(16, 24, FontWeight.Medium).copy(fontFeatureSettings = "tnum")
val HijriNumber = style(12, 16, FontWeight.Normal).copy(fontFeatureSettings = "tnum")

object Dimens { // existing members kept: xs s m l xl xxl fabClearance rowMin touch
    val sheetCorner = 28.dp; val rowCompact = 56.dp; val timeColumn = 76.dp
    val alertButton = 64.dp; val alertGap = 16.dp; val hourHeight = 60.dp; val hourLabel = 56.dp
    val listPane = 440.dp; val twoPane = 840.dp; val maxContent = 640.dp; val compactWidth = 600.dp
}
object Behaviour {
    const val SwipeThreshold = 0.4f
    const val UndoMillis = 10_000L          // SnackbarDuration.Long; widget and notification undo
    const val AlertTapGuardMs = 1_000L
    const val QuickAddDebounceMs = 150L
    const val MaxEarlyAlerts = 3; const val MaxSteps = 50; const val MaxQuickAddChoices = 4
    const val BulkConfirmFrom = 10
}
val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
val ScrimAlpha = 0.32f
```

### 7.2 Steps

1. **Icons** — vendor the ~60 Material Symbols Rounded drawables of §3.4 as `res/drawable/ms_*.xml`, add
   `android:autoMirrored="true"` to the mirrored list, add `MsIcon`, replace every `Icons.*` use, drop
   `material-icons-extended`. Add the Apache-2.0 notice to `THIRD_PARTY.md` and About. Test: an RTL screenshot of the
   back arrow pointing right; a unit test lists the drawables that must carry `autoMirrored`.
2. **Tokens and shared components** (§7.1, §6): `StateTag`, `CollapsibleGroup`, `ChoiceCard`, `NumberedStep`,
   `CountTile`, `TwoPane`. No screen change.
3. **Home**: `ViewMenuButton`, `QuickFilterRow` (hidden for a one-list user — UI test), `SwipeableReminderRow`, row
   metadata order (§5.1), custom actions, 10 s snackbars, long-press → selection.
4. **Reminder screen**: `AlertDock` (pair + 3×2 presets + skip), tap guard, alert-style tag, late reason, volume-key
   silence, F-026 public title. **Notification**: three actions, pinned header, public version with actions, undo note.
5. **Health** (replaces `BackgroundScreen`): summary card, five groups, `HealthRow` with source and one action, bottom
   test button; **Alert test** start sheet, running card, results; **DND help**.
6. **Editor**: §6.2 order, `DateTimeChips`, `AlertListEditor`, «التنظيم» rows, `CollapsibleGroup` «خيارات التنبيه» with
   summary and auto-expand, ⋮ items; **ringtone sheet**, **alert-style sheet**.
7. **Quick Add sheet** + launcher shortcut + share target + tile entry; **templates** screen.
8. **Settings** groups (§5.14) and **search**; full-screen dialog; what's new dialog.
9. **Snooze sheet**, **bulk selection**, **steps**, **history**, **lists & tags**.
10. **Calendar views** (agenda, day, week, month) + Hijri display + prayer time sheet (with their features).
11. **Restore summary**, **compatibility report**, **widget «تم»**, **tablet two-pane**.
12. **Sweep**: no `" · "` in Arabic, ranges with «إلى», every new string in `tools/strings_gen.py` (ar + en), QC
    screenshots of every new screen in ar/en × light/dark × 200 % (F-103).

### 7.3 New strings (Arabic / English) — add to `tools/strings_gen.py`

Plurals use Arabic CLDR forms (zero, one, two, few, many, other); English one/other. Digits always Western.

| Key | Arabic | English |
|---|---|---|
| `view_menu` | طريقة العرض | View |
| `view_list` / `view_agenda` / `view_day` / `view_week` / `view_month` | القائمة / أجندة / يوم / أسبوع / شهر | List / Agenda / Day / Week / Month |
| `go_today` | اليوم | Today |
| `chip_all` / `chip_lists` | الكل / القوائم… | All / Lists… |
| `select` | تحديد | Select |
| `done_undo_msg` | أُنجز «%1$s» | Done: “%1$s” |
| `added_msg` | أُضيف التذكير | Reminder added |
| `snoozed_until_msg` | أُجّل حتى %1$s | Snoozed until %1$s |
| `state_snoozed` | مؤجّل | Snoozed |
| `style_tag_short` | منبّه | Alarm |
| `vibrate_only` | اهتزاز فقط | Vibrate only |
| plural `alerts_n` | two: تنبيهان; few: %1$d تنبيهات; other: %1$d تنبيهًا | %1$d alerts |
| `qa_label` | اكتب التذكير وموعده | Type a reminder and when |
| `qa_title_is` | العنوان: %1$s | Title: %1$s |
| `qa_nothing` | لم أفهم موعدًا. سيُحفظ النص عنوانًا بلا موعد. | No time understood. The text will be saved as a title with no date. |
| `qa_more` | خيارات أكثر | More options |
| `qa_template` | من قالب | From a template |
| `qa_category` | التصنيف: %1$s | Category: %1$s |
| `qa_voice` | إدخال صوتي | Voice input |
| `no_date` | بلا موعد | No date |
| `qa_ask_meridiem` / `qa_ask_day` / `qa_ask_time` / `qa_ask_past` | الساعة %1$s صباحًا أم مساءً؟ / هذا الأسبوع أم القادم؟ / أي ساعة؟ / هذا الوقت مضى. التالي؟ | %1$s in the morning or the evening? / This week or next? / What time? / That time has passed. The next one? |
| `qa_choose_first` | اختر موعدًا أو «بلا موعد» | Pick a time, or “No date” |
| `templates_title` / `templates_hint` | من قالب / القالب يملأ الحقول فقط. لا يُحفظ شيء قبل «حفظ». | From a template / A template only fills in the fields. Nothing is saved before “Save”. |
| `tpl_group_events` / `tpl_group_bills` / `tpl_group_routine` / `tpl_mine` | مناسبات / الفواتير والمواعيد / الروتين / قوالبي | Occasions / Bills and appointments / Routine / My templates |
| `tpl_birthday` / `tpl_anniversary` / `tpl_bill` / `tpl_medical` / `tpl_document` / `tpl_medicine` / `tpl_maintenance` / `tpl_weekly` | عيد ميلاد / ذكرى سنوية / دفع فاتورة شهرية / موعد طبي / تجديد وثيقة / دواء يومي / صيانة دورية / متابعة أسبوعية | Birthday / Anniversary / Monthly bill / Medical appointment / Document renewal / Daily medicine / Regular maintenance / Weekly follow-up |
| `edit_title` / `new_title` | تعديل التذكير / تذكير جديد | Edit reminder / New reminder |
| `add_date` | أضف موعدًا | Add a date |
| `repeat_none` | لا يتكرر | Does not repeat |
| `alerts_section` / `alert_at_time` / `alert_add` / `alert_remove` | التنبيهات / عند الموعد / إضافة تنبيه / حذف التنبيه | Alerts / At the time / Add an alert / Remove alert |
| `organise_section` / `category` / `tags` | التنظيم / التصنيف / الوسوم | Organise / Category / Tags |
| `note_steps_section` / `steps` / `step_add` / `steps_autocomplete` | الملاحظة والخطوات / الخطوات / أضف خطوة / أنجز التذكير عند إكمال كل الخطوات | Note and steps / Steps / Add a step / Complete the reminder when all steps are done |
| `drag_reorder` / `move_up` / `move_down` | اسحب لإعادة الترتيب / انقل لأعلى / انقل لأسفل | Drag to reorder / Move up / Move down |
| `alert_options` | خيارات التنبيه | Alert options |
| `alert_style` / `style_normal` / `style_alarm` | نوع التنبيه / تذكير عادي / منبّه مهم | Alert type / Normal reminder / Important alarm |
| `style_normal_body` | إشعار بصوت الإشعارات. إن كان الهاتف صامتًا يهتز فقط، وفي «عدم الإزعاج» يبقى صامتًا. | A notification at notification volume. On a silent phone it only vibrates; in Do Not Disturb it stays silent. |
| `style_alarm_body` | يرنّ بصوت المنبّه حتى والهاتف صامت، ويستمر حتى تتعامل معه. لا يتجاوز «عدم الإزعاج» إلا إذا سمحت له. | Rings at alarm volume even on a silent phone, until you deal with it. It does not get through Do Not Disturb unless you allow it. |
| `style_alarm_hint` | يرنّ حتى والهاتف صامت | Rings even when the phone is silent |
| `alert_way` | طريقة التنبيه | How to alert |
| `dnd_allow` | السماح في «عدم الإزعاج» | Allow in Do Not Disturb |
| `tone` / `tone_default` / `tone_builtin` / `tone_phone` / `tone_files` / `tone_silent` | النغمة / النغمة الافتراضية / نغمات ذكرني / من نغمات الهاتف… / من ملفاتي… / صامت (اهتزاز فقط) | Tone / Default tone / Remember Me tones / Phone tones… / From my files… / Silent (vibrate only) |
| `tone_for_this` / `tone_copied` / `tone_play` / `tone_stop` | لهذا التذكير / نسخة محفوظة داخل التطبيق / استمع / أوقف | For this reminder / A copy kept inside the app / Listen / Stop |
| `tone_note` | في «تذكير عادي» يتبع الصوت مستوى صوت الإشعارات؛ إن كان الهاتف صامتًا سيهتز فقط. | A normal reminder follows notification volume; on a silent phone it only vibrates. |
| `vibration` / `vib_short` / `vib_normal` / `vib_long` / `vib_off` | الاهتزاز / قصير / عادي / طويل / مطفأ | Vibration / Short / Normal / Long / Off |
| `full_screen` / `fs_follow` / `fs_always` / `fs_important` / `fs_off` / `fs_show` / `fs_notify_only` | الشاشة الكاملة / حسب الإعداد العام / دائمًا / للتذكيرات المهمة فقط / مطفأة، إشعار عادي فقط / اعرض الشاشة الكاملة / إشعار فقط | Full screen / As in settings / Always / Important reminders only / Off, a normal notification only / Show full screen / Notification only |
| `fs_fallback_note` | إن لم يسمح أندرويد بعرضها، يصلك إشعار عادي. | If Android does not allow it, you get a normal notification. |
| `pin` / `pin_hint` | تثبيت التذكير على شاشة القفل / يبقى ظاهرًا حتى تتعامل معه | Pin the reminder on the lock screen / Stays until you deal with it |
| `speak` / `speak_hint` | اقرأ العنوان بصوت / لا يُقرأ في الصامت ولا في «عدم الإزعاج» | Read the title aloud / Not read when silent or in Do Not Disturb |
| `lock_screen` / `lock_show` / `lock_hide` / `lock_public_title` | على شاشة القفل / أظهر التفاصيل / أخفِ التفاصيل / لديك تذكير | On the lock screen / Show details / Hide details / You have a reminder |
| `history` / `save_as_template` / `duplicate` | السجل / حفظ كقالب / نسخ | History / Save as template / Duplicate |
| `snooze_for` (exists) | أجّل %1$s | Snooze %1$s |
| `snooze_more` / `snooze_until` / `snooze_title` / `until_time` / `tomorrow_same` / `repeat_unchanged` | أجّل مدة أخرى / أجّل حتى… / أجّل / حتى %1$s / غدًا في الوقت نفسه / التكرار لا يتغيّر | Snooze longer / Snooze until… / Snooze / until %1$s / Tomorrow, same time / The repeat does not change |
| `snooze_hint3` | أجّلته 3 مرات. اختر وقتًا أنسب؟ | Snoozed 3 times. Pick a better time? |
| `on_time` | حان الوقت | It's time |
| `arrived_late` / `late_reason_off` | وصل متأخرًا %1$s / كان الهاتف مطفأ | Arrived %1$s late / The phone was off |
| `locked_reminder` | لديك تذكير، افتح القفل لرؤيته | You have a reminder. Unlock to see it |
| `volume_silence_hint` | اضغط زر الصوت لإسكات الرنين | Press a volume key to silence |
| `notif_alarm_text` | %1$s، يرنّ حتى تتعامل معه | %1$s, rings until you deal with it |
| `summary_title` / plural `summary_text` / `summary_first` / `open` | ملخص الصباح / لديك %1$d تذكيرات اليوم (one: لديك تذكير واحد اليوم; two: لديك تذكيران اليوم; many/other: لديك %1$d تذكيرًا اليوم) / أولها %1$s: %2$s / افتح | Morning summary / You have %1$d reminders today / First at %1$s: %2$s / Open |
| `health_title` | صحة التذكيرات | Reminder health |
| `check_now` / `last_check` | افحص الآن / آخر فحص: %1$s | Check now / Last check: %1$s |
| `group_essential` / `group_recommended` / `group_phone_now` / `group_maker` / `group_scheduling` | ضروري / يُنصح به / الهاتف الآن / الشركة المصنعة / الجدولة | Essential / Recommended / The phone now / Phone maker / Scheduling |
| `src_android` / `src_maker` | أندرويد / الشركة المصنعة | Android / Phone maker |
| `st_ready` / `st_needed` / `st_off` / `st_restricted` / `st_info` / `st_unknown` | جاهز / مطلوب / غير مفعّل / مقيّدة / للعلم / غير معروف | Ready / Needed / Off / Restricted / For info / Unknown |
| `fix` (exists) / `allow` / `open_settings` | إصلاح / اسمح / افتح الإعدادات | Fix / Allow / Open settings |
| `test_now` | اختبر التنبيه الآن | Test an alert now |
| `scheduled_n` / `attention_n` / `last_delay` | مجدولة: %1$d / تحتاج معالجة: %1$d / آخر تأخير مُقاس: %1$s | Scheduled: %1$d / Need attention: %1$d / Last measured delay: %1$s |
| `phone_silent` / `phone_silent_note` | الهاتف صامت / التذكير العادي سيهتز فقط. المنبّه المهم يرنّ. | The phone is silent / A normal reminder will only vibrate. An important alarm rings. |
| `maker_tried` / `maker_untried` | جُرِّب على هاتف / لم يُجرَّب على هاتف بعد | Tried on a phone / Not yet tried on a phone |
| `test_title` / `test_start` / `test_when` / `test_in_10s` / `test_in_1h` / `test_tomorrow_7` | اختبار التنبيه / ابدأ الاختبار / متى / بعد 10 ثوانٍ / بعد ساعة / غدًا 7:00 ص | Alert test / Start the test / When / In 10 seconds / In an hour / Tomorrow 7:00 AM |
| `test_running` | سيصل تنبيه تجريبي بعد %1$s. اقفل الشاشة إن أردت اختبار الشاشة الكاملة. | A test alert will arrive in %1$s. Lock the screen to test the full screen. |
| `test_done` / `test_parts` / `test_tested` | انتهى الاختبار / الأجزاء الستة / اختبرتَ: %1$s، %2$s | Test finished / The six parts / You tested: %1$s, %2$s |
| `part_alarm` / `part_notification` / `part_sound` / `part_vibration` / `part_full_screen` / `part_buttons` / `part_playback` | المنبّه في وقته / الإشعار / الصوت / الاهتزاز / الشاشة الكاملة / الأزرار / بدأ تشغيل الصوت | Alarm on time / Notification / Sound / Vibration / Full screen / Buttons / Playback started |
| `res_pass` / `res_fail` / `res_waiting` | نجح / لم ينجح / ينتظر جوابك | Passed / Failed / Waiting for your answer |
| `q_sound` / `q_vibration` / `yes` / `no` | هل سمعت الصوت؟ / هل شعرت بالاهتزاز؟ / نعم / لا | Did you hear the sound? / Did you feel the vibration? / Yes / No |
| `retest` / `test_writes_nothing` | أعد الاختبار / الاختبار لا يحفظ أي تذكير. | Test again / The test saves no reminder. |
| `dnd_title` / `dnd_intro` | المنبّه المهم وعدم الإزعاج / ذكرني لا يتجاوز «عدم الإزعاج» من تلقاء نفسه. إن أردت أن يرنّ المنبّه المهم أثناءه، اسمح له من إعدادات الهاتف: | Important alarms and Do Not Disturb / Remember Me never gets through Do Not Disturb on its own. To let important alarms ring during it, allow them in the phone's settings: |
| `dnd_step_1` / `dnd_step_2` / `dnd_step_3` / `dnd_names_vary` / `dnd_open` | افتح إعدادات «عدم الإزعاج» / في «التطبيقات» أو «الاستثناءات» أضف «ذكرني» / تأكّد أن «المنبّهات» مسموحة / تختلف الأسماء حسب الشركة المصنعة للهاتف. / افتح إعدادات عدم الإزعاج | Open Do Not Disturb settings / Under “Apps” or “Exceptions”, add Remember Me / Make sure “Alarms” are allowed / Names differ by phone maker. / Open Do Not Disturb settings |
| `dnd_now_on` / `dnd_app_not_allowed` | عدم الإزعاج الآن: مفعّل / ذكرني: غير مسموح له | Do Not Disturb is on / Remember Me: not allowed |
| `settings_search` / `in_group` / plural `results_n` / `no_results` | ابحث في الإعدادات / في: %1$s / %1$d نتائج (one: نتيجة واحدة; two: نتيجتان; many/other: %1$d نتيجة) / لا نتائج لـ«%1$s» | Search settings / In: %1$s / %1$d results / No results for “%1$s” |
| `section_lock` / `section_prayer` / `section_summary` / `section_organise` | شاشة القفل / الصلاة / ملخص الصباح / التنظيم | Lock screen / Prayer / Morning summary / Organise |
| `default_tone_normal` / `default_tone_alarm` / `important_reminders` / `ring_length` / `swipe_setting` | نغمة التذكير الافتراضية / نغمة التذكيرات المهمة / التذكيرات المهمة / مدة رنين المنبّه المهم / السحب على التذكيرات | Default reminder tone / Important reminder tone / Important reminders / Important alarm ring length / Swipe on reminders |
| `hijri` / `hijri_adjust` | التاريخ الهجري / تعديل التاريخ الهجري | Hijri date / Adjust Hijri date |
| `lists_title` / `categories` / `categories_note` / `my_lists` / `list_tag` / `reorder` / `saved_filters` / `new_filter` / `done_and_trash` / `new_list` | القوائم والوسوم / التصنيفات / مشتركة مع ذكرني لويندوز / قوائمي / الوسم: %1$s / ترتيب / الفلاتر المحفوظة / فلتر جديد… / المنجزة والسلة / قائمة جديدة | Lists and tags / Categories / Shared with Remember Me for Windows / My lists / Tag: %1$s / Reorder / Saved filters / New filter… / Done and trash / New list |
| `prev_day` / `next_day` / `prev_week` / `next_week` / `prev_month` / `next_month` | اليوم السابق / اليوم التالي / الأسبوع السابق / الأسبوع التالي / الشهر السابق / الشهر التالي | Previous day / Next day / Previous week / Next week / Previous month / Next month |
| `now_at` / `more_n` / `nothing` / `range` | الآن %1$s / +%1$d أخرى / لا شيء / %1$s إلى %2$s | Now %1$s / +%1$d more / Nothing / %1$s to %2$s |
| plural `selected_n` / `select_all` / `clear_selection` / `export_selected` | %1$d محددة / تحديد الكل / إلغاء التحديد / تصدير المحدد | %1$d selected / Select all / Clear selection / Export selected |
| `bulk_delete` / `bulk_priority` / `bulk_move` | حذف / الأهمية / نقل | Delete / Priority / Move |
| plural `bulk_trash_q` / `bulk_trash_body` / `move_to_trash` | نقل %1$d تذكيرًا إلى السلة؟ / تبقى في السلة 30 يومًا ويمكنك استعادتها. / نقل إلى السلة | Move %1$d reminders to the trash? / They stay in the trash for 30 days and can be restored. / Move to trash |
| plural `bulk_done_msg` / `bulk_trash_msg` | أُنجز %1$d تذكيرات / نُقلت %1$d تذكيرات إلى السلة | %1$d reminders done / %1$d reminders moved to the trash |
| `ev_created` / `ev_moved` / `ev_snoozed` / `ev_done` / `ev_repeat` / `ev_alert_added` / `history_note` | أُنشئ / غُيّر الموعد: من %1$s إلى %2$s / أُجّل %1$s / أُنجز / تغيّر التكرار: %1$s / أُضيف تنبيه: %1$s / السجل محفوظ على هاتفك فقط. يمكنك إيقافه أو مسحه من الإعدادات. | Created / Moved from %1$s to %2$s / Snoozed %1$s / Done / Repeat changed: %1$s / Alert added: %1$s / History stays on your phone. You can turn it off or clear it in Settings. |
| `restore_q` / `from_windows` / `from_android` / `verified` | استعادة هذه النسخة؟ / من ذكرني لويندوز / من ذكرني لأندرويد / تم التحقق من سلامة الملف | Restore this backup? / From Remember Me for Windows / From Remember Me for Android / File checked: intact |
| `c_active` / `c_repeating` / `c_done` / `c_trash` / `notes_kept` / `restore_replace` / `restore` | نشطة / متكررة / منجزة / في السلة / الملاحظات: كلها محفوظة / سيحل هذا محل تذكيراتك الحالية (%1$d). نحفظ نسخة أمان قبل الاستبدال. / استعادة | Active / Repeating / Done / In trash / Notes: all kept / This replaces your current reminders (%1$d). A safety copy is saved first. / Restore |
| plural `attachments_dropped` / plural `tones_missing` | مرفقان لن يُستعادا (one: مرفق واحد لن يُستعاد; few: %1$d مرفقات لن تُستعاد) / %1$d تذكيرات تستعمل نغمة غير موجودة على هذا الهاتف، وستستعمل النغمة الافتراضية | %1$d attachments will not be restored / %1$d reminders use a tone missing on this phone and will use the default |
| `compat_title` / `compat_intro` / `compat_excludes` / `copy` / `share` | تقرير التوافق / تقرير قصير عن هاتفك ونتائج الفحص، لتشاركه بنفسك إن أردت. لا يُرسَل شيء تلقائيًا. / ما لا يتضمنه التقرير: / نسخ / مشاركة | Compatibility report / A short report on your phone and the checks, for you to share if you want. Nothing is sent automatically. / Not in the report: / Copy / Share |
| `widget_done_cd` / `widget_done_state` / `widget_next` | تم: %1$s / أُنجز: %1$s / التالي | Done: %1$s / Done: %1$s / Next |
| `tile_label` / `tile_subtitle` | تذكير جديد / ذكرني | New reminder / Remember Me |
| `whatsnew_title` / `whatsnew_1…4` / `make_all_normal` / `ok` | ما الجديد في 1.0.0 / (the four lines in §5.25) / اجعل كل التذكيرات عادية / حسنًا | What's new in 1.0.0 / … / Make all reminders normal / OK |
| `time_mode_clock` / `time_mode_prayer` / `fajr` `dhuhr` `asr` `maghrib` `isha` / `before` / `after` / `approx` / `prayer_note` / `city_is` / `method_is` | ساعة محددة / حسب الصلاة / الفجر الظهر العصر المغرب العشاء / قبل / بعد / تقريبي / تُحسب الأوقات على هاتفك من المدينة التي تختارها، دون موقع ودون إنترنت. / المدينة: %1$s / الطريقة: %1$s | Set time / By prayer / Fajr Dhuhr Asr Maghrib Isha / Before / After / approximate / Times are computed on your phone from the city you choose, with no location and no internet. / City: %1$s / Method: %1$s |

### 7.4 Test hooks (exact texts and contentDescriptions)

Texts are Arabic UI unless marked; "once" = exactly one node on that screen.

| Screen | Tests can find | Notes |
|---|---|---|
| Home | «ذكرني» (top-bar title); section «اليوم» **once** (no other «اليوم» on Home in list view); «القادمة»; «تذكير جديد» (FAB, unmerged); cd «طريقة العرض», «بحث», «الإعدادات»; chip «الكل» only with 2+ lists | **no** «التذكيرات»/«التقويم»/«القوائم» nav labels exist (W1) |
| Home row | merged cd contains title, time, «متأخر» (overdue), «منبّه مهم» (alarm style); custom actions «تم», «أجّل», «تحديد» | swipe = `SwipeToDismissBox`; test via `performTouchInput { swipeLeft() }` in RTL |
| Undo | snackbar «أُنجز «<title>»» + action «تراجع» | 10 s |
| Quick Add | field label «اكتب التذكير وموعده»; preview «العنوان: …»; «حفظ» `assertIsNotEnabled()` while «اختر موعدًا أو «بلا موعد»» is shown; cd «بلا موعد», «إدخال صوتي»; «خيارات أكثر» | no save path stores an unseen date (ROADMAP §4) |
| Editor | label «العنوان»; «حفظ» **once**; «إضافة تنبيه» gone after 3 early alerts; «خيارات التنبيه» with stateDescription «مطويّة» by default | |
| Reminder screen | «1 من 2»; **«أجّل 10 دقائق»** (replaces the old «10 دقائق» check); «تم» **once**; in `ar` «تم» is **left of** «أجّل 10 دقائق», in `en` right; at fontScale 2 «تم» is fully inside the window and **above** the snooze button | taps within 1 s ignored (`mainClock.advanceTimeBy(500)` then click → no effect; after 1 100 ms → effect) |
| Notification | actions in order «تم», «أجّل 10 دقائق», «أجّل حتى…»; public version title «لديك تذكير» with the same 3 actions | |
| Health | title «صحة التذكيرات» (replaces «السماح بالعمل في الخلفية» in `AppUiTest`); «افحص الآن»; «اختبر التنبيه الآن»; every not-ready row has «أندرويد» or «الشركة المصنعة» and exactly one button | never «جاهز» for an unknown value |
| Alert test | «الأجزاء الستة»; six part titles; «هل سمعت الصوت؟», «لا», «نعم»; «الاختبار لا يحفظ أي تذكير.» | |
| Settings | «صحة التذكيرات» (top row); search cd «بحث»; field «ابحث في الإعدادات»; «في: التنبيه» | |
| Lists | «القوائم والوسوم», «التصنيفات», «قوائمي», «قائمة جديدة» | |
| Calendar | «أجندة», «يوم», «أسبوع», «شهر» (segments); «اليوم» (go-to-today button, only in calendar views); month cell cd «السبت 14 نوفمبر، 5 تذكيرات، اليوم» | |
| Bulk | «3 محددة»; cd «إلغاء التحديد», «تحديد الكل»; bar labels «حذف», «الأهمية», «نقل», «أجّل», «تم» (in this order, «تم» last); dialog «نقل 12 تذكيرًا إلى السلة؟» only from 10 items | |
| Restore | «استعادة هذه النسخة؟»; «إلغاء» leaves `data.json` byte-identical | |
| Widget | row cd «تم: <title>»; after tap «أُنجز: <title>» + «تراجع» | |
| Tile | label «تذكير جديد» | |
| All new screens | every clickable ≥ 48 × 48 dp (existing touch-target test extended); no `·` in Arabic; no Arabic-Indic digits | |

---

## 8. Council verdicts (1.0.0)

Members: **D** senior Android product designer (Material 3, Android 15/16), **R** Arabic RTL typography and layout,
**A** accessibility (WCAG 2.2 AA / EN 301 549, TalkBack, 48 dp, 200 %, nothing by colour alone), **U** everyday
one-handed Arabic-speaking user, **X** devil's advocate.

| # | Decision | Verdict | Reasons and dissent |
|---|---|---|---|
| W1 | **Navigation: one list + top bar, no NavigationBar**; views from a top-bar menu «طريقة العرض»; lists as chips; Settings/Health/Lists as pushed screens | **First vote 3–2 for a 3-item bar** (D, U, R: «التذكيرات / التقويم / القوائم»; A, X against). **Re-vote after reading FEATURES.md: 4–1 for no bar** (U dissents) | FEATURES F-063 makes a list a saved tag filter shown as a chip, and F-064 puts views behind "a top-bar menu; no NavigationBar"; that left one candidate destination (calendar), and a bar needs 3–5. A: bar + FAB + gesture area cost 200 dp at 200 % text. X: PRD §6.1 "one clear list with a top bar"; a one-list user must see Home unchanged. R: fewer mirrored structures. **U (on record):** "Calendar at the top is far from my thumb." Answer: the view is one tap via the menu, Back returns; launcher shortcut «اليوم» (F-122). |
| W2 | FAB «تذكير جديد» (centred, V4 kept) **opens Quick Add**; the full editor is «خيارات أكثر» | 4–1 (X) | U: "I type one line and I'm done." D: Todoist/Tasks pattern. **X:** "Quick Add hides fields people need." Answer: one tap away, text and date carried over; the launcher shortcut «تذكير جديد» still opens the editor. |
| W3 | Quick Add: preview always visible; at most 4 dated choices; «حفظ» disabled while unclear | 5–0 | ROADMAP §4 "never invent a date". A: the reason is text, not just a disabled button. |
| W4 | Editor in PRD §6.2 order; date + time as chips on one row; alert list with switches (≤ 3 early); alert options in one collapsed group with a summary line that auto-opens when non-default | 5–0 | U: shorter. A: the summary line means nothing is hidden. R: date chip reads naturally «الاثنين 16 نوفمبر» then «10:30 ص» in RTL order. |
| W5 | Row: time hero (V2 kept); new state, style, steps, silent tags, each icon + word; style tag shortened to «منبّه» | 4–1 (A conditional) | **R:** «مهم» would appear twice (priority and «منبّه مهم»). **A:** accepted only with the full «منبّه مهم» in the contentDescription. |
| W6 | Swipe: start→end «تم» (immediate, Undo 10 s), end→start «أجّل» (opens the snooze sheet); directions follow layout direction; TalkBack custom actions | 4–1 (X) | D: Gmail/Todoist pattern, mirrored correctly by `SwipeToDismissBox`. **X:** "Accidental swipes while scrolling." Conditions adopted: 40 % threshold, haptic, Undo, and a Settings switch «السحب على التذكيرات». |
| W7 | Reminder screen: **tap buttons**, 64 dp pair «أجّل 10 دقائق» (tonal, start) · «تم» (filled, end), 16 dp gap, **1 s tap guard**, 5 presets + «أجّل حتى…» in a 3×2 grid, «تخطَّ» as a text button; **no slide, no long-press** | 4–1 (U) | A: WCAG 2.5.7/2.5.1; Google Clock made swipe an option because taps were too easy to hit — the guard and spacing solve the cause without a gesture. D: the default snooze the notification already names is now on screen. **U (on record):** "Done should be under my right thumb." Answer: house rule (secondary first, V5); Done is the filled, heavier button; Snooze under the resting thumb is the safe miss. |
| W8 | Snooze: the six presets (Windows format), the first is the default named everywhere; every choice shows its resulting time; impossible presets are replaced, not disabled | 5–0 | U: "Snooze until when?" X: the old disabled cell confused people. |
| W9 | Health as a grouped checklist with a source label and one fix per row; unknown never green; bottom «اختبر التنبيه الآن» | 5–0 | PRD P0.1; V12 kept and extended. |
| W10 | Alert test: six parts, objective checks + the user's answers, honest texts, start sheet with style and delay | 5–0 | PRD P0.4, F-009/F-027. |
| W11 | Settings: 9 groups + search (title, value, group, synonyms) | 4–1 (X) | **X:** "Search is overkill for a reminder app." Answer: ~45 rows across 9 groups; Android's own Settings has it; cost is one static index. |
| W12 | Alert style as two cards + a DND help screen; no control implies bypassing DND | 5–0 | F-015; honest wording. |
| W13 | Ringtone sheet with five sources, preview, honest note | 5–0 | F-014. |
| W14 | Lists/tags/filters: chips on Home (only with 2+) + a pushed «القوائم والوسوم» | 5–0 | F-063; the one-list Home is unchanged. |
| W15 | Calendar views as Home modes with a segmented row; month cells show counts as numbers; Hijri as a small second numeral | 4–1 (U) | A: counts, not dots (1.4.1). R: the Hijri numeral never replaces the Gregorian one. **U:** as in W1. |
| W16 | Bulk: long-press, contextual top bar, 5 labelled actions (primary last), Undo; confirm from 10 items or when irreversible | 4–1 (X) | **X:** "10 is arbitrary." Answer: Undo snackbars are easy to miss for large changes; the number is a token and can be tuned after the black-box test. |
| W17 | Notifications: three actions «تم»، «أجّل 10 دقائق»، «أجّل حتى…»; public version «لديك تذكير» keeps the actions; quiet «أُنجز — تراجع» | 5–0 | F-021/022/044/052. |
| W18 | Widget «تم» per row + 10 s undo; Quick Settings tile opens Quick Add | 5–0 | F-121/123. |
| W19 | Visual system: v1 palette and type kept; **Material Symbols Rounded** vendored drawables with an explicit mirroring list; material-icons-extended removed | 4–1 (X) | D: Symbols are maintained, Icons-extended is frozen and lacks 1.0.0 symbols; one family keeps strokes even (supersedes V14). **X:** "Asset churn." Answer: one mechanical step, smaller APK. |
| W20 | Motion respects reduced motion; 200 % rules (pairs stack primary on top, segments become dialogs, dock never scrolls) | 5–0 | F-103/F-105. |
| W21 | Adaptive: centred single pane 600–839 dp; list + detail from 840 dp; no rail; no new dependency | 5–0 | PRD §6.3 "when worthwhile"; cheap. |
| W22 | What's new dialog with «اجعل كل التذكيرات عادية» | 5–0 | F-106 condition of F-015's default. |
| W23 | Prayer-relative time as a mode of the time sheet; «تقريبي» always; city and method chosen by the user | 4–1 (X) | Same split as F-050. **X:** "A wrong time in a religious matter." Answer: «تقريبي», no default method. |
| W24 | Stable components only (V15 kept). `ModalBottomSheet`, `SwipeToDismissBox`, `TooltipBox`, `TopAppBar`, pickers may still need `@OptIn(ExperimentalMaterial3Api::class)` in this BOM; they are long-shipped baseline components | 5–0 | No Expressive APIs (no ButtonGroup, FloatingToolbar, FAB menu). |

**v1 verdicts:** V1 (sections), V2 (time hero), V3 (Done at start), V4 (centred FAB), V6 (palette), V7 (no dynamic
colour), V8 (digits, separator), V9 (one Save), V10 (repeat dialogs), V12, V13, V15, **V16 (no NavigationBar, re-confirmed
by W1)**, V17 — kept. V5 kept with W7's application. V11 superseded by W7/W8. V14 superseded by W19.

### Devil's advocate: objections on record

1. *"1.0.0 doubles the surface; the app will feel busy."* **Accepted as a rule:** every new control appears only when
   it applies (chips with 2+ lists, Hijri numerals only when on, steps only when present, alert options folded).
2. *"No NavigationBar hides the calendar."* **Overruled** (W1), U's dissent recorded.
3. *"A 1 s tap guard can feel broken."* **Partly accepted:** it applies only to pointer taps right after the screen
   appears, never to accessibility actions, and the black-box tester ("أم علي") checks it.
4. *"Swipe is invisible to many users."* **Accepted:** every swipe has a visible equivalent (Done circle, ⋮/long-press,
   reminder screen) and TalkBack actions.
5. *"Search in Settings is overkill."* **Overruled** (W11).
6. *"Replacing icons is churn."* **Overruled** (W19), as one mechanical step.

---

## 9. Reconciliation with docs/FEATURES.md

FEATURES.md (feature council, 2026-11-14) is the authority on what ships. Read on 2026-11-15; the design changed to match:

| FEATURES item | Design consequence |
|---|---|
| F-063 lists = saved tag filters; categories fixed at 3 | Editor row «التصنيف» (not «القائمة»); Lists screen split into «التصنيفات» and «قوائمي»; chip «التصنيف: …» in Quick Add |
| F-064 views via a top-bar menu, no NavigationBar | W1 re-vote; NavigationBar and rail removed from every mockup |
| F-020 full-screen default «دائمًا» | Settings and dialogs show «دائمًا» as default |
| F-021 pin on by default; F-022 «أظهر التفاصيل» default | Settings defaults; per-reminder override rows |
| F-014 two default tones + 3 bundled tones | two Settings rows; «نغمات ذكرني» in the sheet |
| F-015 high priority → «منبّه مهم» by default; F-106 | Settings row «التذكيرات المهمة»; what's new dialog |
| F-016/F-018/F-019 | volume-key silence hint; «مدة رنين المنبّه المهم»; vibration pattern row |
| F-025, F-026 | late reason line; locked-boot title on the reminder screen |
| F-027 | test start sheet (style + delay) |
| F-030 morning summary text «لديك N تذكيرات اليوم» | notification mockup and strings |
| F-031 read aloud | editor row |
| F-044 «أجّل حتى…» in the notification | third action |
| F-046 ≤ 3 early alerts | alert list max |
| F-047 user templates | «قوالبي», editor ⋮ «حفظ كقالب» |
| F-048 voice | mic in Quick Add |
| F-050 prayer (approved 4–1), F-051 Hijri (4–1) | designed, not "if approved" any more |
| F-052 undo 10 s | `UndoMillis` |
| F-071 auto-complete off by default; F-072 history off by default | Settings defaults; ⋮ «السجل» hidden while off |
| F-205 attachments **deferred** | **dropped** from the editor (PRD §6.2's "attachments" not designed); restore summary still reports dropped attachments |
| F-203 «مؤرشف» **rejected** | no archive state or action (bulk ⋮ has «تصدير المحدد» only) |
| F-207/F-208 location **rejected** | prayer city from a list or typed coordinates only |
| F-209 dynamic colour deferred | V7 kept |
| F-210 camera flash rejected | not designed; the DND help screen does not mention it |

Nothing in the design asks for a permission outside FEATURES' set.

---

## 10. Mockup index

All in `design/v2/shots/`, Arabic RTL, light and dark (`<name>-light.png`, `<name>-dark.png`), 412×915 dp at 2×.

| Mockup | Screen |
|---|---|
| `home` | Home (list view, chips) — also `home-ar-200`, `compare-home` |
| `home-swipe` | swipe تم / أجّل and Undo snackbar |
| `home-tablet` | tablet / foldable two-pane (1280×800 dp) |
| `quickadd`, `quickadd-ask` | Quick Add with preview; with ambiguity choices — `compare-quickadd` |
| `templates` | templates |
| `edit`, `edit-more`, `edit-advanced` | editor top; scrolled with «خيارات التنبيه» folded; expanded — `compare-edit` |
| `subtasks` | steps in the editor |
| `ringtone`, `alert-type`, `fullscreen` | tone sheet; alert style sheet; Settings full-screen dialog |
| `alert` | reminder screen — also `alert-ar-200`, `compare-alert` |
| `snooze` | snooze sheet |
| `notification`, `notification-lock` | shade (pinned, alarm, summary, undo); lock screen (hidden / shown details) |
| `health`, `alert-test`, `dnd-help` | Health — `compare-health`; test results; DND help |
| `settings`, `settings-search` | Settings — `compare-settings`; search |
| `lists` | lists, tags, saved filters |
| `cal-agenda`, `cal-day`, `cal-week`, `cal-month` | calendar views (Hijri numerals shown because the option is on) |
| `bulk`, `bulk-confirm` | selection mode; confirm for 12 items |
| `history`, `restore`, `compat-report` | history; restore summary; compatibility report |
| `widget`, `qs-tile` | widgets with «تم»; Quick Settings tile |
| `whatsnew`, `prayer` | what's new; prayer-relative time sheet |

Total: 80 PNGs (36 screens × 2 themes, 2 at 200 % text, 6 before/after). The 200 % shots use Android 14's non-linear
table with proportional line heights; icons do not scale. Sample data is invented.

---

## 11. Licences

| Asset | Licence | Where |
|---|---|---|
| Noto Sans Arabic (app: static 400/500/700; mockups: VF) | SIL Open Font License 1.1 | `app/src/main/res/font/`, `design/v2/kit/fonts/` (`OFL-NotoSansArabic.txt`) |
| Noto Sans 400/700 (mockup Latin only) | SIL OFL 1.1 | `design/v2/kit/fonts/` |
| Material Symbols Rounded (vendored vector drawables) | Apache License 2.0 | `res/drawable/ms_*.xml`; named in About and `THIRD_PARTY.md` |
| material-icons-extended (until W19 lands) | Apache License 2.0 | app dependency, then removed |
| GeoNames city list (F-050) | CC BY 4.0 | credited in About and `THIRD_PARTY.md` (feature's own deliverable) |
| material-color-utilities (palette generation, tooling only) | Apache License 2.0 | `design/tools/palette.mjs` via `$env:MCU_DIR` |
| Playwright (rendering, tooling only) | Apache License 2.0 | `E:\fast kiwi pro neq\tools\pw` |
| Brand mark | FastKiwi original (the Windows icon) | `Remember-Me/assets/icon.svg` |

Mockup icons are original hand-drawn stand-ins. Nothing is copied from Google, Microsoft, Samsung, Doist or TickTick: no
logos, colours, illustrations or icon art; only common interaction patterns were benchmarked (§2).
