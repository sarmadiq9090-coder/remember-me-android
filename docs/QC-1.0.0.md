# فحص الجودة المستقل — «ذكرني» لأندرويد 1.0.0

> **ما هذا الملف؟** نتيجة فحص الجودة (البوابة 5 في [FEATURES.md](FEATURES.md)) لنسخة الإصدار المرشّحة، كتبها مدقّق
> مستقل لم يكتب الكود. التاريخ 2026-11-15. الكود المفحوص: الفرع `qc` عند `41f11aa`. لم يُغيَّر أي سطر من كود
> التطبيق؛ أُضيفت اختبارات فحص فقط في `app/src/test/java/com/fastkiwi/rememberme/qc/`.
>
> **الدرجات:** Q1 يمنع الإصدار (معيار قبول مكسور في ميزة اعتمادية أو خصوصية أو بيانات، أو مخالفة معيار).
> Q2 انحراف ظاهر أو ميزة منفَّذة جزئياً. Q3 تحسين.

## الخلاصة

- **الميزات الموافَق عليها: 80.** مستوفاة: **67**. مستوفاة جزئياً: **8**. غير مستوفاة: **0**. تحتاج هاتفاً حقيقياً
  للحكم: **5** (الصوت والاهتزاز والوقت الحقيقي وشاشة القفل بعد إعادة التشغيل).
- **Q1 في الكود: لا شيء.** لم أجد معيار قبول مكسوراً في ميزة اعتمادية أو خصوصية أو بيانات، ولا مخالفة لمعيار في
  الأذونات أو الخدمة الأمامية أو المحاذاة أو الإنترنت.
- **لكن الإصدار لم يكتمل بعد** لأسباب في البوابات لا في الكود: نسخة الفحص غير موقّعة (لا مفتاح في هذه الشجرة)، والبوابة 6
  (قائمة فحص هاتفك و14 يوماً من الاستعمال) لم تبدأ، والبوابة 1 ناقصة (ملاحظات lint، ومستويات API الست).
- **Q2: 18 فجوة**، أهمها: نص التذكير لا يظهر على شاشة التذكير بخط 200 % دون تمرير؛ قصّ وتداخل في عرض اليوم
  والأسبوع ومساعدة «عدم الإزعاج» بخط 200 %؛ العناوين الإنجليزية داخل الجمل العربية غير معزولة؛ النسخة قبل التحديث
  لا تُحفظ بين نسخ rc ذات رقم البناء نفسه؛ وثائق الإصدار (CHANGELOG، اقرأني.txt، STANDARDS.md) ما زالت لـ0.1.x.
- **الاختبارات:** 591 اختباراً، 0 فشل، 114 متخطّى (مجموعة التعذيب ومرحلة Windows لا تعمل إلا بمفتاح). lint: 0 أخطاء،
  7 تحذيرات، 3 تلميحات. التعذيب الخفيف: 110 اختبارات، فشل واحد (G-18). التبادل مع Windows في الاتجاهين: نجح.
- **ملف APK:** 2,817,755 بايت (2.69 MB)، versionCode 3، versionName 1.0.0، targetSdk 36، الأذونات = 0.1.1 +
  `FOREGROUND_SERVICE` فقط، **لا إذن إنترنت**، محاذاة 16 KB سليمة. الشهادة لم تُفحص على هذه النسخة لأنها غير موقّعة؛
  نسخة سابقة موقّعة من الشجرة الرئيسية تحمل الشهادة الصحيحة `8d3b91dc…6ae6e26`.

## جدول الميزات

«م» = مستوفاة، «ج» = جزئياً، «لا» = غير مستوفاة، «هاتف» = لا يُحكم عليها إلا على هاتف حقيقي (المنطق ناجح على الحاسوب).
الأدلة أسماء اختبارات ناجحة في هذه النسخة أو مواضع في الكود.

### 1. الاعتمادية والتنبيه

| رقم | الحكم | الدليل | الفجوة |
|---|---|---|---|
| F-001 | م | `EngineTest.armsAnAlarmClockForTheNextReminder`، `fallsBackToAnInexactAlarmWithoutThePermission` | — |
| F-002 | م | `EngineTest.rebootAndTimeChangesReArm`، `EngineReliabilityTest.aRebootClearsAlarmsAndBootSetsTheSameOneAgain`، `SchedulePropertyTest` | — |
| F-003 | م | `EngineTest.snoozeFromTheNotificationUsesTheFirstPresetAndStaleTapsAreIgnored`، `TasksTest.staleTapsChangeNothing` | — |
| F-004 | م | `AlertUiTest.atDoubleTextDoneIsFullyVisibleAndAboveSnooze` | G-04 (العنوان خارج الشاشة بخط 200 %) |
| F-005 | م | `PlannerTest.remindAgainSoundsEveryIntervalAtMostSixTimes` | — |
| F-006 | م | `EngineReliabilityTest.aBrokenNoticeSlotNeverDelaysADueTime` | — |
| F-007 | م | `SchedulePropertyTest.aThousandRandomSequencesNeverLoseOrDoubleAReminder`، `rebuildingTenTimesGivesOneAlarmOneNotificationAndOneSound` | — |
| F-008 | م | `HealthModelTest.allEightCombinationsOfTheEssentials`؛ التحديث عند العودة `HealthScreens.kt:205` | — |
| F-009 | م | `HealthUiTest.alertTestResultsShowTheSixPartsAndAskHonestly`؛ مطابقة البايتات: `QcGapsTest.f009_theAlertTestLeavesDataJsonByteIdentical` (جديد) | — |
| F-010 | م | `ScheduleLog.MAX = 200` (`ScheduleLog.kt:66`)، `EngineReliabilityTest.theScheduleLogHoldsNoReminderContent` | — |
| F-011 | م | `MainActivity.kt:295-299` يفحص ويعيد الضبط عند كل عودة | Q3: لا اختبار لدورة العودة كاملة |
| F-012 | م | `SchedulePropertyTest.thirtySixTimeZoneClockAndRebootScenarios`، `RepeatTest`، `CalendarTest` | — |
| F-013 | م | `HealthModelTest.makerStepsPerMakerAndVersionAndNoneTried` (كل الخطوات «لم تُجرَّب») | — |
| F-014 | م | `TonesTest` (النسخ داخل التطبيق، 5 MB، 10 نغمات)؛ لا إذن تخزين (`QcGapsTest.f_gate1_…`) | البند 7 في قائمة هاتفك |
| F-015 | م | `AlertPipelineTest.theAppNeverAsksForDndAccess`، `AlertPolicyTest.doNotDisturbIsNeverBypassed` | البندان 5 و6 |
| F-016 | هاتف | `AlertPipelineTest.aDueReminderIsPostedThenTheServiceRingsTheSameNotification`؛ الحد 120 ث `RingService.kt:187`، `onTimeout` `:170`، مفتاح الصوت `AlertActivity.kt:128` | توقف خلال ثانية وعدم بقاء شيء يعمل لا يُقاسان إلا على هاتف |
| F-017 | ج | سلسلة البدائل `Tones.kt:55-68`، `TonesTest.aMissingCopyFallsBackAndSaysSo`، `HuntAlertTest.aVibrateOnlyReminderIsFullySilentWhenTheRingingServiceIsRefused` | G-09: لا مهلة 3 ثوانٍ للتحضير |
| F-018 | هاتف | `AlertPipelineTest.anImportantReminderUsesTheAlarmChannelCategoryAndRingDuration` | ±2 ث على صوت حقيقي |
| F-019 | هاتف | `AlertPolicyTest.aNormalReminderFollowsTheRinger`، `vibrationCanBeTurnedOffPerReminderOrByDefault` | الاهتزاز الفعلي في أوضاع الرنين |
| F-020 | م | `AlertPipelineTest.fullScreenOffStillShowsTheNotificationWithItsButtons`؛ زر الإعداد `Health.kt:161` | — |
| F-021 | م | `AlertPipelineTest.aSwipedPinnedReminderComesBackAtMostFiveTimesAndAnUnpinnedOneStaysAway` | منع السحب والهاتف مقفل: البند 10 |
| F-022 | م | `AlertPipelineTest.hiddenDetailsStayHiddenOnALockedPhoneThatShowsAllContentAndComeBackAfterTheUnlock`، `Health.kt:129` | البند 11 |
| F-023 | م | `HealthUiTest.dndHelpSaysUnknownWhenItCannotRead`، `HealthModelTest.unknownIsNeverReady`، `dndBlocksTheTestByStyle` | Q3: `canBypassDnd` لا يُقرأ |
| F-024 | م | `AlertLog.MAX = 500` (`AlertLog.kt:57`)، `HealthUiTest.theAlertLogListsEventsWithoutContentAndClears` | — |
| F-025 | م | `AlertPipelineTest.aLateArrivalIsInTheAlertLog`، `AlertUiTest.hintsLateArrivalRingingAndSteps` | — |
| F-026 | هاتف | `AlertPipelineTest.beforeTheUnlockOnlyTimesAreKeptAndAnUnlockDoesNotRingTwice` | إعادة تشغيل حقيقية: البند 14 |
| F-027 | هاتف | `HealthModelTest.theNightTestArrivesTomorrowAtSeven`، `HealthUiTest.aRunningTestCountsDown` | الليل الحقيقي: البند 13 |
| F-028 | م | `HealthUiTest.theReportHasNoPersonalDataAndIsShownExactly`، `HealthScreens.kt:94-125` | Q3: التقرير بالإنجليزية داخل صفحة عربية |
| F-029 | م | `docs/COMPATIBILITY.md`: لا صفّ بلا تجربة | Q3: الملف يقول 0.2.0 |
| F-030 | م | `AlertPipelineTest.theMorningSummaryCountsTodayAndShowsNoTitleWhenDetailsAreHidden`، `Summary.kt:53` | — |
| F-031 | م | `HuntAlertTest.readAloudSpeaksAnImportantReminderWhileThePhoneIsSilent`، `…WhileDoNotDisturbIsOn` | النطق الفعلي على هاتف |
| F-032 | م | `AlertPipelineTest.theChannelsAreTheFixedSetAndTheDueOnesHaveNoSoundOfTheirOwn` | — |

### 2. الإضافة والتأجيل

| رقم | الحكم | الدليل | الفجوة |
|---|---|---|---|
| F-040 | م | `AppUiTest.homeAndEditorTouchTargetsAndRowSemantics`، `EditorUiTest.oneSaveAndTheAlertOptionsFoldedByDefault` | — |
| F-041 | م | `AppUiTest.exportWarnsAboutHourlyRemindersFirst` | — |
| F-042 | م | `QuickAddParityTest` (2,196 حالة)، `QuickAddTest.noDateCorpusInventsNothing` (275 عنواناً × 3 مناطق × 3 أوقات)، `QuickUiTest.anAmbiguousTimeDisablesSaveUntilAChoiceAndNothingUnseenIsSaved` | — |
| F-043 | م | `SettingsUiTest.snoozeEditorWritesSixValidPresets`؛ 10 ضغطات = تأجيل واحد: `QcGapsTest.f043_…` (جديد) | — |
| F-044 | م | `Alert.kt:385` يرفض الوقت الماضي برسالة | Q3: لا اختبار للرفض |
| F-045 | م | `Alert.kt:241`، `AlertUiTest.hintsLateArrivalRingingAndSteps` | — |
| F-046 | م | `EditorUiTest.addAlertIsGoneAfterThreeEarlyAlertsAndTheNearestGoesToRemindBefore`، `AlertPipelineTest.severalEarlyNoticesEachShowOnce` | — |
| F-047 | م | `TemplatesTest.builtInsMatchTheSpecGroups`، `QuickUiTest.templatesFillTheEditorAndSaveNothing` | Q3: «ذكرى سنوية» تنبيه قبل يوم لا أسبوع (DESIGN-SPEC §5.4) |
| F-048 | م | `QuickUiTest.voiceShowsTheDisclosureOnceAndAsksNoPermission` | — |
| F-049 | م | `QuickUiTest.shareOpensThePreviewWithTextCutTo2000AndSavesNothing` | — |
| F-050 | م | `PrayerTimesTest.matchesIndependentReferenceWithinOneMinute` (≥ 2,000 حالة)، `EditorUiTest.prayerSheetAsksForTheCityFirstAndAssumesNothing` | مقارنة مسجدك: البند 22 |
| F-051 | م | `HijriTest` (كل أيام جدول أم القرى مقابل JDK، و7 تواريخ منشورة)، `day30BecomesDay29` | — |
| F-052 | م | `HomeUiTest.swipeStartToEndCompletesWithAnExactUndo`، `AlertPipelineTest.doneFromTheNotificationOffersUndoThatRestoresTheOccurrence` | — |

### 3. التنظيم والعروض

| رقم | الحكم | الدليل | الفجوة |
|---|---|---|---|
| F-060 | م | `HomeUiTest.homeHasTheBrandOneTodayAndNoNavigationLabels` | — |
| F-061 | م | `SearchTest.statesAreComputedAndOverdueIsNeverDone` | — |
| F-062 | م | `Organize.kt:16`، `EditorCoreTest.tagsStepsAndChoicesAreSavedTheWindowsWay`، التبادل مع Windows (`tools/interchange.sh`: INTERCHANGE OK) | — |
| F-063 | م | `HomeUiTest.aOneListUserSeesNoChips`، `twoListsShowChipsAndAListFilters` | — |
| F-064 | ج | `CalendarUiTest` (8 اختبارات)؛ صور ar/en فاتح وداكن | G-03، G-05، G-16 |
| F-065 | م | `SearchTest.fiveThousandTasksUnderAHundredMilliseconds`، `foldsHamzaTaaMarbutaAlefMaqsuraAndDiacritics` | — |
| F-066 | م | `BulkTest`، `HomeUiTest.deletingTwelveAsksFirst`، `deletingFewerThanTenAppliesAtOnceWithUndo` | — |

### 4. المهام

| رقم | الحكم | الدليل | الفجوة |
|---|---|---|---|
| F-070 | م | `Organize.kt:18` (50 خطوة)، `TasksTest.checklistFromWindowsIsKeptAndUntickedForTheNextOccurrence` | — |
| F-071 | م | `EditorCoreTest.tickingTheLastStepCompletesOnlyWithTheSwitchOn` | — |
| F-072 | م | `HistoryTest.nothingIsWrittenWhileOff`، `boundedPerReminderAndInAll`، `clearRemovesEverything` | — |

### 5. الخصوصية والبيانات

| رقم | الحكم | الدليل | الفجوة |
|---|---|---|---|
| F-080 | م | `EngineTest.noInternetPermissionAndReceiversAreLockedDown`؛ `aapt2 dump permissions` | — |
| F-081 | م | `DataTest.exportZipHasTheWindowsFolderLayoutAndAChecksum`؛ `node tools/windows-validate.cjs`: «OK … verified=true, 3 tasks» | — |
| F-082 | ج | `UpgradeTest.from011`، `from010`، `EngineReliabilityTest.aNewVersionKeepsACopyOfTheDataBeforeTouchingIt` | G-01 |
| F-083 | م | `SettingsUiTest.restoreSummaryCancelLeavesDataJsonByteIdentical`، `SettingsLogicTest.restoreSummaryCountsAndWarns` | — |
| F-084 | م | `IcsTest.rrulesExpandToTheSameOccurrencesAsTheApp` (ical4j) | — |
| F-085 | م | `IcsImportTest` (25 اختباراً)، `fuzzNeverThrowsAnythingElseAndStaysFast` | Q3: الحد 20 MB لا 5 MB (`IcsImport.kt:86`) |
| F-086 | م | `BackupCryptoTest.aWrongPasswordIsRefused`، `roundTripWithTheDefaultParameters` | — |
| F-087 | م | `SettingsLogicTest.weeklyCopyKeepsTheNewestFiveAndNeverTouchesOtherFiles`، `backupPasswordIsStoredOnlyWrapped` | المجلد الحقيقي على هاتف |
| F-088 | م | `DeleteAllTest.deletesEveryFileAndPreferenceAndCancelsAlarms`، `SettingsFlows.kt:548-586` | — |
| F-089 | م | `DocsTest.everyManifestPermissionIsExplainedInTheAppAndTheDoc` | — |
| F-090 | م | `DocsTest.dataFormatDocListsExactlyTheAndroidMembers` | — |
| F-091 | م | `tools/interchange.sh` شُغّل في هذا الفحص: المرحلتان نجحتا («INTERCHANGE OK») | Q3: المرحلة 2 تُتخطى في التشغيل العادي |
| F-092 | م | `SettingsLogicTest.exportEffectsCountEachChange` | — |

### 6. الإتاحة والواجهة

| رقم | الحكم | الدليل | الفجوة |
|---|---|---|---|
| F-100 | م | `EngineTest.arabicTextUsesWesternDigits`؛ 0 رقم هندي في `strings.xml` | — |
| F-101 | م | `HomeUiTest.aRowSpeaksItsStateAndOffersItsActions`، فحوص 48 dp | TalkBack حقيقي على هاتف |
| F-102 | ج | `L10n.isolate` (`L10n.kt:89`) مستعملة في 5 مواضع فقط | G-02؛ `QcBidiTest` يفشل |
| F-103 | ج | 21 شاشة لكل لغة؛ 200 % بالعربية الفاتحة فقط | G-03، G-04، G-05 |
| F-104 | ج | الاهتزاز عند السحب وزر «تم» في الصف فقط (`SwipeableReminderRow.kt:84`، `Home.kt:737`) | G-06 |
| F-105 | ج | تقليل الحركة محترم (`MainActivity.kt:274`)؛ لا وميض | G-07: لا انتقالات بين الشاشات |
| F-106 | م | `WhatsNewUiTest.whatsNewOffersMakeAllNormalFirstAndOkLast`، `SettingsLogicTest.whatsNewOnlyAfterAnUpdateFromBefore100AndOnce` | — |
| F-107 | ج | `docs/GUIDE-ar.md`، `GUIDE-en.md` | G-08 |

### 7. التكامل

| رقم | الحكم | الدليل | الفجوة |
|---|---|---|---|
| F-120 | م | `QuickUiTest.widgetEmptyStateAndPlusOpensQuickAdd` | — |
| F-121 | م | `QuickUiTest.widgetDoneCircleCompletesWithUndoAndIgnoresStaleTaps`؛ 48 dp `widget_row.xml:11-12` | — |
| F-122 | م | `QuickUiTest.shortcutsOpenTheRightScreens`؛ `xml/shortcuts.xml` | — |
| F-123 | م | `QuickUiTest.tileIsLabelledAndOpensQuickAdd`؛ `NewReminderTile.kt:42` | — |

## الفجوات

### Q1 (يمنع الإصدار)

لا يوجد في الكود. **مع ذلك لا يُصدر 1.0.0 قبل:** توقيع النسخة النهائية وفحص شهادتها (G-12)، وإغلاق البوابة 1 (G-10، G-11)،
والبوابة 6 (قائمة فحص هاتفك و14 يوماً). هذه بوابات مفتوحة لا أخطاء.

### Q2

| رمز | الميزة | المشكلة | الموضع | الإصلاح المقترح |
|---|---|---|---|---|
| G-01 | F-082 | النسخة قبل التحديث مربوطة برقم البناء فقط؛ تثبيت rc.2 فوق rc.1 أو 1.0.0 فوق rc.N (كلها رقم 3) لا يحفظ نسخة، والمواصفة تقول «versionCode **و** versionName» | `Repo.kt:29-32` | احفظ `lastVersionName` وخذ النسخة إن تغيّر أحدهما. الاختبار `QcGapsTest.f082_…` يفشل الآن |
| G-02 | F-102 | العنوان داخل الجمل غير معزول: «أُنجز «…»» في الشريط، وصف «تم: …» وحالة «أُنجز: …» في الأداة، تأكيد الحذف النهائي، «العنوان: …» في الإضافة السريعة، القوالب، وصف الصف | `Home.kt:409, 502, 684-685`، `ReminderWidget.kt:178, 192`، `Settings.kt:565`، `QuickAddSheet.kt:345`، `TemplatesScreen.kt:105, 119` | مرّر كل عنوان عبر `L10n.isolate`. الاختبار `QcBidiTest` يفشل الآن |
| G-03 | F-103، F-064 | بخط 200 %: عرض اليوم يقصّ «ص/م» من أرقام الساعات وتتداخل «الآن 6:50 ص» مع عنوان التذكير؛ عرض الأسبوع يقطع العناوين إلى 5 أحرف؛ أرقام الخطوات في مساعدة «عدم الإزعاج» مقصوصة | `Day.kt:121, 125, 186-194`؛ `Week.kt:115`؛ `HealthComponents.kt:162`؛ الصور `ar-200-16-day.png`، `ar-200-18-week.png`، `ar-200-12-dnd-help.png` | عمود ساعات أعرض أو بسطرين عند الخط الكبير؛ `maxLines = 2`؛ `sizeIn(min = 28.dp)` للدائرة |
| G-04 | F-004، §5.8 | بخط 200 % لا يظهر **ما هو التذكير** على شاشة التذكير: الوقت والتأخر فقط، والعنوان تحت الشاشة حتى تمرّر (أهم شاشة لشخصية «أم علي») | `ar-200-04-alert.png`؛ `Alert.kt:240, 300` | عند `fontScale ≥ 1.5` انقل شبكة التأجيل إلى المنطقة القابلة للتمرير (كما يحدث تحت ارتفاع 640 dp) أو اطوها خلف «أجّل مدة أخرى…» |
| G-05 | F-103، F-064 | صور الفحص لا تغطي كل شاشة جديدة: لا صور للنغمة، نوع التنبيه، الشاشة الكاملة، التأجيل، الإشعار، بحث الإعدادات، التحديد الجماعي، الخطوات، السجل بأحداث، ملخص الاستعادة، الأداة، المربع السريع، الصلاة، ما الجديد، اللوحي. وخط 200 % بالعربية الفاتحة فقط | `QcScreenshotsTest.kt:136-187` | أضف هذه الحالات، و200 % بالإنجليزية والداكن |
| G-06 | F-104 | لا اهتزاز تأكيد عند «تم» و«أجّل» في شاشة التذكير ولا عند «حفظ» في المحرر؛ ولا اختبار | `Alert.kt` (لا `performHapticFeedback`)، `Edit.kt` | `LocalHapticFeedback.Confirm` على الأزرار الثلاثة + اختبار |
| G-07 | F-105 | لا انتقالات بين الشاشات ولا «رجوع تنبّؤي» (`enableOnBackInvokedCallback` غير موجود)؛ معيار القبول (لا وميض) مستوفى | `MainActivity.kt:397` | `AnimatedContent` مع `LocalReducedMotion` |
| G-08 | F-107 | الدليل لا يذكر: الإدخال الصوتي، القراءة بصوت، ملخص الصباح، سجل التغييرات، الإكمال التلقائي، «ما الجديد»، صفحة الخصوصية | `docs/GUIDE-ar.md`، `GUIDE-en.md` | سطر لكل ميزة |
| G-09 | F-017 | المواصفة: «إن لم يُحضَّر المشغّل خلال 3 ثوانٍ» يُنشر الإشعار على القناة الاحتياطية. لا مهلة؛ `prepare()` متزامن على الخيط الرئيسي، فمصدر عالق يعني تذكيراً صامتاً | `RingService.kt:92` | `prepareAsync()` مع مهلة 3 ث ثم `fallback("timeout")` + اختبار فوضى |
| G-10 | البوابة 1 | lint ليس «No issues found»: 7 تحذيرات و3 تلميحات (`lintDebug`)؛ و`lintRelease` النتيجة نفسها: 0 أخطاء، 7 تحذيرات، 3 تلميحات | `TwoPane.kt:67`، `Alert.kt:103`، `DeleteAll.kt:41, 62`، `Engine.kt:176`، `RingService.kt:90`، `Tones.kt:45` | أصلحها أو اكتب سبب كل استثناء في `lint.xml` |
| G-11 | البوابة 1 | المطلوب: كل اختبارات المنبّه والإشعار والخدمة والأذونات والمستقبلات عند API 26 و29 و31 و33 و34 و36. الواقع: `EngineReliabilityTest` وحده بالست؛ `AlertPipelineTest` و`UpgradeTest` و`HuntAlertTest` بـ26 و33 و36؛ `EngineTest` بـ36 فقط | `@Config` في الاختبارات | وسّع `sdk = [26, 29, 31, 33, 34, 36]` لهذه الصفوف |
| G-12 | البوابة 1 | نسخة الفحص `app-release-unsigned.apk` غير موقّعة (لا مجلد `keystore/` في هذه الشجرة)، فلم أتحقق من توقيع هذه النسخة بعينها. `tools/dist.sh` يتحقق من `CN=FastKiwi` فقط لا من البصمة | `tools/dist.sh:13` | وقّع في الشجرة الرئيسية وتحقق؛ ثبّت البصمة `8d3b91dc…6ae6e26` في `dist.sh` |
| G-13 | البوابة 5 | STANDARDS.md لم يُحدَّث لـ1.0.0: العنوان «v0.1.0»، «40 PNGs… 8 screens»، «Only SystemReceiver is exported» (الآن أيضاً `LockedReceiver` و`MainActivity` و`ShareActivity` والمربع)، «.ics import: Gap»، «Hijri: Not offered»، «UAX #9 Partial»، «SemVer 0.1.0, versionCode 1» | `STANDARDS.md:1, 3, 36, 56, 81, 82, 90, 97` | حدّثه لهذه النسخة |
| G-14 | البوابة 1، 6 | وثائق الإصدار قديمة: لا قسم 1.0.0 في CHANGELOG ولا سبب للزيادة في الحجم (+692 KB عن 0.1.1)؛ `اقرأني.txt` يصف 0.1.1 (ويُنسخ مع الـAPK في `dist.sh:19`) ويقول «الملف غير مشفّر» و«ics لا يُستعاد منه»؛ README «What it does (v0.1.0)»؛ COMPATIBILITY.md يقول 0.2.0 | `CHANGELOG.md:7`، `اقرأني.txt:1, 12, 100-102`، `README.md:22`، `docs/COMPATIBILITY.md:13` | اكتبها لـ1.0.0 |
| G-15 | النصوص | رسالة الاستعادة «استُعيدت البيانات: %1$s تذكير.» بلا جمع: «3 تذكير»، «11 تذكير»، وبالإنجليزية "1 reminders" | `strings.xml:65`؛ `MainActivity.kt:242`، `SettingsFlows.kt:178` | `<plurals>`. الاختبار `QcGapsTest.texts_…` يفشل الآن |
| G-16 | F-064، §5.16 | في عروض التقويم يظهر شريط الصحة فوق رأس التقويم، فينزل عنوان الفترة و«اليوم» وزر العرض 70 إلى 180 dp | `Home.kt:394-402, 417`؛ `ar-light-16…19` | اعرض الشريط تحت رأس التقويم |
| G-17 | §5.5 | مفتاح «عند الموعد» مفعّل لكنه معطَّل فيظهر رمادياً، فيُقرأ «مطفأ» | `EditorParts.kt:134`؛ `ar-light-03-edit.png` | علامة صح أو نص «دائماً» بدل مفتاح معطَّل |
| G-18 | البوابة 3 | التعذيب ليس أخضر: `UiMonkeyTortureTest.monkeyOnTheMainScreensAnotherSeed` يفشل بـ`NullPointerException` في مكبّر النص (`Magnifier.destroy`) تحت Robolectric، ومنذ تشغيل 03:32 | `torture/reports/summary-2026-11-15-0332.txt`؛ هذا الفحص | غالباً من بيئة المحاكاة لا من التطبيق: عطّل المكبّر في الاختبار أو التقط الاستثناء في الأداة، ثم أعد التشغيل الثقيل حتى ينجح |

### Q3

- F-011: لا اختبار يغيّر إذناً ثم يعود ويتحقق من الشريط والصحة (`MainActivity.kt:295`).
- F-023: `NotificationChannel.canBypassDnd()` لا يُقرأ، والمواصفة تذكره.
- F-028: التقرير بالإنجليزية داخل صفحة عربية (`HealthScreens.kt:92-125`، `HealthPages.kt:165`)؛ أضف سطراً عربياً يشرح ذلك.
- F-030: الملخص بـ`setAndAllowWhileIdle` على رمز 104 لا الخانة B (`Summary.kt:48`)؛ قد يتأخر دقائق.
- F-044: لا اختبار لرسالة رفض الوقت الماضي (`Alert.kt:385`).
- F-047: «ذكرى سنوية» بتنبيه قبل يوم والمواصفة أسبوع (`Templates.kt:52`).
- F-085: الحد 20 MB والمواصفة 5 MB و5,000 عنصر (`IcsImport.kt:86`).
- F-091: المرحلة 2 لا تعمل إلا بـ`tools/interchange.sh`؛ اجعلها جزءاً من بوابة الإصدار.
- `RingService.stop` قبل بدء الخدمة: `pendingStop = ringingId` يساوي 0 دائماً، فـ«تم» في أول أجزاء الثانية لا يوقف الرنين (`RingService.kt:206-211`).
- قواعد النسخ السحابي لا تستثني نطاقات التخزين المحمي بالجهاز (`device_*`) حيث ملف F-026 (أوقات فقط) (`backup_rules.xml`، `data_extraction_rules.xml`).
- التصميم: خانة فارغة في شبكة التأجيل (`Alert.kt:240`)؛ عناوين المجموعات بلون النص لا الأخضر (`Components.kt:92`)؛ صف التنقل في الشهر بلا عنوان؛ صفوف الأجندة بلا دائرة «تم»؛ «8:50 / ص» على سطرين في الأجندة بخط 200 %؛ علامة اليوم بيضاوية بخط 200 %؛ زر «قائمة جديدة» يغطي رأس «الفلاتر المحفوظة» بخط 200 %؛ "Arrived 22 hours late, The phone was off" بحرف كبير بعد الفاصلة؛ زر «افحص الآن» بلا لون الخطأ؛ لا اختبار لقائمة الأيقونات المعكوسة؛ اختبار النسخة العامة للإشعار يتحقق من الزر الأول فقط (`EngineTest.kt:189`)؛ العرض المتوسط 600 إلى 839 dp بلا نوافذ وسطية.
- النصوص: `DirectBoot.kt:48` لغة بلا `nu-latn` (لا أرقام فيها اليوم)؛ `EditorParts.kt:58` فاصلة «، » ثابتة لوصف TalkBack بالإنجليزية؛ `"%,d"` يضع فاصلة لاتينية «5,000» داخل جمل عربية (`IcsReportText.kt:176`، `IcsNotes.kt:18`)؛ وعد «وسيصلك في وقته» في `empty_all_body` (`strings.xml:175`)؛ "never silent" في `channel_alert_desc`؛ `essential_off#zero`؛ لا اختبار واحد يفحص كل `strings.xml` لهذه القواعد.

## التصميم (DESIGN-SPEC.md 1.0.0)

- **§3.1 الألوان، §3.2 الخط، §3.3 الأشكال والمسافات، §7.1 الرموز:** مطابقة تماماً (`Theme.kt:30-145`). لا ألوان ديناميكية.
- **§3.4 الأيقونات:** عائلة واحدة (94 رسماً من Material Symbols Rounded عبر `Ms.*`، لا `Icons.*`)، والعكس في الاتجاه
  العربي صحيح. ينقص اختبار قائمة المعكوسات.
- **§3.5 الحركة:** جزئي (G-07).
- **§3.6 خط 200 %:** ناجح في الرئيسية والمحرر والإعدادات والصحة والإضافة السريعة وتقرير التوافق، و«تم» ظاهر وفوق «أجّل» في
  شاشة التذكير. فاشل في عرض اليوم والأسبوع ومساعدة «عدم الإزعاج» وعنوان شاشة التذكير (G-03، G-04).
- **§3.7 التخطيطات:** الهاتف واللوحي (≥ 840 dp) مُنفَّذان ومختبَران (`TabletUiTest`)؛ 600 إلى 839 dp محتوى في الوسط فقط.
- **§4 قواعد الأزرار:** زر أساسي واحد في كل شاشة، «حفظ» مرة واحدة، «تم» في آخر الصف، الإلغاء أولاً في الحوارات، والحذف
  بلون الخطأ (`Components.kt:206-214`): مستوفاة.
- **§5 الشاشات:** مطابقة في الرئيسية والمحرر وشاشة التذكير والصحة والإضافة السريعة وتقرير التوافق، مع الانحرافات أعلاه.
- **§7.4 خطافات الاختبار:** كلها موجودة في الكود ومستعملة في اختبارات (`HomeUiTest`، `QuickUiTest`، `EditorUiTest`،
  `AlertUiTest`، `HealthUiTest`، `SettingsUiTest`، `CalendarUiTest`). النقص الوحيد: أزرار النسخة العامة الثلاثة.

## المعايير (STANDARDS.md)

| البند | الحالة في هذه النسخة | الدليل |
|---|---|---|
| target/compile SDK 36، min 26 | صحيح | `aapt2 dump badging` |
| محاذاة 16 KB | صحيح | `zipalign -c -P 16 -v 4`: «Verification successful»؛ كل ملفات `.so` الأربعة LOAD `p_align = 16384` ومخزّنة بلا ضغط |
| من الحافة إلى الحافة، لغة لكل تطبيق | صحيح | `enableEdgeToEdge`، `locales_config.xml`، `locales: 'ar' 'en'` |
| القنوات | صحيح | 6 قنوات ثابتة (`AlertPipelineTest.theChannelsAreTheFixedSet…`) |
| خدمة أمامية واحدة بنوع صحيح | صحيح | `RingService` `shortService`، إذن `FOREGROUND_SERVICE` فقط |
| «عدم الإزعاج» لا يُتجاوز | صحيح | لا `ACCESS_NOTIFICATION_POLICY` |
| الأذونات | صحيح | 0.1.1 + `FOREGROUND_SERVICE` بالضبط؛ لا إنترنت ولا موقع ولا تخزين ولا ميكروفون (`QcGapsTest.f_gate1_…`) |
| المكوّنات المصدَّرة | تغيّر | `MainActivity`، `ShareActivity` (نص فقط)، `NewReminderTile` (محمي بـ`BIND_QUICK_SETTINGS_TILE`)، `SystemReceiver` و`LockedReceiver` (يفحصان الإجراء، `Receivers.kt:37`، `DirectBoot.kt:115`). سليمة، لكن STANDARDS.md لا يذكرها (G-13) |
| النسخ السحابي | صحيح | كل النطاقات مستثناة؛ النقل بين الهواتف `data.json` و`device.xml` فقط |
| R8، غير قابل للتصحيح | صحيح | `isMinifyEnabled`، لا `application-debuggable` |
| UAX #9 | جزئي | G-02 |
| الحركة | جزئي | G-07 |
| صور الفحص | جزئي | 105 صورة، لا «40»؛ G-05 |

## حقائق APK

- الملف: `app/build/outputs/apk/release/app-release-unsigned.apk` (بناء `gradle :app:assembleRelease` في هذه الشجرة).
- الحجم: **2,817,755 بايت** (2.69 MB؛ السقف المرن 3.5 MB، الصارم 4 MB). 0.1.1 كان 2,125,996 بايت.
- SHA-256 (غير موقّع): `654f440203c5996b124fa83efe22e776dde3d337c0056dd497762af2471ffef8`.
- `package: com.fastkiwi.rememberme versionCode='3' versionName='1.0.0'`، `targetSdkVersion:'36'`، الاسم «ذكرني» / "Remember Me".
- الأذونات: `POST_NOTIFICATIONS`، `USE_EXACT_ALARM`، `SCHEDULE_EXACT_ALARM` (max 32)، `USE_FULL_SCREEN_INTENT`،
  `RECEIVE_BOOT_COMPLETED`، `VIBRATE`، `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`، `FOREGROUND_SERVICE`، و
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` الخاص بـAndroidX (موجود في 0.1.1). **لا `INTERNET`.**
- `apksigner verify`: «DOES NOT VERIFY … Missing META-INF/MANIFEST.MF» لأن النسخة غير موقّعة.
- للمقارنة فقط: `remember-me-android/app/build/outputs/apk/release/app-release.apk` (بناء سابق، 02:27، 1.0.0/3): توقيع v2،
  `CN=FastKiwi, O=FastKiwi, C=IQ`، بصمة الشهادة **`8d3b91dcacced148d11a51fb1333a509581ed2ce04ff362f2882c7cda6ae6e26`** (مطابقة).

## الاختبارات

- `:app:testDebugUnitTest` (مرة واحدة، 5 د 28 ث): **591 اختباراً، 0 فشل، 0 خطأ، 114 متخطّى**. المتخطّى: 112 من مجموعة التعذيب
  (تحتاج `TORTURE=1`)، و`InterchangeTest.phase2` (تحتاج ناتج Windows)، و`BlackBoxDriver`.
- `:app:lintDebug`: **0 أخطاء، 7 تحذيرات، 3 تلميحات**.
- `tools/interchange.sh` (F-091): **INTERCHANGE OK**. `node tools/windows-validate.cjs` (F-081): `verified=true`.
- التعذيب الخفيف (`TORTURE=1`، بلا `TORTURE_HEAVY`): **111 اختباراً، 1 فشل، 5 متخطّى** (أوقات: الخصائص 179 ث، الإجهاد 63 ث). الفشل `UiMonkeyTortureTest.monkeyOnTheMainScreensAnotherSeed`: `NullPointerException` في `android.widget.Magnifier$InternalPopupWindow.destroy` (مكبّر حقل النص في Robolectric بلا سطح رسم)، وهو نفسه في تقرير `torture/reports/summary-2026-11-15-0332.txt` (G-18). لم أشغّل الوضع الثقيل (`TORTURE_HEAVY`).
- اختبارات الفحص الجديدة: `QcGapsTest` و`QcBidiTest`: 8 تشغيلات، 5 نجحت (الأذونات بالضبط عند API 26 و33 و36، 10 ضغطات تأجيل = تأجيل واحد، اختبار التنبيه لا يغيّر بايتاً) و**3 تفشل عمداً لأنها تثبت فجوات**: `f082_…` (G-01)، `f102_…` («أُنجز «Meeting 9»» بلا عزل، G-02)، `texts_…` («3 تذكير»، "1 reminders"، G-15).

---
---

# Independent QC — Remember Me for Android 1.0.0

Gate 5 of [FEATURES.md](FEATURES.md), written on 2026-11-15 by an auditor who did not write the code. Checked commit:
`41f11aa` (branch `qc`). No app code was changed; QC tests were added under
`app/src/test/java/com/fastkiwi/rememberme/qc/`.

Grades: **Q1** blocks the release (a broken acceptance criterion of a reliability, privacy or data feature, or a
standard violation). **Q2** a visible deviation or a partly-met feature. **Q3** polish.

## Summary

- **80 approved features:** 67 met, 8 partly met, 0 not met, 5 need a real phone (the logic passes on the JVM; sound,
  vibration, real-time ringing and the locked-boot restart can only be judged on a phone): F-016, F-018, F-019,
  F-026, F-027.
- **Partly met:** F-017 (G-09), F-064 (G-03, G-05, G-16), F-082 (G-01), F-102 (G-02), F-103 (G-03, G-04, G-05),
  F-104 (G-06), F-105 (G-07), F-107 (G-08).
- **No Q1 in the code.** No reliability, privacy or data acceptance criterion is broken; permissions, the
  foreground service, 16 KB alignment and the absence of INTERNET all hold.
- **The release is still not shippable** for gate reasons, not code defects: the QC build is unsigned (no keystore in
  this worktree, G-12), gate 1 is incomplete (lint warnings G-10, API levels G-11), and gate 6 (the owner's
  27-item checklist and 14 days of use) has not started.
- **18 Q2 gaps** (G-01 to G-18, table above, same ids). The most user-visible: at 200 % text the reminder screen
  does not show what the reminder is without scrolling (G-04); the Day/Week views and the DND help clip at 200 %
  (G-03); Latin titles inside Arabic sentences are not isolated (G-02).
- **Tests:** 591 run, 0 failed, 114 skipped (torture and the Windows phase need a switch). Lint: 0 errors,
  7 warnings, 3 hints. Light torture run: 110 tests, 1 failure (G-18). Windows round trip both ways: OK.
- **APK:** 2,817,755 bytes, versionCode 3, versionName 1.0.0, targetSdk 36, permissions = 0.1.1 + `FOREGROUND_SERVICE`,
  **no INTERNET**, 16 KB aligned. The certificate could not be checked on this unsigned build; an earlier signed 1.0.0
  build from the main worktree carries the expected `8d3b91dc…6ae6e26`.

## Q2 gaps (one line each; detail in the Arabic table)

- **G-01 F-082:** the pre-update snapshot is keyed by versionCode only (`Repo.kt:29-32`); rc→rc and rc→1.0.0 (all
  code 3) keep no copy, against the spec's "versionCode **and** versionName". Fix: store `lastVersionName`.
  `QcGapsTest.f082_…` fails.
- **G-02 F-102:** titles inside sentences are not bidi-isolated (`Home.kt:409, 502, 684-685`, `ReminderWidget.kt:178,
  192`, `Settings.kt:565`, `QuickAddSheet.kt:345`, `TemplatesScreen.kt:105, 119`). Fix: `L10n.isolate`. `QcBidiTest` fails.
- **G-03 F-103/F-064:** 200 % clipping: Day hour labels lose «ص/م» and the now-label overlaps a title (`Day.kt:121,
  125, 186-194`); Week titles cut to 5 letters (`Week.kt:115`); DND-help step numbers clipped (`HealthComponents.kt:162`).
- **G-04 F-004/§5.8:** at 200 % the reminder screen shows the time and lateness but not the title (`ar-200-04-alert.png`).
  Fix: move the snooze grid into the scroll at fontScale ≥ 1.5.
- **G-05 F-103/F-064:** QC screenshots miss ~15 new screens/states and have 200 % only in Arabic light
  (`QcScreenshotsTest.kt:136-187`).
- **G-06 F-104:** no confirm haptic on the reminder screen's «تم»/«أجّل» or the editor's «حفظ»; no test.
- **G-07 F-105:** no screen transitions, no predictive back (`MainActivity.kt:397`); "no flashing" is met.
- **G-08 F-107:** the guides omit voice input, read aloud, morning summary, change history, auto-complete, What's new
  and the privacy page.
- **G-09 F-017:** no 3-second prepare timeout; `MediaPlayer.prepare()` is synchronous on the main thread
  (`RingService.kt:92`), so a stuck source means a silent reminder.
- **G-10 gate 1:** lint is not "No issues found" (7 warnings; `lintRelease` too: 0 errors, 7 warnings, 3 hints).
- **G-11 gate 1:** only `EngineReliabilityTest` runs at all six API levels; `EngineTest` runs at 36 only.
- **G-12 gate 1:** the RC built here is unsigned, so its signature was not verified; `tools/dist.sh:13` checks
  `CN=FastKiwi` only. Fix: pin the certificate digest.
- **G-13 gate 5:** STANDARDS.md is still the 0.1.0 text (exported components, `.ics` import, Hijri, UAX #9,
  screenshot count, SemVer row).
- **G-14 gates 1/6:** no 1.0.0 CHANGELOG entry and no reason for the +692 KB growth; `اقرأني.txt` (shipped by
  `dist.sh`) describes 0.1.1; README and COMPATIBILITY.md are stale.
- **G-15 texts:** `import_done` is not a plural («3 تذكير», "1 reminders"); `QcGapsTest.texts_…` fails.
- **G-16 F-064/§5.16:** in calendar views the health banner sits above the calendar header (`Home.kt:394-417`).
- **G-17 §5.5:** the editor's «عند الموعد» switch is disabled and renders grey, reading as "off" (`EditorParts.kt:134`).
- **G-18 gate 3:** the torture run is not green: `UiMonkeyTortureTest.monkeyOnTheMainScreensAnotherSeed` fails with an
  NPE in the text magnifier (`Magnifier.destroy`) under Robolectric, as it did at 03:32. Most likely a harness issue,
  not an app crash; fix the harness and rerun the heavy suite.

## Design, standards, APK, tests

See the Arabic sections above (same facts): DESIGN-SPEC §3.1–§3.3 and §7.1 tokens match exactly; icons are one family
with correct RTL mirroring; motion is partial; 200 % passes on Home, Editor, Settings, Health, Quick Add and the
compat report but fails on Day, Week, DND help and the reminder title; §4 button rules hold; every §7.4 hook exists
and is tested except the public notification's three actions. STANDARDS rows for target SDK, 16 KB, edge-to-edge,
per-app language, channels, the short foreground service, DND, permissions, backups and R8 hold for this build;
UAX #9, motion and QC screenshots are partial; the document itself is stale (G-13).

QC tests added (`app/src/test/java/com/fastkiwi/rememberme/qc/`): `QcGapsTest` and `QcBidiTest`: 8 runs, 5 pass (exact permission set at API 26/33/36; ten snooze taps = one snooze; the alert test leaves `data.json` byte-identical) and **3 fail on purpose, proving gaps**: `f082_…` (G-01), `f102_…` (snackbar «أُنجز «Meeting 9»» has no isolation, G-02), `texts_…` («3 تذكير», "1 reminders", G-15).


## المعالجة بعد التقرير (2026-11-15) — Resolution

كل بنود Q2 عولجت في `wip/1.0-base`، ولكل منها اختبار:

| البند | المعالجة |
|---|---|
| G-01 | نسخة ما قبل التحديث بمفتاح رقم البناء **واسم الإصدار** (`Repo.kt`، `QcGapsTest`) |
| G-02 | عزل نص المستخدم داخل الجمل (`L10n.isolate`) في كل المواضع (`QcBidiTest`، `QcFixUiTest`) |
| G-03 | اليوم والأسبوع وأرقام خطوات «عدم الإزعاج» بخط 200 % (`QcFixUiTest`) |
| G-04 | العنوان أولًا بخط كبير، و«تم» ظاهرة كاملة (`BlackBoxFixesUiTest`) |
| G-05 | صور QC لكل الشاشات والأوراق: 277 صورة، عربي/إنجليزي، فاتح/داكن، 200 % بالعربية والإنجليزية، والجهاز اللوحي |
| G-06 | اهتزاز تأكيد خفيف يحترم إعداد الهاتف على «تم»/«أجّل»/«حفظ» |
| G-07 | انتقالات المحور المشترك، وتلاشٍ مع «تقليل الحركة»، والرجوع التنبّئي (`ScreenStackHost`) |
| G-08 | الدليلان يشملان كل الميزات |
| G-09 | تحضير النغمة غير متزامن مع حد 3 ثوانٍ لكل مصدر ثم البديل (`RingServiceTest`) |
| G-10 | lint: لا ملاحظات (debug وrelease) |
| G-11 | اختبارات المحرك والتنبيه على API 26 و29 و31 و33 و34 و36 |
| G-12 | `dist.sh` يتحقق من بصمة الشهادة نفسها |
| G-13/G-14 | STANDARDS وCHANGELOG وREADME وCOMPATIBILITY واقرأني محدّثة لـ1.0.0 |
| G-15 | جمع عربي صحيح في رسالة الاستعادة |
| G-16 | لا شريط فوق رأس التقويم |
| G-17 | «عند الموعد» تظهر «دائمًا» لا مفتاحًا معطّلًا |
| G-18 | فشل واحد في أداة الاختبار (عدسة النص في Robolectric، بلا كود من التطبيق)؛ موثّق |

الميزات: 75 مُستوفاة، و5 تحتاج هاتف المالك (F-016، F-018، F-019، F-026، F-027).

## مشكلة أداة معروفة: فشلا monkey (قرار القائد، 2026-11-15) — Known tool issue

في تشغيل التعذيب الشامل الأخير (`torture/reports/summary-2026-11-15-0804.txt`) فشل اختباران فقط، وكلاهما في
`UiMonkeyTortureTest` (`monkeyOnTheMainScreens` و`monkeyOnTheMainScreensAnotherSeed`) بالخطأ نفسه:

```
java.lang.NullPointerException: Cannot invoke "android.view.Surface.destroy()" because "this.mSurface" is null
    at android.widget.Magnifier$InternalPopupWindow.destroy(Magnifier.java)
    at android.widget.Magnifier.dismiss(Magnifier.java)
    … (Compose text-field magnifier, androidx.compose.foundation.text.*)
```

**سبب الاستبعاد:** المكدس كله من Robolectric وعدسة النص في Compose، ولا يمرّ بأي كود من التطبيق
(`com.fastkiwi.rememberme`). Robolectric لا يعطي عدسة النص سطحًا (Surface)، أما الهاتف فيعطيها؛ ويحدث عند لمستين
سريعتين على حقل نص في جولة monkey العشوائية. قرّر القائد أنهما **لا يمنعان إصدار 1.0.0**.
التعديل الذي يتجنبهما في أداة الاختبار رُفض إذنه لأحد الوكلاء، ولم يُنفَّذ (ولا يُنفَّذ إلا بطلب عبر مالك الجلسة).

English: the two remaining torture failures are both in the UI monkey and both throw the NPE above inside
Robolectric's `Magnifier` (no app frame in the stack). They are a known Robolectric limit, recorded here by the
lead's decision; they do not block 1.0.0. The harness change that avoids them was refused for an agent and was
not made.
