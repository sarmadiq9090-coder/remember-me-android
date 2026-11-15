# Black-box phone for Remember Me (Android) — «هاتف تجريبي» لتطبيق ذكرني

This folder lets you use the Remember Me app **like a person holding a phone**, without opening the
app's code. You write what you would do, line by line, in a text file. The driver plays it on a
simulated Android 16 phone (412 × 915 dp, Arabic, Baghdad time) with the app freshly installed, and
after every line it saves a picture of the screen and a text description of what is on it.

> يسمح هذا المجلد باستخدام تطبيق «ذكرني» كما يستخدمه شخص يمسك الهاتف، دون فتح شيفرة التطبيق. تكتب ما
> ستفعله سطرًا سطرًا في ملف نصي، فيشغّله «المشغِّل» على هاتف أندرويد 16 مُحاكى (عربي، بتوقيت بغداد) والتطبيق
> مثبّت للتو، ويحفظ بعد كل سطر صورة للشاشة ووصفًا نصيًا لما عليها.

---

## 1. How to run a session — طريقة التشغيل

1. Make a folder anywhere, for example `my-test/`. Put a file `script.txt` in it (UTF-8).
2. In Git Bash run:

   ```sh
   "/e/fast kiwi pro neq/rm-wt-blackbox/torture/blackbox/run.sh" my-test
   ```

   To test another copy of the app, set `BB_REPO` to that copy's folder first:
   `BB_REPO="/e/fast kiwi pro neq/<other copy>" ".../run.sh" my-test`.
3. Wait (about 1–2 minutes plus your `wait` lines). The script then lists the files it made and
   prints `report.txt`. Everything is in `my-test/out/`. Old results in `out/` are deleted first.

> ١) أنشئ مجلدًا وضع فيه ملف `script.txt`. ٢) شغّل `run.sh` مع اسم المجلد. ٣) انتظر، ثم افتح المجلد
> `out/` داخل مجلدك: فيه الصور والنصوص و`report.txt`. تُحذف النتائج القديمة قبل كل تشغيل.

Every run starts from a **new phone with the app just installed**: no reminders, nothing allowed yet,
first launch as a real new user. Nothing is kept between runs.

> كل تشغيل يبدأ بهاتف جديد والتطبيق مثبّت للتو: لا تذكيرات، ولا أذونات بعد، وأول فتح كمستخدم جديد.

---

## 2. Writing `script.txt` — كتابة الملف

- One action per line. Empty lines and lines starting with `#` are ignored.
- Text with spaces goes in quotes: `"…"` or `«…»`. Arabic is fine: `tap "حفظ"`.
- **Phone settings at the top** (lines like `dark on`, `font-scale 2.0`, `language en`, `start onboarded`)
  are applied *before* the app is opened. Then the app is opened from its icon by itself (picture `00`),
  and the remaining lines run in order. Use `start at-home` if you do not want the app opened for you.
- If a line cannot be done (for example the text is not on screen), it is written in `report.txt`
  with the reason, a picture is still taken, and the next line runs. Put `on-error stop` at the top to
  stop at the first problem instead. If the app **crashes**, the run always stops there.

> - سطر واحد لكل فعل. الأسطر الفارغة والتي تبدأ بـ `#` تُتجاهل.
> - النص الذي فيه مسافات يوضع بين علامتي تنصيص `"…"` أو `«…»`.
> - إعدادات الهاتف في أعلى الملف تُطبَّق قبل فتح التطبيق، ثم يُفتح التطبيق وحده (الصورة `00`).
> - إذا تعذّر سطر يُكتب السبب في `report.txt` ويستمر التشغيل (إلا مع `on-error stop`). إذا انهار التطبيق يتوقف التشغيل.

Example — مثال:

```text
# A new user allows notifications and tries the alarm test with the phone locked.
tap "السماح"
tap "اختبر التنبيه الآن"
tap "ابدأ الاختبار"
lock
wait 15
tap "تم"
unlock
```

---

## 3. Every action — كل الأفعال

### Touching the screen — لمس الشاشة

| Line | What the person does |
|---|---|
| `tap "<text>"` | Taps the thing on screen that shows this text. Exact text first, then a description (icon name), then text that *contains* it. Topmost window first (a dialog before the screen under it). |
| `tap-desc "<description>"` | Taps an icon or element by the name TalkBack would read (its description), e.g. `tap-desc "الإعدادات"`. |
| `long-press "<text>"` | Presses and holds on that text. |
| `type "<text>"` | Types into the text field that has the cursor. Tap the field first. Adds to what is there. |
| `clear` | Empties the text field that has the cursor. |
| `scroll down` / `scroll up` | Swipes the main list or page once (about half a screen). |
| `scroll-to "<text>"` | Scrolls until that text is visible (up to 25 swipes). |
| `swipe-left "<text>"` / `swipe-right "<text>"` | Swipes the row that contains this text with the finger moving left / right. (Directions are physical: left is left also in Arabic.) |
| `tap-outside` | Taps outside the open dialog, sheet or menu (usually closes it). |
| `tap-at <x> <y>` | Taps a point, in dp from the top-left corner (the `.txt` files give every element's position in dp). |
| `drag <x1> <y1> <x2> <y2>` | Drags a finger from one point to another, in dp (for example on a clock dial). |
| `back` | The Back gesture/button. Closes a dialog, menu, Android screen or the notification shade first. |
| `home` | Goes to the home screen; the app goes to the background. |
| `open-app` | Taps the app's icon on the home screen (brings it back if it is in the background). |
| `screenshot` | Does nothing; just takes another picture (useful after `wait`). |

You cannot tap what is **under** an open dialog/menu — the driver refuses and tells you, because a
real tap there would only close the dialog. You cannot tap what is not visible — scroll first.

> لا يمكن لمس ما تحت نافذة حوار أو قائمة مفتوحة، ولا ما ليس ظاهرًا على الشاشة؛ مرّر أولًا.

### Time — الوقت

| Line | Meaning |
|---|---|
| `wait <seconds>` | Real time passes (at most **120 s per line**; use several lines for longer). During the wait, every alarm the app set that comes due is delivered, and the app reacts (notifications, ringing, the full-screen alert). |

**Important — مهم:** the phone's clock is the real clock. **You cannot jump to tomorrow or to next
hour.** To see a reminder arrive, set it a minute or two ahead and `wait`. The current phone time is in
`screen.txt` and at the top of every step's `.txt`. Each ordinary action takes about 1–2 seconds of phone time.

> ساعة الهاتف هي الساعة الحقيقية: **لا يمكن القفز إلى الغد أو إلى الساعة القادمة**. لرؤية تذكير يصل،
> اضبطه بعد دقيقة أو دقيقتين ثم استخدم `wait` (حتى 120 ثانية للسطر الواحد).

### Lock screen and notifications — شاشة القفل والإشعارات

| Line | Meaning |
|---|---|
| `lock` | Presses the power button: screen off and locked. A reminder that asks for a full-screen alert then opens the alert screen by itself, over the lock screen, as on a real phone. |
| `unlock` | Unlocks the phone (the phone has a PIN). Things that waited for the unlock (a notification tapped on the lock screen) open now. |
| `notifications` | Pulls down the notification shade (the picture shows it). Any other action closes it. |
| `notification-tap "<title>"` | Taps the notification with this title (in the shade or on the lock screen). |
| `notification-action "<title>" "<button>"` | Taps a button of that notification, e.g. `notification-action "دواء" "تم"`. |
| `notification-swipe "<title>"` | Swipes the notification away. A pinned (ongoing) one cannot be swiped on the lock screen. |

While the phone is **unlocked and in use**, Android shows a full-screen request only as a **banner at the
top** (heads-up), drawn in the picture; tap it with `notification-tap`. When **locked**, the alert
screen opens by itself.

> عندما يكون الهاتف مفتوحًا وقيد الاستخدام، يظهر طلب الشاشة الكاملة كشريط في أعلى الشاشة فقط (كما في
> أندرويد الحقيقي). وعندما يكون مقفلًا تُفتح شاشة التنبيه وحدها فوق شاشة القفل.

### Phone settings (personas) — إعدادات الهاتف (الشخصيات)

Put these at the top for a persona, or anywhere to change them in the middle:

| Line | Meaning |
|---|---|
| `start onboarded` | Top only: the app was already set up before (first-launch screens skipped, notifications allowed). |
| `start at-home` | Top only: do not open the app automatically; start on the home screen. |
| `font-scale 2.0` | Android text size (1.0 normal, 1.3 large, 2.0 largest; 0.5–3.0). |
| `dark on` / `dark off` | Android dark theme. |
| `language ar` / `language en` | The **phone's** language (the app has its own language setting; it starts in Arabic whatever the phone says). |
| `ringer silent` / `ringer vibrate` / `ringer normal` | The ringer switch. |
| `dnd on` / `dnd alarms` / `dnd total` / `dnd off` | Do Not Disturb: `on` = priority only with alarms allowed (Android's usual), `alarms` = alarms only, `total` = total silence. |
| `deny-notifications` / `allow-notifications` | Turns the app's notifications off/on in Android's settings. |
| `channel "<category name>" on\|silent\|off` | Changes one notification category of the app in Android's settings (names are listed in `notifications.txt`). |
| `exact-alarms on` / `off` | Android's «Alarms & reminders» permission for the app. |
| `full-screen-permission on` / `off` | Android's «full-screen notifications» permission (on by default). |
| `lockscreen-notifications all\|hide-sensitive\|none` | Android's lock-screen setting (default `all` = show all content). |
| `timezone <zone>` | Changes the phone's time zone, e.g. `timezone Europe/London` (default Asia/Baghdad). |
| `speech none\|google\|google-nolist\|google-dead` | The phone's speech service. `none` (default): no Google app, no speech service. `google`: Google's speech dialog; it has Arabic (Saudi Arabia, Egypt, UAE, Jordan) and English, but not Arabic (Iraq): for a language it does not have, its dialog says «ميزة "البحث الصوتي" غير متاحة» and never listens. When it is listening, `say "<text>"` is what you say. `google-nolist`: the same, but the service does not tell apps which languages it has. `google-dead`: the dialog always says «غير متاحة». Takes effect the next time a screen opens. |
| `say "<text>"` | While Google's speech dialog is open: you say this text. `back` closes the dialog without text. |

Personas — أمثلة على الشخصيات:

```text
# Older user, big text, dark room
font-scale 2.0
dark on

# English phone, silent ringer, Do Not Disturb at night
language en
ringer silent
dnd on
```

### System events and files — أحداث النظام والملفات

| Line | Meaning |
|---|---|
| `reboot` | Restarts the phone: the app's windows, alarms, notifications and sound are gone. The phone comes back **locked, before the first unlock**; `unlock` then finishes the start (Android tells the app the phone started). |
| `pick-file <name>` | When the app opened Android's file picker: chooses this file. Files come from a `files/` folder inside your session folder, or from `torture/blackbox/fixtures/` (`tone.wav` a short sound, `not-a-backup.txt`, `backup-sample.zip` a real backup with 3 reminders, `backup-damaged.zip` the same with one byte changed). |
| `save-file [<name>]` | When the app opened Android's «save file» screen: saves (with the name the app suggested, or yours). The file appears in `out/files/` so you can look at it. Tapping «حفظ»/«Save» there does the same. |
| `share-text "<text>"` | Another app shares this text to Remember Me (Android's share sheet). If the app does not accept shared text, the report says so. |

Android's own screens (permission questions, file picker, settings pages, share sheet) are **drawn by the
driver**, marked at the bottom «Android screen (drawn by the driver)». Their buttons are tapped by text
like any other (`tap "السماح"`, `tap "عدم السماح"`). Settings pages of Android are only a title: use
the setting lines above while it is open, then `back`.

> شاشات أندرويد نفسها (أسئلة الأذونات، منتقي الملفات، صفحات الإعدادات، المشاركة) يرسمها المشغِّل وتُعلَّم
> بذلك في أسفلها. تُلمس أزرارها بنصها كغيرها.

---

## 4. What you get — ما تحصل عليه

In `out/`:

| File | What it is |
|---|---|
| `NN-<action>.png` | What the person sees after line NN: the app's screen **with any dialog, bottom sheet or menu on top of it**, dimmed behind as on a phone. A heads-up banner, a short toast message, the lock screen, home screen, notification shade, and Android's own dialogs are drawn too. `00-open-app.png` is the first launch. |
| `NN-<action>.txt` | The same moment in words: what happened by itself (a banner appeared, the alert opened, ringing started, a toast…), the **screen** summary, **what TalkBack finds** (every element in reading order, windows on top first, with its role, state — checked, selected, disabled, has the cursor — its actions, and position/size in dp), and the **notifications**. |
| `screen.txt` | Latest state: phone time, what is in front, locked or not, dark mode, text size, languages, ringer, Do Not Disturb, notifications allowed, status-bar icons (alarm clock, notifications), **sound now**. |
| `notifications.txt` | Latest notifications: title, text, expanded text, buttons in order, category (channel) name and importance, pinned or not, silent or alerting, full-screen request, and what the lock screen would show. |
| `report.txt` | Every line with `done` or `COULD NOT BE DONE: <reason>`, and `THE APP CRASHED: …` if it crashed. |
| `crash-details.txt` | Only after a crash: the technical error (for the developers). |
| `files/` | Files the app saved (`save-file`) and text it shared. |
| `driver-debug.txt` | Driver internals; ignore it. |

How dialogs appear — كيف تظهر النوافذ: a dialog, bottom sheet or popup menu is a separate window on a
real phone. The picture paints the screen first, then a dark veil if the dialog dims the background,
then each window on top at its place. In the `.txt`, the window on top is listed first; windows under a
dialog are marked «TalkBack cannot reach it».

> النافذة المنبثقة أو اللوحة السفلية أو القائمة تُرسم فوق الشاشة في مكانها، مع تعتيم ما خلفها كما في
> الهاتف. وفي ملف النص تُذكر النافذة العليا أولًا، وما تحتها يُعلَّم بأن TalkBack لا يصل إليه.

TalkBack lines look like:

```text
- «حفظ» — (Button) — actions: tap — at 16,847 size 380×56 dp
- (NO LABEL — TalkBack would only say the kind of control) — (Button) — actions: tap — at …
```

`«…»` is what TalkBack says first. `OFF SCREEN` means it exists but you must scroll to it.

---

## 5. Examples — أمثلة

Ready sessions in `torture/blackbox/examples/` (copy one and change it):

| Folder | What it shows |
|---|---|
| `01-first-launch-test-alarm` | Real first launch, Android's notification question, the alarm test with the phone locked, the full-screen alert, «تم». |
| `02-quick-add-one-minute` | A reminder written for one minute from now, the phone locked, `wait 65`, the alert, «تم». |
| `03-persona-banner-notification` | Large text + dark mode; the alert arrives while the phone is in use (banner), answered from the notification shade. |
| `04-backup-restore-reboot` | Saving a backup file, restoring a wrong file and a real one, restarting the phone. |
| `05-settings-change-while-open` | Dark mode and text size changed while the app is open; the app's own language switch to English. |
| `06-editor-sheets-menu` | The full editor, a choice sheet, the ⋮ menu, delete and undo. |

> أمثلة جاهزة في `examples/`: انسخ واحدًا وعدّله.

---

## 6. Honest limits — حدود صريحة

- **No real sound or vibration.** The driver reports what the app *asked* Android to do:
  «SOUND WOULD PLAY: yes — the phone's default alarm sound, repeating, stops by itself after at most 120 s
  (alarm sound: plays at alarm volume, also when the ringer is silent…)», «VIBRATION: yes», «READ ALOUD:
  …». It also says when Android would mute it (ringer, Do Not Disturb). Whether a person would really hear
  it in a noisy room cannot be checked here.
  > لا صوت ولا اهتزاز حقيقيان: يذكر المشغِّل ما طلبه التطبيق من أندرويد (نوع النغمة، تكرارها، مدتها،
  > الاهتزاز، القراءة بصوت)، ومتى كان أندرويد سيكتمه.
- **Real time only**: no jumping to tomorrow; at most 120 s per `wait` line.
- The **status bar and the on-screen keyboard are not drawn** (their information is in `screen.txt`).
  In a real phone the keyboard would cover the lower part of the screen while typing.
- Android's own screens are simplified drawings. Settings pages cannot be operated by tapping; use the
  setting lines.
- Turning off notifications does not kill the app as Android sometimes does.
- Widgets, other apps, real files on a phone, and the phone's own TalkBack voice are not available.
- Screen positions are in dp (1 dp = 2 pixels in the pictures).
- One run takes 1–2 minutes before the first line, plus your waits. If one line takes more than
  4 minutes (plus its wait), the driver stops and `report.txt` says «THE DRIVER GOT STUCK»: that is a
  problem of the driver, not a finding about the app. A whole run is stopped after 30 minutes
  (`BB_TIMEOUT=<seconds>` changes this).
- Android's per-app language and the first launch use the app's real code; what you see is what the app
  does, also when it looks wrong.

> - ساعة حقيقية فقط، دون قفز للغد، و120 ثانية كحد أقصى لكل سطر انتظار.
> - شريط الحالة ولوحة المفاتيح لا يُرسمان (معلوماتهما في `screen.txt`).
> - شاشات أندرويد رسوم مبسطة، وصفحات الإعدادات تُدار بأسطر الإعدادات لا باللمس.
