# Lead integration strings (wiring between areas).
S = [
("template_saved", "حُفظ القالب في «قوالبي»", "Saved to “My templates”"),
("history_cleared", "مُسح سجل التغييرات", "Change history cleared"),
("change_not_saved", "لم يُحفظ التغيير. تحقّق من المساحة الفارغة في الهاتف ثم أعد المحاولة.", "The change was not saved. Check the free space on the phone and try again."),
("banner_notifications_off", "الإشعارات مطفأة: لن يظهر أي تذكير.", "Notifications are off: no reminder will show."),
("banner_autobackup_failed", "لم تُحفظ النسخة التلقائية الأسبوعية. اختر المجلد من جديد في الإعدادات.", "The weekly automatic copy was not saved. Choose the folder again in Settings."),
("h_autobackup", "النسخة التلقائية الأسبوعية", "Weekly automatic copy"),
("about_voice", "الإدخال الصوتي وحده يمرّ عبر خدمة الكلام في هاتفك (غالبًا من Google)، وقد تعمل هذه الخدمة عبر الإنترنت. نطلب منها العمل دون إنترنت على الهاتف نفسه حين يمكن ذلك. ذكرني نفسه لا يملك إذن الميكروفون.", "Only voice input goes through your phone's speech service (usually Google's), which may work over the internet. We ask it to work offline, on the phone itself, when it can. Remember Me itself has no microphone permission."),
("tone_bundled_default", "نغمة ذكرني 1 (لا توجد نغمة افتراضية في الهاتف)", "Remember Me tone 1 (the phone has no default tone)"),
]

P = {
"import_done": ({"zero": "استُعيدت البيانات: %1$s تذكير. حُفظت بياناتك السابقة في «نسخ الأمان».",
                 "one": "استُعيدت البيانات: تذكير واحد. حُفظت بياناتك السابقة في «نسخ الأمان».",
                 "two": "استُعيدت البيانات: تذكيران. حُفظت بياناتك السابقة في «نسخ الأمان».",
                 "few": "استُعيدت البيانات: %1$s تذكيرات. حُفظت بياناتك السابقة في «نسخ الأمان».",
                 "many": "استُعيدت البيانات: %1$s تذكيرًا. حُفظت بياناتك السابقة في «نسخ الأمان».",
                 "other": "استُعيدت البيانات: %1$s تذكير. حُفظت بياناتك السابقة في «نسخ الأمان»."},
                {"one": "Data restored: %1$s reminder. Your previous data was kept in Safety copies.",
                 "other": "Data restored: %1$s reminders. Your previous data was kept in Safety copies."}),
}
