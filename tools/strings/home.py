# Home, swipe, snooze sheet, bulk selection, search and the reminder screen 1.0.0
# (DESIGN-SPEC §5.1, §5.2, §5.8, §5.9, §5.17; FEATURES.md F-025, F-043, F-045, F-052, F-061, F-063, F-065, F-066).
# Keys that another area may also need carry the home_ prefix, so parallel branches never clash.
S = [
# View menu (§5.1)
("home_view_menu", "طريقة العرض", "View"),
("home_view_list", "القائمة", "List"),
("home_view_agenda", "أجندة", "Agenda"),
("home_view_day", "يوم", "Day"),
("home_view_week", "أسبوع", "Week"),
("home_view_month", "شهر", "Month"),
# Quick filters (F-063)
("chip_all", "الكل", "All"),
("chip_lists", "القوائم…", "Lists…"),
# Rows (§5.1)
("select", "تحديد", "Select"),
("home_select_named", "تحديد: %1$s", "Select: %1$s"),
("state_snoozed", "مؤجّل", "Snoozed"),
("style_tag_short", "منبّه", "Alarm"),
("home_style_alarm", "منبّه مهم", "Important alarm"),
("vibrate_only", "اهتزاز فقط", "Vibrate only"),
("home_state_upcoming", "قادم", "Upcoming"),
("home_state_due", "مستحق الآن", "Due now"),
("home_state_overdue", "متأخر", "Overdue"),
("home_state_done", "منجز", "Done"),
# Undo (F-052)
("done_undo_msg", "أُنجز «%1$s»", "Done: “%1$s”"),
("snoozed_until_msg", "أُجّل حتى %1$s", "Snoozed until %1$s"),
# Snooze sheet (§5.9)
("snooze_title", "أجّل", "Snooze"),
("snooze_more", "أجّل مدة أخرى", "Snooze longer"),
("until_time", "حتى %1$s", "until %1$s"),
("home_tomorrow_same", "غدًا في الوقت نفسه", "Tomorrow, same time"),
("repeat_unchanged", "التكرار لا يتغيّر", "The repeat does not change"),
# Reminder screen (§5.8)
("snooze_hint3", "أجّلته 3 مرات. اختر وقتًا أنسب؟", "Snoozed 3 times. Pick a better time?"),
("late_reason_off", "كان الهاتف مطفأ", "The phone was off"),
("volume_silence_hint", "اضغط زر الصوت لإسكات الرنين", "Press a volume key to silence"),
("home_steps", "الخطوات", "Steps"),
# Bulk selection (§5.17, F-066)
("select_all", "تحديد الكل", "Select all"),
("clear_selection", "إلغاء التحديد", "Clear selection"),
("export_selected", "تصدير المحدد", "Export selected"),
("bulk_delete", "حذف", "Delete"),
("bulk_priority", "الأهمية", "Priority"),
("bulk_move", "نقل", "Move"),
("bulk_move_title", "نقل إلى", "Move to"),
("home_move_category", "التصنيف: %1$s", "Category: %1$s"),
("home_move_list", "القائمة: %1$s", "List: %1$s"),
("bulk_trash_body", "تبقى في السلة 30 يومًا ويمكنك استعادتها.", "They stay in the trash for 30 days and can be restored."),
("move_to_trash", "نقل إلى السلة", "Move to trash"),
# Tablet detail pane (§3.7)
("home_detail_empty", "اختر تذكيرًا لعرض تفاصيله", "Choose a reminder to see its details"),
("home_alerts", "التنبيهات", "Alerts"),
("home_at_time", "عند الموعد", "At the time"),
("home_no_repeat", "لا يتكرر", "Does not repeat"),
]

P = {
"alerts_n": ({"zero": "%1$s تنبيه", "one": "تنبيه واحد", "two": "تنبيهان", "few": "%1$s تنبيهات", "many": "%1$s تنبيهًا", "other": "%1$s تنبيه"},
             {"one": "%1$s alert", "other": "%1$s alerts"}),
"selected_n": ({"zero": "%1$s محددة", "one": "%1$s محدد", "two": "%1$s محددان", "few": "%1$s محددة", "many": "%1$s محددة", "other": "%1$s محددة"},
               {"one": "%1$s selected", "other": "%1$s selected"}),
"bulk_trash_q": ({"zero": "نقل %1$s تذكير إلى السلة؟", "one": "نقل تذكير واحد إلى السلة؟", "two": "نقل تذكيرين إلى السلة؟", "few": "نقل %1$s تذكيرات إلى السلة؟", "many": "نقل %1$s تذكيرًا إلى السلة؟", "other": "نقل %1$s تذكير إلى السلة؟"},
                 {"one": "Move %1$s reminder to the trash?", "other": "Move %1$s reminders to the trash?"}),
"bulk_done_q": ({"zero": "إنجاز %1$s تذكير؟", "one": "إنجاز تذكير واحد؟", "two": "إنجاز تذكيرين؟", "few": "إنجاز %1$s تذكيرات؟", "many": "إنجاز %1$s تذكيرًا؟", "other": "إنجاز %1$s تذكير؟"},
                {"one": "Mark %1$s reminder done?", "other": "Mark %1$s reminders done?"}),
"bulk_repeating_note": ({"zero": "لا شيء منها متكرر.", "one": "واحد منها متكرر: تتوقف سلسلته.", "two": "اثنان منها متكرران: تتوقف سلسلتاهما.", "few": "%1$s منها متكررة: تتوقف سلاسلها.", "many": "%1$s منها متكررة: تتوقف سلاسلها.", "other": "%1$s منها متكررة: تتوقف سلاسلها."},
                        {"one": "%1$s of them repeats: its series stops.", "other": "%1$s of them repeat: their series stop."}),
"bulk_done_msg": ({"zero": "أُنجز %1$s تذكير", "one": "أُنجز تذكير واحد", "two": "أُنجز تذكيران", "few": "أُنجز %1$s تذكيرات", "many": "أُنجز %1$s تذكيرًا", "other": "أُنجز %1$s تذكير"},
                  {"one": "%1$s reminder done", "other": "%1$s reminders done"}),
"bulk_trash_msg": ({"zero": "نُقل %1$s تذكير إلى السلة", "one": "نُقل تذكير واحد إلى السلة", "two": "نُقل تذكيران إلى السلة", "few": "نُقلت %1$s تذكيرات إلى السلة", "many": "نُقل %1$s تذكيرًا إلى السلة", "other": "نُقل %1$s تذكير إلى السلة"},
                   {"one": "%1$s reminder moved to the trash", "other": "%1$s reminders moved to the trash"}),
"bulk_changed_msg": ({"zero": "عُدّل %1$s تذكير", "one": "عُدّل تذكير واحد", "two": "عُدّل تذكيران", "few": "عُدّلت %1$s تذكيرات", "many": "عُدّل %1$s تذكيرًا", "other": "عُدّل %1$s تذكير"},
                     {"one": "%1$s reminder changed", "other": "%1$s reminders changed"}),
}
