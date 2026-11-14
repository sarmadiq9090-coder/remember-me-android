# Calendar views 1.0.0 (DESIGN-SPEC §5.16, FEATURES.md F-064). Keys carry the cal_ prefix so they never
# clash with Home's view menu keys (view_*); the texts are those of DESIGN-SPEC §7.3.
S = [
("cal_view_label", "طريقة العرض", "View"),
("cal_view_agenda", "أجندة", "Agenda"),
("cal_view_day", "يوم", "Day"),
("cal_view_week", "أسبوع", "Week"),
("cal_view_month", "شهر", "Month"),
("cal_prev_day", "اليوم السابق", "Previous day"),
("cal_next_day", "اليوم التالي", "Next day"),
("cal_prev_week", "الأسبوع السابق", "Previous week"),
("cal_next_week", "الأسبوع التالي", "Next week"),
("cal_prev_month", "الشهر السابق", "Previous month"),
("cal_next_month", "الشهر التالي", "Next month"),
("cal_now_at", "الآن %1$s", "Now %1$s"),
("cal_more_n", "+%1$s أخرى", "+%1$s more"),
("cal_nothing", "لا شيء", "Nothing"),
("cal_range", "%1$s إلى %2$s", "%1$s to %2$s"),
("cal_late", "متأخر", "Late"),
("cal_snoozed", "مؤجّل", "Snoozed"),
("cal_agenda_empty", "لا تذكيرات قادمة", "No upcoming reminders"),
]

P = {
"cal_reminders_n": ({"zero": "لا تذكيرات", "one": "تذكير واحد", "two": "تذكيران", "few": "%1$s تذكيرات", "many": "%1$s تذكيرًا", "other": "%1$s تذكير"},
                    {"one": "%1$s reminder", "other": "%1$s reminders"}),
}
