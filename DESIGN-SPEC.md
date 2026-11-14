# Remember Me for Android («ذكرني») — design spec

*FastKiwi design council, 2026-11-14. Status: **approved by the council**, ready for the app agent.*
*Applies to the Android app (Kotlin, Jetpack Compose, Material 3, minSdk 26, targetSdk 36, Compose BOM 2025.12.01).*

Owner request: «راعي ستاندر العالمي يجب ان يبدو احترافي» (follow international standards; it must look professional).

Everything here uses only **stable** Material 3 Compose components already on the classpath (`androidx.compose.material3`,
`material-icons-extended`), the Noto Sans Arabic font the app already bundles, and Apache-2.0 icons. No Expressive or
experimental-only components, no network, no new permissions, no new dependencies.

| Path | What |
|---|---|
| `DESIGN-SPEC.md` | this spec |
| `design/shots/<screen>-light.png`, `-dark.png` | mockups, Arabic RTL, 412×915 dp rendered at 2× (824×1830 px) |
| `design/shots/home-ar-200.png`, `alert-ar-200.png` | Arabic at 200 % font size (Android 14+ non-linear scaling) |
| `design/shots/home-en-light.png` | English (LTR) |
| `design/shots/compare-*.png` | before (`screenshots/*.png`, v0.1.0) and after, side by side |
| `design/mockups/*.html`, `kit.css`, `kit.js`, `fonts/` | HTML/CSS sources (light/dark via `prefers-color-scheme`; `?fs=2` = 200 % text) |
| `design/tools/palette.mjs` → `palette.json`, `palette.kt.txt` | the full M3 scheme generated from the brand seed (material-color-utilities) |
| `design/tools/contrast.mjs` → `contrast-report.md` | WCAG 2.2 check of every colour pair used; exits 1 on a failure |
| `design/tools/render.js` | Playwright renderer (Edge channel) |

Re-render: `$env:IC_PLAYWRIGHT_DIR="E:\fast kiwi pro neq\tools\pw"; node design\tools\render.js` (from the repo root).
Re-check colours: `node design\tools\contrast.mjs`.

---

## 1. Diagnosis: why v0.1.0 does not look professional yet

From `screenshots/01…08` and `app/src/main/java/com/fastkiwi/rememberme/ui/*.kt`.

### 1.1 Across the app

1. **The time is not the hero.** In a reminders app the first thing to read is *when*. Rows lead with the title
   (titleMedium) and bury the time in a red or grey metadata line («اليوم، 6:04 م · فات موعده منذ 3 ساعات»); the reminder
   screen does the same: a small bell, the title, then the time in body size.
2. **Incomplete colour scheme, so the baseline purple leaks in.** `Theme.kt` fills 22 roles and leaves out
   `outlineVariant`, `surfaceContainerLowest/Low/Highest`, `surfaceDim/Bright`, `tertiaryContainer`, `inverseSurface`,
   `inverseOnSurface`, `inversePrimary` and `scrim`. Visible results: the lavender-grey line under the tabs (`#CAC4D0`,
   screenshot 01), lavender outlines on the date/time and snooze buttons (04, 05), and lavender selected segments
   (`secondary` was set to purple `#5B4B8A`) on a green app. The snackbar has no brand inverse colours.
3. **Colour is the only signal in three places** (WCAG 1.4.1): the priority stripe (green/amber/red, only "high" has an
   icon), the pink "overdue" card background, and the permissions screen, whose intro literally says «الأحمر ضروري؛
   والبنفسجي يُنصح به».
4. **The primary action is weak and badly placed.** The FAB is mint `primaryContainer` on near-white, so it barely
   separates from the page, and in RTL it sits bottom-left, the farthest point from a right thumb (01). In the editor
   «حفظ» appears **twice** (top-bar text button and bottom button, 04) and the delete icon sits next to it in the top bar
   (destructive next to primary). On the reminder screen «تم» is in the middle of the screen, not in the thumb zone (05).
5. **Tabs hide what matters.** Overdue, Today, Upcoming and Done are four tabs; the user must switch to see what is next.
   The badge «2» overlaps the tab label (01). A count of what is overdue should not need a badge if overdue is simply at
   the top.
6. **Mixed separators.** «اليوم، 6:04 م · فات موعده…» uses the Arabic comma and `·` in one line; `·` also joins the
   notification text and the widget row uses «—» and «●».
7. **Uneven rhythm.** 14 dp, 12 dp, 10 dp, 6 dp and 20 dp gaps; cards 12 dp corners next to 28 dp segmented pills;
   section headers at three different sizes. Not on an 8 dp grid.
8. **Settings is one long scroll of radio buttons and dropdown fields** (06): 5 radios plus 3 dropdowns before the first
   switch; the «السماح بالعمل في الخلفية» entry looks like plain text, not a control.
9. **Mixed icon families**: `Icons.Filled.*` with one `Icons.Outlined.Circle`; the snooze buttons and links have no icons
   at all while some rows have three.

### 1.2 Screen by screen

| Screen | What looks wrong | Cause in code |
|---|---|---|
| Home (01, 02, 03, 08) | Four tabs; the time is secondary text; a 6 dp coloured stripe for priority; pink overdue cards; half the screen empty; FAB faint and bottom-left in RTL. | `PrimaryScrollableTabRow`; `TaskRow` with `Card` + stripe `Box`; `errorContainer.copy(alpha=.55f)`; `ExtendedFloatingActionButton` default colours and position. |
| Editor (04) | Save twice; delete icon beside Save; date wraps onto two lines inside an outlined pill («السبت 14 نوفمبر / 2026»); two dropdown fields and two segmented rows of equal weight; lavender selected segments. | `TopAppBar.actions` with `TextButton(save)` + `IconButton(delete)`; `OutlinedButton` for date/time; `Choice`/`Segments`. |
| Reminder screen (05) | Small bell, title, then time in body size; «تم» mid-screen; snooze chips wrap 3/2/1 with one disabled chip in the first row; two plain text links at the bottom; lower third empty. | `Column(verticalScroll)` with everything stacked; `FlowRow` of `OutlinedButton`s. |
| Settings (06) | Radios inline; dropdowns as form fields; no groups; inconsistent headers. | `Radios`, `Choice`, `Section`. |
| Allow background (07) | Cards of equal weight; status only by icon colour; «اسمح» text buttons float under the text; purple "recommended" cards. | `CheckItem` with `secondaryContainer` (purple). |

---

## 2. The design system

### 2.1 Colour: a complete Material 3 scheme from the brand

**Seed: the icon's forest-green tile `#2F6249`** (HCT hue 163.5, chroma 29.7, tone 37.5). **Tertiary: the icon's lavender
bell `#C5B3FF`.** Generated with Google's `material-color-utilities` (variant TONAL_SPOT; primary chroma 36, secondary
16 on the seed hue, tertiary from the bell, neutrals chroma 5/8 on the seed hue, standard error), by
`design/tools/palette.mjs`. Every role is filled; nothing falls back to the baseline purple.

| Role | Light | Dark |
|---|---|---|
| primary / onPrimary | `#246A4B` / `#FFFFFF` | `#8FD5AF` / `#003823` |
| primaryContainer / onPrimaryContainer | `#ABF2CA` / `#005235` | `#005235` / `#ABF2CA` |
| inversePrimary | `#8FD5AF` | `#246A4B` |
| secondary / onSecondary | `#4D6356` / `#FFFFFF` | `#B4CCBC` / `#203529` |
| secondaryContainer / onSecondaryContainer | `#D0E8D8` / `#364B3F` | `#364B3F` / `#D0E8D8` |
| tertiary / onTertiary | `#645498` / `#FFFFFF` | `#CDBDFF` / `#352466` |
| tertiaryContainer / onTertiaryContainer | `#E8DEFF` / `#4B3C7E` | `#4B3C7E` / `#E8DEFF` |
| error / onError | `#BA1A1A` / `#FFFFFF` | `#FFB4AB` / `#690005` |
| errorContainer / onErrorContainer | `#FFDAD6` / `#93000A` | `#93000A` / `#FFDAD6` |
| background = surface / onSurface | `#F7FAF5` / `#181D1A` | `#101412` / `#E0E3DE` |
| surfaceVariant / onSurfaceVariant | `#DCE5DD` / `#404943` | `#404943` / `#C0C9C1` |
| outline / outlineVariant | `#707972` / `#C0C9C1` | `#8A938C` / `#404943` |
| surfaceDim / surfaceBright | `#D7DBD6` / `#F7FAF5` | `#101412` / `#363A37` |
| surfaceContainerLowest / Low | `#FFFFFF` / `#F1F5EF` | `#0B0F0C` / `#181D1A` |
| surfaceContainer / High / Highest | `#EBEFEA` / `#E5E9E4` / `#E0E3DE` | `#1C211E` / `#272B28` / `#313632` |
| inverseSurface / inverseOnSurface | `#2D312E` / `#EEF2EC` | `#E0E3DE` / `#2D312E` |
| surfaceTint = primary; scrim | `#246A4B`; `#000000` | `#8FD5AF`; `#000000` |
| *Fixed brand (mark only, both themes)* | tile `#123B2A`, rim `#2F6249`, bell `#C5B3FF`, accent `#B9F34A` | same |

**Contrast** (WCAG 2.2; full table in `design/tools/contrast-report.md`, 25 pairs per theme + brand). **Every text pair
≥ 4.5:1 and every UI boundary ≥ 3:1.** Highlights:

| Pair | Light | Dark |
|---|---|---|
| body `onSurface` / `surface` | 16.22 | 14.34 |
| row title and time `onSurface` / `surfaceContainer` | 14.69 | 12.61 |
| metadata `onSurfaceVariant` / `surfaceContainer` | 8.02 | 9.62 |
| overdue time `error` / `surfaceContainer` | 5.56 | 9.62 |
| FAB / «تم» label `onPrimary` / `primary` | 6.49 | 7.74 |
| text button `primary` / `surface` | 6.16 | 10.88 |
| priority tag `onTertiaryContainer` / `tertiaryContainer` | 7.27 | 7.27 |
| warning banner `onErrorContainer` / `errorContainer` | 7.24 | 7.24 |
| snackbar action `inversePrimary` / `inverseSurface` | 7.74 | 5.01 |
| Done circle, chip, field outline `outline` / `surfaceContainer` (UI, 3:1) | 3.87 | 5.16 |
| switch-off border `outline` / `surfaceContainerHighest` (UI, 3:1) | 3.47 | 3.89 |
| brand bell `#C5B3FF` / tile `#123B2A` (graphic) | 6.69 | 6.69 |

`outlineVariant` is used **only** for decorative edges (exempt). The notification accent (`setColor`) is light
`primary` `#246A4B` (6.49:1 on a white shade).

**Usage rules**

- **FAB and filled buttons use `primary`**, never `primaryContainer` (the mint FAB was the main reason Home looked faint).
- **Meaning colours, always paired with text and an icon:** `error` = overdue / essential missing; `tertiary` (lavender,
  the bell) = priority and the reminder screen's bell; `primary` = ready / done. Nothing is told by colour alone.
- Tonal levels replace shadows: page `surface`; rows and settings groups `surfaceContainer`; bottom action bars
  `surfaceContainer`; the reminder screen's action dock `surfaceContainerLow`; dialogs `surfaceContainerHigh`;
  search field `surfaceContainerHigh`; snackbar `inverseSurface`.
- **Dynamic colour (Material You): not offered in this release** (V7). The theme setting stays «حسب الهاتف / فاتح / داكن».

### 2.2 Type

**Font:** Noto Sans Arabic 400/500/700 (SIL OFL 1.1), already bundled in `res/font/` and covering Latin. Keep it.
**Letter spacing 0 everywhere** (Material's 0.1–0.5 sp break Arabic joining). **No italics, no all-caps.**

| Token | Size / line (sp) | Weight | Used for |
|---|---|---|---|
| displayLarge | 57 / 72 | 500 | reminder screen: the due time |
| headlineMedium | 28 / 40 | 700 | reminder screen: the title |
| headlineSmall | 24 / 36 | 700 | empty-state title, dialog titles |
| titleLarge | 22 / 32 | 700 | top-bar titles |
| *rowTime* (custom) | 22 / 30 | 500, `tnum` | the time on each reminder row |
| titleMedium | 17 / 26 | 500 | (spare) list headline when no time |
| titleSmall | 15 / 22 | 700 | section and group headers |
| bodyLarge | 16 / 28 | 400 | row title, field text, body |
| bodyMedium | 14 / 24 | 400 | supporting text, day label on a row |
| bodySmall | 13 / 20 | 400 | row metadata, hints (never smaller for Arabic) |
| labelLarge | 15 / 22 | 500 | buttons, FAB |
| labelMedium | 13 / 18 | 500 | tags, status labels |
| labelSmall | 12 / 16 | 500 | counters only |

**Arabic and number rules**

- `lineHeightStyle = LineHeightStyle(Alignment.Center, Trim.None)` on every style (already in `Theme.kt`); **no fixed
  heights** on text containers (`heightIn(min = …)` only) and no `clip` on title parents, so the dots and tails of
  «ي»، «م»، «ج» are never cut. Line height in **sp** so it scales with the text.
- **Digits: Western 0–9 only, also in Arabic** (D-009; owner rule D-071). Times and counts use `font-feature "tnum"`
  (`TextStyle(fontFeatureSettings = "tnum")`) so a column of times lines up.
- **Separator:** the Arabic comma «، » between metadata in Arabic (`R.string.list_separator`); ` · ` stays in English only.
  Fix the three places that hard-code `" · "`: `Home.kt` (TaskRow), `Alert.kt`, `Notifier.kt` (line 105).
- AM/PM: the time is drawn as two runs, the digits in *rowTime* / displayLarge and «م/ص» ("PM/AM") at 15 sp / 28 sp,
  from `DateTimeFormatter` parts (`h:mm` + `a`); 24-hour phones show `H:mm` only.
- Latin runs inside Arabic (Noto, AndroidX, zip) are wrapped with `BidiFormatter.unicodeWrap`.

### 2.3 Shape, spacing, elevation

| Token | Radius | Used for |
|---|---|---|
| extraSmall | 4 dp | inner corners in a group (with `groupInner`) |
| small | 8 dp | text fields, tags, snackbar |
| medium | 12 dp | menus |
| large | 16 dp | FAB, banners, note card, notification-style cards |
| extraLarge | 28 dp | dialogs, the reminder screen's action dock (top corners) |
| `groupOuter` / `groupInner` | 20 dp / 4 dp | grouped lists (reminder sections, settings, permissions), 2 dp gaps |
| full | 50 % | buttons, segmented buttons, pills, weekday toggles, Done circle |

**8 dp grid, 4 dp half-step.** `Dimens`: `xs 4`, `s 8`, `m 12`, `l 16`, `xl 24`, `xxl 32`.
- Screen margin 16 dp; group header 20 dp above, 8 dp below, 32 dp start inset; sections 24 dp apart.
- Reminder row: start 4 dp (the Done target reaches the edge), end 16 dp, top 8, bottom 10, min height 72 dp.
- Settings row: 16 dp padding, min 64 dp; leading icon 24 dp, 16 dp gap.
- Buttons: min height 48 dp (56 dp single full-width primary; 64 dp «تم» on the reminder screen); padding 24 dp;
  icon 20 dp + 8 dp gap.
- Content under the FAB: bottom padding 56 + 16 + 16 = **88 dp**.
- Every touch target ≥ 48 × 48 dp (the Done circle is 22 dp drawn inside a 48 dp target; weekday toggles 44 dp drawn in 48 dp).

**Elevation:** tonal colour only. Shadows only on the FAB (level 3), dialogs and menus (level 3, with scrim) and the
snackbar (level 1). No shadow on rows, groups or bars.

### 2.4 Icons

**One family: `Icons.Rounded.*`** from `material-icons-extended` (Apache-2.0, already a dependency), and
**`Icons.AutoMirrored.Rounded.*`** where the symbol has a direction. No `Icons.Filled/Outlined` mixing. 24 dp in bars and
rows, 20 dp in buttons, 16–18 dp in tags and metadata. Every icon-only button has a `contentDescription` **and** a
long-press `PlainTooltip` with the same text (`TooltipBox`, stable in material3 1.4). (Material Symbols vector drawables
would be the long-term choice; deferred, V14.)

| Use | Icon | Mirror in RTL |
|---|---|---|
| back | `AutoMirrored.Rounded.ArrowBack` | **yes** |
| close (editor) | `Rounded.Close` | no |
| search / settings / more | `Rounded.Search` / `Rounded.Settings` / `Rounded.MoreVert` | no (the magnifier is not mirrored) |
| new reminder (FAB) | `Rounded.Add` | no |
| Done circle / done | `Rounded.RadioButtonUnchecked` → `Rounded.CheckCircle` | no |
| overdue | `Rounded.WarningAmber` | no |
| repeat | `Rounded.Repeat` | no (Material does not mirror repeat) |
| priority high / medium | `Rounded.Flag` / `Rounded.OutlinedFlag` | no |
| category | `AutoMirrored.Rounded.Label` | **yes** |
| timed switch / date / time | `Rounded.Alarm` / `Rounded.Event` / `Rounded.Schedule` | no (clock hands never mirror) |
| early notice | `Rounded.Notifications` | no |
| reminder screen bell | `Rounded.NotificationsActive` | no |
| open to edit | `Rounded.Edit` | no |
| expand / collapse Done | `Rounded.ExpandMore` / `Rounded.ExpandLess` | no |
| row chevron | `AutoMirrored.Rounded.KeyboardArrowRight` | **yes** |
| ready / needed / recommended | `Rounded.CheckCircle` / `Rounded.Error` / `Rounded.Info` | no |
| unused-app pause / maker steps / DND | `Rounded.HourglassEmpty` / `Rounded.PhoneAndroid` / `Rounded.DoNotDisturbOn` | no |
| language / theme / month names / week start | `Rounded.Language` / `Rounded.Contrast` / `Rounded.CalendarMonth` / `Rounded.DateRange` | no |
| sound | `AutoMirrored.Rounded.VolumeUp` | **yes** |
| sound again | `Rounded.NotificationsActive` | no |
| hide title on lock screen | `Rounded.VisibilityOff` | no |
| save a copy / restore from file / safety copies | `Rounded.Download` / `Rounded.SettingsBackupRestore` / `Rounded.History` | no |
| trash / restore / delete forever | `Rounded.Delete` / `Rounded.RestoreFromTrash` / `Rounded.DeleteForever` | no |
| about / privacy / offline | `Rounded.Info` / `Rounded.VerifiedUser` / `Rounded.CloudOff` | no |
| notification small icon | `res/drawable/ic_stat_bell.xml` (exists) | no |

The mockups use hand-drawn stand-ins in the same style; the app must use the icons above.

### 2.5 Motion

M3 standard tokens; durations are multiplied by the system animator scale automatically.

| Transition | Spec |
|---|---|
| Screen push/pop | shared-axis X: 300 ms emphasised-decelerate in, 200 ms accelerate out; direction follows layout direction (forward slides in from the left in RTL). Predictive back is on by default with targetSdk 36: keep `BackHandler` usage compatible. |
| Reminder screen in | fade + scale 0.92 → 1, 250 ms. No looping, pulsing or flashing (WCAG 2.3.1); the sound and the notification do the alerting. |
| Done on a row | circle fills (150 ms), haptic `HapticFeedbackType.Confirm`, then the row leaves with `Modifier.animateItem()` (fade + collapse, 250 ms) and the snackbar «أُنجز التذكير — تراجع» appears. |
| Done section | `AnimatedVisibility(expandVertically + fadeIn)`, 200 ms. |
| FAB | label collapses to icon while the list scrolls down (`expanded = !listState.isScrollingDown`), expands on scroll up or at the top. |
| Dialogs, snackbar, switch, segments | component defaults. |

**Reduced motion:** read `Settings.Global.ANIMATOR_DURATION_SCALE` once in `MainActivity` into a `LocalReducedMotion`
CompositionLocal. When it is 0: crossfade instead of slides, no scale-in on the reminder screen, `animateItem` with
`snap()`. Haptics stay.

---

## 3. Per-screen layout and button placement

Legend: **P** primary, **S** secondary, **T** tertiary. Positions for RTL (Arabic): start = right, end = left; LTR mirrors.

**Global rules (council verdicts, §6)**
- Exactly **one primary action per screen**, in the bottom thumb zone.
- **Paired buttons: secondary first (start), primary at the end**, both 50 % wide (`FlowRow`, they stack at large text
  with the primary on top). A lone primary is full width.
- **No NavigationBar**: the app has no top-level destinations; Settings, Trash and About are pushed screens.
- Destructive actions live in ⋮ menus, never beside a primary; they are undoable (snackbar) or confirmed (dialog).
- Labelled bottom actions only, at most 5 in any bar (the app uses 1–2).

### 3.1 Home — `home-light/dark.png`, `home-ar-200.png`, `home-en-light.png`, `home-empty-*.png`, `compare-home*.png`

**Decision: one list with sections, not tabs** (V1). Order: **المتأخرة** (only when not empty), **اليوم** (always; shows
«لا شيء اليوم.» inline when empty and other sections exist), **القادمة** (only when not empty), **المنجزة** (collapsed
header «المنجزة 12», only when not empty). Overdue is never hidden behind a tab, the answer to "what's next" is the first
screen, and TalkBack users jump between section headings. Search results use the same rows in one unsectioned list.

| Level | Action | Position | Component |
|---|---|---|---|
| P | **«تذكير جديد»** | bottom centre, 16 dp above the gesture bar | `ExtendedFloatingActionButton(containerColor = primary)`, `floatingActionButtonPosition = FabPosition.Center`, icon `Add`; collapses on scroll; hidden while searching |
| S | Done (per row) | row start (right in RTL) | 48 dp `IconToggleButton`-like `Box(Modifier.toggleable(role = Role.Checkbox))`, 22 dp circle `outline` 2 dp → `CheckCircle` in `primary`; cd «تم: <title>» |
| S | search | top bar end | `TooltipIconButton(Search)` → search mode (unchanged behaviour: top bar becomes a filled `TextField`, `surfaceContainerHigh`, `CircleShape`, placeholder «ابحث…», back arrow + clear) |
| S | settings | top bar end (last) | `TooltipIconButton(Settings)` |
| S | fix (banner) | banner end | `TextButton` «إصلاح» → Allow background |
| T | open a reminder | tap the row | due/overdue → reminder screen; otherwise → editor (unchanged) |
| T | done row ⋮ | done rows only, row end | `DropdownMenu`: «إعادة فتح»، «حذف» (snackbar undo) |
| T | expand Done | section header | whole header 48 dp, `Role.Button`, stateDescription «مطويّة / ظاهرة» |

- **Top bar:** small `TopAppBar`, the 32 dp brand mark (the icon: tile `#123B2A`, bell `#C5B3FF`, accent `#B9F34A`, as a
  vector drawable) at the start, title «ذكرني» (titleLarge), actions [search, settings]. Not centre-aligned.
- **Banner** (under the top bar, above the list, not scrolled away): `Surface(shape = large, color = errorContainer)`,
  16 dp margins, `WarningAmber` + «قد لا تصل التذكيرات في وقتها.» + `TextButton` «إصلاح». The monthly backup nudge uses
  the same component on `secondaryContainer` with «احفظ نسخة» and a close `IconButton` («ليس الآن»). Only one banner
  at a time (health wins).
- **Section header:** titleSmall, `onSurface` (Overdue: `error` + `WarningAmber` 20 dp), count in labelMedium
  `onSurfaceVariant`, `semantics { heading() }`. Padding 20/8 dp, start inset 32 dp.
- **Reminder row** (inside a section group: `surfaceContainer`, 20/4 dp corners, 2 dp gaps; no dividers, no stripe):
  - start: the Done target (48 dp);
  - line 1 (*rowTime* 22 sp): the time «2:00» + «م» 15 sp; then, 10 dp after, a bodyMedium label: in **Overdue**
    «متأخر ساعتين» (`late_short`, `error`, and the day first when not today: «أمس، متأخر 22 ساعة»); in **Upcoming** the
    day «غدًا» / «الاثنين 16 نوفمبر» (`onSurfaceVariant`); in **Today** nothing; a reminder without a time shows
    «بلا وقت» in bodyMedium instead of the time. The line is a `FlowRow`, so at large text the label wraps under the time.
  - line 2: the title, bodyLarge, `maxLines = 2`, ellipsis;
  - line 3 (only if any): bodySmall metadata, 16 dp icons, 8 dp gaps: `Repeat` + repeat text; priority **tag**
    (high: `Flag` + «مهم» on `tertiaryContainer`; medium: `OutlinedFlag` + «متوسط», 1 dp `outline`; normal: nothing);
    category `Label` + name **only when not «شخصي»** (the default).
  - Overdue time and label are `error`; the meaning is also in the words and the section header.
  - Done rows: the check is filled, title and time in `onSurfaceVariant`, line 1 shows «أُنجز أمس، 6:00 م».
  - Semantics: the row is one `clickable` node (`Role.Button`) whose contentDescription joins title, time, late, repeat,
    priority with «، »; it carries a `customActions` entry «تم» (so TalkBack users complete without hunting the circle).
    The Done target is its own focusable node.
- **List padding:** 0 dp horizontal outside the groups (groups carry 16 dp margins), bottom **88 dp**.
- **Empty state** (no reminders at all): centred above the FAB: 120 dp `tertiaryContainer` disc with
  `NotificationsActive` (56 dp, `onTertiaryContainer`), «لا تذكيرات بعد» headlineSmall, «أضف أول تذكير، وسيصلك في وقته
  حتى والهاتف مقفل.» bodyLarge `onSurfaceVariant`. No extra button: the FAB is the action.
- **Snackbars** (above the FAB): «أُنجز التذكير — تراجع», «نُقل إلى السلة — تراجع» (also after deleting in the editor),
  `SnackbarDuration.Long`, with dismiss action.

### 3.2 Reminder editor (new / edit) — `edit-light/dark.png`, `edit-repeat-light/dark.png`, `compare-edit.png`

| Level | Action | Position | Component |
|---|---|---|---|
| P | **«حفظ»** | bottom, full width, 56 dp, rides above the keyboard | `BottomActionBar { Button(Modifier.fillMaxWidth()) }` with `Check` icon; `imePadding()` on the bar |
| S | close | top bar start | `TooltipIconButton(Close)`; if anything changed → `AlertDialog` «تجاهل التغييرات؟» with **S** «متابعة التعديل», **P** «تجاهل» (end) |
| T | ⋮ «حذف» (edit mode only) | top bar end | `DropdownMenu`; deleting moves to the trash, closes the editor and shows «نُقل إلى السلة — تراجع» on Home. No confirm dialog: undo + 30-day trash. |

- **No Save in the top bar** (removes the duplicate) and **no delete icon** beside it.
- Content (`verticalScroll`, 16 dp margins, 20 dp between blocks):
  1. `OutlinedTextField` «العنوان» (keeps the label text; error text under it as today), max 3 lines.
  2. `OutlinedTextField` «ملاحظة», minLines 2, maxLines 8.
  3. Group header «الموعد», then one group (20/4 dp):
     - switch row `Alarm` «ذكّرني في وقت محدد» (the whole row `toggleable(role = Switch)`);
     - when on: row `Event` overline «التاريخ», headline «السبت 14 نوفمبر 2026» → `DatePickerDialog`;
       row `Schedule` overline «الوقت», headline «10:00 م» → `TimePicker` in an `AlertDialog` (as today);
       row `Repeat` overline «التكرار», headline the choice → **single-choice dialog** (8 options; «كل بضع ساعات» shows
       the supporting line «خاص بأندرويد: يصبح لمرة واحدة في ذكرني لويندوز»);
       for «أيام محددة من الأسبوع»: an extra row of **7 round toggles** (44 dp in 48 dp targets, `toggleable`,
       `Role.Checkbox`), first day = the week-start setting, label = the **narrow weekday letter** from `java.time`
       (`TextStyle.NARROW`: س ح ن ث ر خ ج), contentDescription = the full name; selected = `primary` fill + bold; a
       bodySmall summary under them «كل الأحد، الثلاثاء، الخميس» (`repeat_days_on`);
       for «كل بضعة أيام» / «كل بضع ساعات»: a stepper row (existing `Stepper`, 48 dp buttons);
       row `Notifications` overline «تنبيه مبكر», headline «قبل 15 دقيقة» → single-choice dialog;
     - the past-time warning stays as bodyMedium `error` with a `WarningAmber` icon under the group.
  4. Group header «التفاصيل»: «الأهمية» (titleSmall) + `SingleChoiceSegmentedButtonRow` عادي / متوسط (`OutlinedFlag`) /
     مهم (`Flag`); «التصنيف» + segmented شخصي / عمل / دراسة. At font scale ≥ 1.5 a segmented row that would truncate
     becomes a single-choice dialog row (same pattern as Repeat).
- Rows that open dialogs have **no chevron** (they open a dialog, not a page).

### 3.3 Reminder screen (due, also over the lock screen) — `alert-light/dark.png`, `alert-ar-200.png`, `compare-alert.png`

Full screen, `surface`, `safeDrawingPadding()`; no back arrow (the screen stays until dealt with; Back = «لاحقًا»).

| Level | Action | Position | Component |
|---|---|---|---|
| P | **«تم»** | bottom row, **end**, 64 dp; **full width** when the reminder does not repeat | `Button`, `Check` 24 dp, labelLarge at 20 sp/700 |
| S | «تخطَّ هذه المرة» (repeats only) | bottom row, start, 64 dp | `OutlinedButton` |
| S | six snooze choices | the dock, above the pair, **2 columns × 3 rows**, 48 dp each | `OutlinedButton` (`onSurface` label); a choice that is not possible now («اليوم، 6:00 م» after 5 pm) stays in place **disabled** so the grid never jumps; «اختر وقتًا…» → date then time (existing `SnoozePicker`) |
| T | open to edit | top row end | `TooltipIconButton(Edit)`, cd «فتح التذكير للتعديل» (asks to unlock on the lock screen, as today) |
| T | «لاحقًا» | top row end (last) | `TextButton`; cd «لاحقًا (يبقى في الإشعارات)» |
| T | queue «1 من 3» | top row start | `Surface(secondaryContainer, CircleShape)` pill, labelLarge, `liveRegion = Polite` |

- **Hero (scrolls if needed), centred:** 64 dp `tertiaryContainer` disc with `NotificationsActive`; **the due time in
  displayLarge** («2:00» + «م» at 28 sp; `error` when overdue, `onSurface` when on time); then bodyLarge: «حان الوقت»
  (`onSurfaceVariant`) or `WarningAmber` + «اليوم، متأخر ساعتين» (`error`); then the **title** headlineMedium
  (heading, `liveRegion = Polite`); then a metadata row (repeat, priority tag); then the note in a `surfaceContainer`
  card (16 dp radius) when there is one.
- **The dock:** `Surface(color = surfaceContainerLow, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))`
  holding «ذكّرني مرة أخرى» (titleSmall `onSurfaceVariant`), the 2×3 grid and the pair; 16 dp padding, 12 dp gaps,
  `navigationBarsPadding()`. The dock never scrolls; the hero scrolls above it. At 200 % text the hero scrolls and the
  dock keeps «تم» visible (see `alert-ar-200.png`); if the pair does not fit side by side it stacks, «تم» on top.
- Lock-screen privacy on: the title shows «تذكير» and the note is hidden until unlock (behaviour unchanged).
- `Modifier.semantics { paneTitle = title }` so TalkBack announces the reminder when the screen appears.

### 3.4 «السماح بالعمل في الخلفية» — `background-light/dark.png`, `compare-background.png`

Top bar: back + title. Intro (bodyMedium `onSurfaceVariant`) **without colour words**: «لكي يصلك كل تذكير في وقته حتى
والهاتف مقفل، اسمح بما ليس جاهزًا. كل بند يفتح الإعداد الخاص به.»

Three groups (20/4 dp), each header with a status count on its end («1 من 2 جاهز»):

| Group | Items (existing checks) | Row when not ready | Row when ready |
|---|---|---|---|
| **ضروري** | الإشعارات · صوت التذكيرات (only when notifications are on and the channel is muted) · المنبّهات في وقتها | `errorContainer` row, `Error` icon, status «مطلوب», **`Button` «اسمح»** | `surfaceContainer`, `CheckCircle` in `primary`, status «جاهز», no button |
| **يُنصح به** | شاشة التذكير الكاملة · بلا قيود على البطارية | `Info` in `tertiary`, status «غير مفعّل», `FilledTonalButton` «اسمح» | as above |
| **نصائح** | إيقاف التطبيق غير المستخدم · عدم الإزعاج (when on) · خطوات خاصة بهواتف <maker> | icon in `onSurfaceVariant`, status «تحقق بنفسك» (none for maker), `OutlinedButton` «افتح الإعدادات» | — |

- Row anatomy: leading 24 dp status icon; headline titleSmall-weight (16 sp/500) with the **status text on the same line
  at the end** (labelMedium, also in the icon's contentDescription); supporting bodyMedium; the action button under the
  text, start-aligned, 40 dp visual in a 48 dp target. One action per row.
- Maker steps keep their text; the path arrows «←» read correctly in RTL (English keeps «→»).
- **Bottom bar (first start only):** «متابعة» full width. It is an `OutlinedButton` while an essential item is missing
  (that row's «اسمح» is the screen's primary) and becomes a filled `Button` when all essentials are ready. Opened from
  Settings or the banner, there is no bottom bar (back returns).
- Re-check on `ON_RESUME` (exists) and announce a change of status politely.

### 3.5 Settings, Trash, About — `settings-*.png`, `settings-dialog-*.png`, `trash-*.png`, `about-*.png`, `compare-settings.png`

**Settings** (no primary; changes apply at once). Grouped rows: 24 dp leading icon (`onSurfaceVariant`), headline
16 sp/500, supporting = the current value or hint (bodySmall), trailing `Switch` for booleans. Radio choices move into
**single-choice `AlertDialog`s** (56 dp rows, `selectableGroup`, tap applies and closes, «إلغاء» TextButton).

| Group | Rows |
|---|---|
| (top, alone) | **السماح بالعمل في الخلفية**: `errorContainer` + `WarningAmber` + «إذن ضروري واحد غير مفعّل» (plural) when an essential is off; otherwise `CheckCircle` + «كل الأذونات الضرورية جاهزة»; trailing chevron |
| **العرض** | اللغة «العربية» (dialog) · السمة «حسب الهاتف» (dialog) · أسماء الأشهر «تشرين الثاني» (dialog, Arabic only) · أول يوم في الأسبوع «السبت» (dialog) |
| **التنبيه** | صوت التذكير (switch) · إعادة التنبيه بالصوت «متوقف» (dialog) · إخفاء العنوان على شاشة القفل (switch) |
| **بياناتك** | حفظ نسخة «ملف zip تختار مكانه. الملف غير مشفّر.» · استعادة من ملف · نسخ الأمان (only when there are any; a dialog listing them, tap to restore with a confirm) · سلة المحذوفات (trailing count) |
| **حول** | حول ذكرني «الإصدار 0.1.0» |

**Trash:** top bar back + «سلة المحذوفات» + ⋮ «إفراغ السلة» (→ `AlertDialog` «حذف كل ما في السلة نهائيًا؟» with
**S** «إلغاء», **P** «حذف نهائي» in `error`). Hint bodyMedium. One group; each row: title (bodyLarge), «حُذف أمس، 8:45 م»
(bodySmall), trailing `TextButton` «استعادة» + ⋮ «حذف نهائي» (confirm dialog). Snackbar after restore «استُعيد
التذكير — تراجع». Empty: «السلة فارغة.» centred.

**About:** 72 dp brand mark, «ذكرني» headlineSmall, «الإصدار 0.1.0» bodyMedium, a pill «يعمل دون إنترنت» (`CloudOff`);
a `surfaceContainer` card with `VerifiedUser` and the privacy text; group «التراخيص» with one row per licence (Noto Sans
Arabic — SIL OFL 1.1; Material icons — Apache 2.0; AndroidX, Jetpack Compose, Kotlin, kotlinx — Apache 2.0);
«© 2026 FastKiwi» bodySmall.

### 3.6 Notification and widget — `notification-light/dark.png`, `widget-light/dark.png`

**Notification** (system template; the app sets content, accent, actions):
- `setColor(0xFF246A4B)` (light primary), small icon `ic_stat_bell`, category reminder (unchanged).
- Title = the reminder title. Text = «حان الوقت، 2:00 م، كل يوم» or «متأخر 22 ساعة» joined with
  `R.string.list_separator` (**not** `" · "`); BigText adds the note on a new line.
- Actions in this order: **«تم»**, **«أجّل 10 دقائق»** (new string `snooze_for` with the first applicable preset's label,
  so the user knows what Snooze does). Android shows the first action first; «تم» is the main one.
- Lock screen with privacy on: «تذكير» / «افتح القفل لرؤيته» with the same actions (exists). Summary when more than 8:
  «12 تذكيرًا ينتظر» (exists).

**Widget** (RemoteViews, 4×2 default): `surface` background (theme-aware via `res/values/colors.xml` and
`values-night/colors.xml`; on Android 12+ radius `@android:dimen/system_app_widget_background_radius`, else 24 dp).
Header: 28 dp brand mark, «ذكرني» 16 sp bold, and a 48 dp `primary` circle with `ic_add` (cd «تذكير جديد»).
Up to 4 rows in a 16/4 dp group on `surfaceContainer`: a 76 dp time column (15 sp/600, `tnum`; a 12 sp label under it:
«متأخر» in `error`, «اليوم», «غدًا», or the short date) and the title (15 sp, one line, ellipsis); an overdue row also
ends with a 20 dp warning icon. Empty: «لا تذكيرات قادمة». Remove the «●» and «—» text decorations.

### 3.7 Dialogs and snackbars

- `AlertDialog` (`surfaceContainerHigh`, 28 dp): title headlineSmall, body bodyMedium, `TextButton`s at the end:
  **dismiss first, confirm last** (confirm at the far end = left in RTL). Destructive confirms use `error` text.
- Single-choice dialogs: title + radio rows (whole row `selectable`, 56 dp) + «إلغاء». A tap applies and closes.
- Date and time: the stock `DatePickerDialog` / `TimePicker` (already used); they follow the colour scheme.
- Snackbar: `inverseSurface`, action «تراجع» in `inversePrimary`, `SnackbarDuration.Long`, above the FAB or bottom bar.

---

## 4. Mockups

All under `design/shots/`, 412×915 dp at 2× (824×1830 px). Arabic RTL unless named `-en`.

| Screen | Light | Dark | Extra / before-after |
|---|---|---|---|
| Home | `home-light.png` | `home-dark.png` | `home-ar-200.png`, `home-en-light.png`, `compare-home.png`, `compare-home-en.png` |
| Home, empty | `home-empty-light.png` | `home-empty-dark.png` | |
| Editor | `edit-light.png` | `edit-dark.png` | `compare-edit.png` |
| Editor + repeat dialog | `edit-repeat-light.png` | `edit-repeat-dark.png` | |
| Reminder screen | `alert-light.png` | `alert-dark.png` | `alert-ar-200.png`, `compare-alert.png` |
| Allow background | `background-light.png` | `background-dark.png` | `compare-background.png` |
| Settings | `settings-light.png` | `settings-dark.png` | `compare-settings.png` |
| Settings + theme dialog | `settings-dialog-light.png` | `settings-dialog-dark.png` | |
| Trash | `trash-light.png` | `trash-dark.png` | |
| About | `about-light.png` | `about-dark.png` | |
| Notification | `notification-light.png` | `notification-dark.png` | |
| Widget | `widget-light.png` | `widget-dark.png` | |

The 200 % shots apply Android 14's non-linear font scaling table for 2.0 (8→16, 14→26, 18→30, 24→36, 30→38 sp…) with
line heights kept proportional; icons do not scale (dp). Mockup fonts: Noto Sans Arabic VF + Noto Sans (OFL).

---

## 5. Implementation plan

**Before step 1:** run `gradlew :app:testDebugUnitTest` (green). **After every step:** run it again; each step is one
commit and keeps the suite green. Work on a `claude/design-1` branch; the lead merges on the Sunday train (D-035).

### Strings the UI tests depend on (`AppUiTest.kt`) — keep them, exactly once per screen

| Screen | Must stay |
|---|---|
| Home (ar) | «ذكرني» (top-bar title); «دواء الضغط»; section header «اليوم» (exact, once: rows of today show no «اليوم»); «القادمة»; «تذكير جديد» (FAB label, unmerged tree); contentDescription «الإعدادات», «بحث»; search placeholder «ابحث…» |
| Home (en) | "Remember Me"; header "Today" (once); "Team meeting" |
| Editor | label «العنوان»; «حفظ» (now exactly one node) |
| Settings | «السماح بالعمل في الخلفية» (the top status row's headline) |
| Reminder screen | «1 من 2»; «10 دقائق»; «تم» (exactly one node: the primary button) |

### Steps

1. **Theme tokens (no layout change).** In `ui/Theme.kt`:
   - Replace `Light`/`Dark` with the generated schemes (copy from `design/tools/palette.kt.txt`):
     ```kotlin
     private val Light = lightColorScheme(
         primary = Color(0xFF246A4B), onPrimary = Color(0xFFFFFFFF),
         primaryContainer = Color(0xFFABF2CA), onPrimaryContainer = Color(0xFF005235), inversePrimary = Color(0xFF8FD5AF),
         secondary = Color(0xFF4D6356), onSecondary = Color(0xFFFFFFFF),
         secondaryContainer = Color(0xFFD0E8D8), onSecondaryContainer = Color(0xFF364B3F),
         tertiary = Color(0xFF645498), onTertiary = Color(0xFFFFFFFF),
         tertiaryContainer = Color(0xFFE8DEFF), onTertiaryContainer = Color(0xFF4B3C7E),
         error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
         errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF93000A),
         background = Color(0xFFF7FAF5), onBackground = Color(0xFF181D1A),
         surface = Color(0xFFF7FAF5), onSurface = Color(0xFF181D1A),
         surfaceVariant = Color(0xFFDCE5DD), onSurfaceVariant = Color(0xFF404943), surfaceTint = Color(0xFF246A4B),
         inverseSurface = Color(0xFF2D312E), inverseOnSurface = Color(0xFFEEF2EC),
         outline = Color(0xFF707972), outlineVariant = Color(0xFFC0C9C1), scrim = Color(0xFF000000),
         surfaceBright = Color(0xFFF7FAF5), surfaceDim = Color(0xFFD7DBD6),
         surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF1F5EF),
         surfaceContainer = Color(0xFFEBEFEA), surfaceContainerHigh = Color(0xFFE5E9E4),
         surfaceContainerHighest = Color(0xFFE0E3DE),
     )
     private val Dark = darkColorScheme(
         primary = Color(0xFF8FD5AF), onPrimary = Color(0xFF003823),
         primaryContainer = Color(0xFF005235), onPrimaryContainer = Color(0xFFABF2CA), inversePrimary = Color(0xFF246A4B),
         secondary = Color(0xFFB4CCBC), onSecondary = Color(0xFF203529),
         secondaryContainer = Color(0xFF364B3F), onSecondaryContainer = Color(0xFFD0E8D8),
         tertiary = Color(0xFFCDBDFF), onTertiary = Color(0xFF352466),
         tertiaryContainer = Color(0xFF4B3C7E), onTertiaryContainer = Color(0xFFE8DEFF),
         error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
         errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
         background = Color(0xFF101412), onBackground = Color(0xFFE0E3DE),
         surface = Color(0xFF101412), onSurface = Color(0xFFE0E3DE),
         surfaceVariant = Color(0xFF404943), onSurfaceVariant = Color(0xFFC0C9C1), surfaceTint = Color(0xFF8FD5AF),
         inverseSurface = Color(0xFFE0E3DE), inverseOnSurface = Color(0xFF2D312E),
         outline = Color(0xFF8A938C), outlineVariant = Color(0xFF404943), scrim = Color(0xFF000000),
         surfaceBright = Color(0xFF363A37), surfaceDim = Color(0xFF101412),
         surfaceContainerLowest = Color(0xFF0B0F0C), surfaceContainerLow = Color(0xFF181D1A),
         surfaceContainer = Color(0xFF1C211E), surfaceContainerHigh = Color(0xFF272B28),
         surfaceContainerHighest = Color(0xFF313632),
     )
     ```
   - Typography per §2.2:
     ```kotlin
     val AppTypography = Typography(
         displayLarge = style(57, 72, FontWeight.Medium),
         headlineMedium = style(28, 40, FontWeight.Bold), headlineSmall = style(24, 36, FontWeight.Bold),
         titleLarge = style(22, 32, FontWeight.Bold), titleMedium = style(17, 26, FontWeight.Medium),
         titleSmall = style(15, 22, FontWeight.Bold),
         bodyLarge = style(16, 28, FontWeight.Normal), bodyMedium = style(14, 24, FontWeight.Normal),
         bodySmall = style(13, 20, FontWeight.Normal),
         labelLarge = style(15, 22, FontWeight.Medium), labelMedium = style(13, 18, FontWeight.Medium),
         labelSmall = style(12, 16, FontWeight.Medium),
     )
     val RowTime = style(22, 30, FontWeight.Medium).copy(fontFeatureSettings = "tnum")
     ```
   - Shapes: `val AppShapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
     medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(28.dp))`;
     `val GroupOuter = 20.dp; val GroupInner = 4.dp`. Pass `shapes = AppShapes` to `MaterialTheme`.
   - `object Dimens { val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 24.dp; val xxl = 32.dp
     val fabClearance = 88.dp; val rowMin = 72.dp; val touch = 48.dp }`.
   - Delete `priorityColor()` (the stripe goes away in step 4).
2. **Strings** (`tools/strings_gen.py`, Arabic / English; regenerate `values{,-en}/strings.xml`):

   | Key | Arabic | English |
   |---|---|---|
   | `late_short` | متأخر %1$s | %1$s late |
   | `done_on` | أُنجز %1$s | Done %1$s |
   | `section_done_show` / `section_done_hide` (stateDescription) | مطويّة / ظاهرة | Collapsed / Expanded |
   | `empty_all_title` | لا تذكيرات بعد | No reminders yet |
   | `empty_all_body` | أضف أول تذكير، وسيصلك في وقته حتى والهاتف مقفل. | Add your first reminder. It will reach you on time, even when the phone is locked. |
   | `empty_today_inline` | لا شيء اليوم. | Nothing today. |
   | `schedule_section` / `details_section` | الموعد / التفاصيل | Schedule / Details |
   | `date` / `time` | التاريخ / الوقت | Date / Time |
   | `more_options` | خيارات أخرى | More options |
   | `discard_title` / `discard` / `keep_editing` | تجاهل التغييرات؟ / تجاهل / متابعة التعديل | Discard changes? / Discard / Keep editing |
   | `reopen` | إعادة فتح | Reopen |
   | `later` | لاحقًا | Later |
   | `hours_note_short` | خاص بأندرويد: يصبح لمرة واحدة في ذكرني لويندوز | Android only: becomes one-time in Remember Me for Windows |
   | `snooze_for` | أجّل %1$s | Snooze %1$s |
   | `group_essential` / `group_recommended` / `group_tips` | ضروري / يُنصح به / نصائح | Essential / Recommended / Tips |
   | `ready_count` | %1$s من %2$s جاهز | %1$s of %2$s ready |
   | `status_off` | غير مفعّل | Off |
   | `continue_` | متابعة | Continue |
   | `background_intro` (**new text**) | لكي يصلك كل تذكير في وقته حتى والهاتف مقفل، اسمح بما ليس جاهزًا. كل بند يفتح الإعداد الخاص به. | For every reminder to reach you on time, even when the phone is locked, allow what is not ready. Each item opens its own setting. |
   | `health_ok` | كل الأذونات الضرورية جاهزة | All essential permissions are on |
   | plural `essential_off` | one: إذن ضروري واحد غير مفعّل; two: إذنان ضروريان غير مفعّلين; few: %1$s أذونات ضرورية غير مفعّلة; many/other/zero: %1$s إذنًا ضروريًا غير مفعّل | one: %1$s essential permission is off; other: %1$s essential permissions are off |
   | `section_display` / `section_alerts` / `section_data` / `section_about` | العرض / التنبيه / بياناتك / حول | Display / Alerts / Your data / About |
   | `theme` | السمة | Theme |
   | `export_hint` | ملف zip تختار مكانه. الملف غير مشفّر. | A zip file saved where you choose. It is not encrypted. |
   | `restored` | استُعيد التذكير | Reminder restored |
   | `empty_trash_confirm` | حذف كل ما في السلة نهائيًا؟ لا يمكن التراجع عن ذلك. | Delete everything in the trash for good? This cannot be undone. |
   | `delete_forever_confirm` | حذف «%1$s» نهائيًا؟ | Delete "%1$s" for good? |
   | `offline` | يعمل دون إنترنت | Works offline |
   | `licence_font` / `licence_icons` / `licence_libs` | خط Noto Sans Arabic / أيقونات Material / مكتبات AndroidX وJetpack Compose وKotlin وkotlinx | Noto Sans Arabic font / Material icons / AndroidX, Jetpack Compose, Kotlin and kotlinx libraries |

   Reused as is: `tab_overdue/today/upcoming/done` (now section headers), `no_time`, `priority_*`, `cat_*`,
   `repeat_*`, `n_of_m`, `keep_waiting` (as the «لاحقًا» contentDescription), `open_task`, `fix`, `save_copy`,
   `not_now`, `marked_done`, `moved_to_trash`, `undo`, `list_separator`. `appearance` becomes unused.
3. **Shared components** (new `ui/Components.kt`; unused until step 4, so a safe commit):
   - `TooltipIconButton(icon: ImageVector, label: String, onClick, enabled = true)` — `TooltipBox` + `PlainTooltip` +
     `IconButton` with `contentDescription = label`.
   - `SectionHeader(text, count: Int? = null, tone: Normal|Error, onClick: (() -> Unit)? = null, expanded: Boolean? = null)`
     — heading semantics; clickable variant for Done.
   - `Group(content)` / `GroupItem(index, count, content)` — the 20/4 dp shape per position, 2 dp gaps, `surfaceContainer`.
   - `SettingsRow(icon, title, value?, onClick?, trailing?)`, `SwitchSettingsRow(icon, title, hint?, checked, onChange)`
     (whole row `toggleable(role = Role.Switch)`).
   - `ChoiceDialog(title, options: List<Pair<String,String>>, selected, onSelect, onDismiss, supporting: Map<String,String> = emptyMap())`.
   - `BottomActionBar(content)` — `Surface(color = surfaceContainer)` + `padding(16.dp, 12.dp)` + `navigationBarsPadding()`.
   - `ButtonPair(secondary: @Composable, primary: @Composable)` — `FlowRow`, secondary first, both `weight(1f)`, 12 dp gap.
   - `StatusBanner(icon, text, action, tone: Alarm|Info, onAction, onDismiss?)` — replaces `Banner`.
   - `PriorityTag(priority)` and `TimeText(instant, style = RowTime)` (digits + small «م/ص» run).
   - `BrandMark(size)` — `Image(painterResource(R.drawable.ic_brand_mark))`: add a vector drawable of the Windows icon
     (`Remember-Me/assets/icon.svg`: tile, bell, lime) with fixed colours.
   - `LocalReducedMotion` (CompositionLocal) — provided in `MainActivity`.
4. **Home** (`ui/Home.kt`): keep `tabOf`/`listFor`/`search`/`fold` (pure functions, tested); rename `Tab` to
   `Section` only if tests do not reference it (they do not). Replace the tab row with one `LazyColumn` built from
   `listFor` per section; `stickyHeader` is **not** used (keeps headers simple for TalkBack). New `ReminderRow`
   replaces `TaskRow` (§3.1). FAB: `containerColor = primary`, `FabPosition.Center`, `expanded` tied to scroll.
   Done section collapsed by default (`rememberSaveable`). Empty state when there are no active and no done tasks.
   `contentPadding(bottom = 88.dp)`. Banner via `StatusBanner`. Done rows: ⋮ with «إعادة فتح» / «حذف»
   (repeat copies with `sourceId` have no «إعادة فتح», as today).
   Home gains a parameter `pendingSnack: SnackEvent?` + `onSnackShown()` so a delete from the editor shows its undo here.
5. **Editor** (`ui/Edit.kt`): top bar close + ⋮ (edit mode); remove the top-bar Save and Delete; groups and dialog rows
   (§3.2) using `SettingsRow`, `ChoiceDialog`, weekday toggles (`L10n.weekdayName(…, short)` gains a `narrow` variant
   using `TextStyle.NARROW`); `BottomActionBar { Button(save) }` with `imePadding()`; discard dialog when
   `title/body/fields != initial`. `MainActivity.onDelete` sets the Home snack event with `Tasks.restore` as undo.
6. **Reminder screen** (`ui/Alert.kt`): `Column { top row; Column(weight(1f).verticalScroll) { hero }; Dock }` per §3.3;
   `presetLabel` unchanged; disabled presets stay in the grid; `TimeText(due, displayLarge)`; «تم» full width when
   `task.repeat == Repeat.NONE`, else `ButtonPair(OutlinedButton(skip), Button(done))` with 64 dp min height.
   `AlertActivity` keeps `showWhenLocked`/`turnScreenOn` (unchanged).
7. **Allow background** (`ui/Settings.kt` `BackgroundScreen`/`CheckItem`): three groups with counts, new row anatomy
   and button hierarchy (§3.4); `BackgroundScreen(…, firstRun: Boolean, onContinue)`; `MainActivity` passes
   `firstRun = true` when it pushed the screen during onboarding.
8. **Settings, Trash, About** (`ui/Settings.kt`): grouped rows + `ChoiceDialog`s; status row; safety copies behind a row
   + dialog; Trash confirm dialogs; About per §3.5. `Radios`, `LinkRow` and the old `Section` are removed once unused.
9. **Notification** (`alarm/Notifier.kt`): `setColor(0xFF246A4B.toInt())`; join with `R.string.list_separator` instead of
   `" · "` (line 105); action label `snooze_for` + the label of the preset the Snooze action uses.
10. **Widget** (`widget/ReminderWidget.kt`, `res/layout/widget_reminders.xml`, `res/drawable/widget_bg.xml`, new
    `res/values{,-night}/colors.xml` with `widget_surface`, `widget_container`, `widget_on_surface`, `widget_muted`,
    `widget_error`, `widget_primary`): header with brand mark; rows as two `TextView`s (time column 76 dp + title) and
    an optional warning `ImageView`; drop «●»/«—»; rows set `contentDescription` «<title>، <time>، متأخر».
11. **Icons sweep**: replace every `Icons.Filled.*`/`Icons.Outlined.*` with the `Icons.Rounded.*` /
    `Icons.AutoMirrored.Rounded.*` names in §2.4.
12. **Separators and digits sweep**: no `" · "` left in Arabic output; times through `TimeText`; counts as text
    (`n.toString()` is fine: digits are Western by rule).

### Tests to add or update (alongside the steps)

- `AppUiTest.homeEditorAlertSettingsInArabic`: the tab clicks become no-ops; replace
  `onNodeWithText("اليوم").performClick()` with `onNodeWithText("اليوم").assertIsDisplayed()` and, before asserting an
  item further down, scroll: `rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("دفع الإيجار"))`
  (LazyColumn does not compose off-screen rows; the banner and sections push «دفع الإيجار» below the fold).
  Same for `homeInEnglish` ("Today").
- New: **dark** (`@Config(qualifiers = "+night")`) and **font scale 2.0** (`RuntimeEnvironment.setFontScale(2f)`)
  screenshots for Home and the reminder screen; assert «تم» is displayed and fully inside the window at 2.0.
- New **touch-target test**: every node with a click/toggle action on Home, Editor, Reminder screen, Settings is
  ≥ 48 × 48 dp (`fetchSemanticsNodes().forEach { it.size … }`).
- New **RTL order test**: on the reminder screen of a repeating task, «تم» is to the **left** of «تخطَّ هذه المرة» in
  `ar` and to the right in `en`; in the editor exactly one «حفظ» node exists.
- New **semantics test**: a home row's merged contentDescription contains the title, the time and «متأخر» for an
  overdue task; the row exposes a custom action «تم».
- New **unit test**: Arabic metadata strings contain «، » and never `·` (`L10n` + `list_separator`).
- `EngineTest`/`Notifier` test (if present): the alert notification's second action label equals «أجّل 10 دقائق».

### Files touched (summary)

- New: `ui/Components.kt`, `res/drawable/ic_brand_mark.xml`, `res/values/colors.xml`, `res/values-night/colors.xml`.
- Edited: `ui/Theme.kt`, `ui/Home.kt`, `ui/Edit.kt`, `ui/Alert.kt`, `ui/Settings.kt`, `ui/L10n.kt` (narrow weekday,
  time parts), `MainActivity.kt` (snack event, first-run flag, reduced motion), `alarm/Notifier.kt`,
  `widget/ReminderWidget.kt`, `res/layout/widget_reminders.xml`, `res/drawable/widget_bg.xml`, `tools/strings_gen.py`
  (+ regenerated strings), `AppUiTest.kt`.
- Untouched: `core/*`, `data/*`, `alarm/Engine.kt`, `Receivers.kt` (scheduling and data behaviour do not change).

---

## 6. Council verdicts

Members: **D** senior Android product designer (Material 3, Android 15/16), **R** Arabic RTL typography and layout,
**A** accessibility (WCAG 2.2 AA / EN 301 549 for mobile, TalkBack, 48 dp, 200 % text, nothing by colour alone),
**U** an everyday one-handed user, **X** devil's advocate.

Benchmarks studied for **flow patterns only** (no branding, art, colours or icons copied):
- **Google Tasks**: the check circle at the start of each row; completed items collapsed at the bottom as "Completed (n)".
- **Google Clock**: the time as the largest element; the ringing screen's actions at the bottom; the snooze length named.
- **Microsoft To Do** ("Planned"): one list grouped by Overdue / Today / Tomorrow / Later instead of tabs.
- **Samsung Reminder**: time-first rows; a single prominent add button; repeat shown as a small icon line.
- **Todoist (free)**: Today view with an Overdue section on top; Undo snackbar after completing.

| # | Decision | Verdict | Reasons and dissent |
|---|---|---|---|
| V1 | Home is **one list with sections** (Overdue, Today, Upcoming, Done collapsed), not tabs | **Adopted 4/5** (X dissents) | U: "I open the app to see what's next; tabs made me switch." A: section headings are TalkBack-navigable; overdue is never hidden. D: matches To Do / Todoist. **X:** "Tabs keep each list short and the old test clicks tabs." Answer: Done is collapsed, empty sections are hidden, tests change by two lines (§5). |
| V2 | **The time is the row headline** (22 sp), title under it; the due time is the reminder screen's hero (57 sp) | **Adopted 5/5** | D: hierarchy follows the user's question ("when?"). R: Western digits with `tnum` align in RTL columns. U: "I read the time first, always." |
| V3 | Done circle at the row **start** (right in RTL) | **Adopted 4/5** (U conditional) | D, A: Tasks/To Do pattern, a separate named 48 dp target. **U:** "My thumb rests there; I'll tick by accident." Condition met: haptic + 10 s Undo snackbar, and the circle is drawn small (22 dp) inside the target. |
| V4 | A **single centred** extended FAB «تذكير جديد» on `primary` | **Adopted 4/5** (X dissents) | U: bottom centre is reachable by either thumb; the old bottom-left was worst for right-handers. D: consistent with the house's Scan Kiwi decision. **X:** "Material's default is end." Answer: 88 dp padding and it collapses on scroll. |
| V5 | Paired buttons: **secondary first, primary at the end**, including «تخطَّ» · «تم» on the reminder screen | **Adopted 4/5** (U dissents) | D, R: Android's own Arabic dialogs; house rule. **U:** "On the alarm, Done should be under my right thumb." Answer: «تم» is full width for one-time reminders (most of them); with Skip, «تم» is 64 dp tall and filled, so it is unmistakable. |
| V6 | Complete M3 scheme generated from the brand seed; lavender bell as tertiary | **Adopted 5/5** | D: the purple leaks were the most visible flaw. A: all 51 required pairs pass (§2.1). X: no rebrand risk, the icon colours stay. |
| V7 | No dynamic colour (Material You) in this release | **Adopted 3/5** (D, A prefer opt-in) | X, U, R: red/green/lavender carry meaning here (overdue, ready, priority) and must stay stable; one less setting. D, A: allow opt-in later like Scan Kiwi; recorded as a follow-up. |
| V8 | Western digits only; «، » as the Arabic separator; ` · ` only in English | **Adopted 5/5** | R: owner rule D-071; the old line mixed «،» and `·`. A: TalkBack reads both correctly. |
| V9 | Editor: Save only at the bottom; delete only in ⋮ with Undo; close asks before discarding | **Adopted 5/5** | U: one Save, in reach. A: one fewer duplicate control. X agreed: the 30-day trash already protects data. |
| V10 | Repeat and early notice as rows opening single-choice dialogs; weekday letter toggles | **Adopted 4/5** (R conditional) | D: Google Calendar/Clock pattern, shorter screen. **R:** single letters (س ح ن ث ر خ ج) only with full names in contentDescription and the written summary under them — required by §3.2. |
| V11 | Reminder screen: hero time, docked action zone (2×3 snooze grid + pair), «لاحقًا» and edit in the top row; disabled snooze stays in place | **Adopted 4/5** (X dissents on «لاحقًا») | U: everything to act on is in the bottom half. A: a stable grid helps switch and TalkBack users; the dock keeps «تم» visible at 200 %. **X:** "«لاحقًا» at the top lets people dismiss without noticing." Answer: it only hides the screen; the ongoing notification stays and re-posts (D-005). |
| V12 | Allow-background: grouped by importance, status as text + icon, one action per row, button weight follows importance; intro no longer names colours | **Adopted 5/5** | A: fixes WCAG 1.4.1. U: "I can see what's missing at a glance." |
| V13 | Settings as grouped rows with dialogs; a status row on top | **Adopted 5/5** | D: Android 16 style, about one screen long. U: the permission status is now a control, not plain text. |
| V14 | One icon family: `Icons.Rounded` + `AutoMirrored.Rounded` (material-icons-extended) | **Adopted 4/5** (D: "Material Symbols drawables are the long-term answer") | X: no new asset pipeline for v0.2. D's point recorded as a follow-up. |
| V15 | Stable components only; no Expressive APIs (ButtonGroup, FloatingToolbar, FAB menu, `MotionScheme.expressive`) | **Adopted 5/5** | X: a solo-maintained app must not chase alphas. Note: `TopAppBar`, `DatePicker`, `TimePicker`, `TooltipBox` may still need `@OptIn(ExperimentalMaterial3Api::class)` in this BOM; they are long-shipped baseline components and already used. |
| V16 | No NavigationBar | **Adopted 5/5** | D: no top-level destinations. |
| V17 | Notification «أجّل 10 دقائق» names its duration; widget rows time-first with «متأخر» in words | **Adopted 5/5** | U: "Snooze for how long?" A: the old «●» was colour/shape only. |

### Devil's advocate: objections on record

1. *"Professional" means consistent and calm, not "more Material".* **Accepted:** V2, V5, V6 and V12 carry the change;
   V13 and V14 are polish and can slip without blocking the release.
2. *Sections make a long scroll on a busy day.* **Partly accepted:** Done is collapsed, empty sections disappear, and the
   FAB collapses on scroll. Revisit with real use (if Upcoming grows beyond ~20 items, group it by day).
3. *A red time on the reminder screen is alarming.* **Partly accepted:** red only when actually late, always with the
   words «متأخر …»; an on-time reminder is neutral `onSurface`.
4. *The Done circle at the start invites accidental taps.* **Accepted as a condition** (V3): haptic + Undo.
5. *Dynamic colour is "international standard".* **Deferred** (V7) for meaning stability; opt-in remains possible.
6. *Test churn.* **Accepted:** §5 lists every string the tests depend on and the exact test edits.
7. *«لاحقًا» at the top.* **Overruled** (V11) because the notification persists.

---

## 7. Licences

| Asset | Licence | Where |
|---|---|---|
| Noto Sans Arabic (app: static 400/500/700; mockups: VF) | SIL Open Font License 1.1 | `app/src/main/res/font/`, `design/mockups/fonts/` (`OFL-NotoSansArabic.txt`) |
| Noto Sans 400/700 (mockup Latin only) | SIL OFL 1.1 | `design/mockups/fonts/` |
| Material icons (`material-icons-extended`, Rounded) | Apache License 2.0 | app dependency; named in About and `THIRD_PARTY.md` |
| material-color-utilities (palette generation, tooling only, not shipped, not stored in the repo) | Apache License 2.0 | `design/tools/palette.mjs` loads it from `$env:MCU_DIR` |
| Playwright (rendering, tooling only) | Apache License 2.0 | `E:\fast kiwi pro neq\tools\pw` |
| Brand mark | FastKiwi original (the Windows icon) | `Remember-Me/assets/icon.svg` |

Mockup icons are original hand-drawn stand-ins. Nothing is copied from Google, Microsoft, Samsung or Doist: no logos,
colours, illustrations or icon art; only common interaction patterns were benchmarked. Note for the implementer:
`THIRD_PARTY.md` and `licences_text` should name the Material icons (Apache-2.0) if they do not already.
