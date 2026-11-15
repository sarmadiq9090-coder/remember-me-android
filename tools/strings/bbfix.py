# Fixes from the black-box user test (rm-blackbox-sessions/BLACKBOX-REPORT.md, U1-2, U2-1, U2-4, U2-5, U2-6, U2-10).
# Keys start with "bbfix_" so they never clash with other areas.
S = [
# U2-10: «منبّه مهم» rings for the ring length set in Settings, then stops (it does not ring until handled).
("bbfix_style_alarm_body", "يرنّ بصوت المنبّه حتى والهاتف صامت، لمدة تختارها في الإعدادات (%1$s)، ولا يتجاوز «عدم الإزعاج» إلا إذا سمحت له.", "Rings at alarm volume even on a silent phone, for the length you choose in Settings (%1$s). It does not get through Do Not Disturb unless you allow it."),
# U2-6: a step's row ticks it; editing its text is a separate, labelled action.
("bbfix_step_edit", "عدّل الخطوة: %1$s", "Edit step: %1$s"),
("bbfix_step_edit_done", "أنهِ تعديل الخطوة", "Finish editing the step"),
("bbfix_step_new", "خطوة جديدة", "New step"),
# U2-1: under «كل شيء ضروري جاهز» when the sound will not be heard.
("bbfix_sum_notif_zero", "صوت الإشعارات على الصفر: لن تسمع التذكير العادي.", "Notification volume is at zero: you will not hear a normal reminder."),
("bbfix_sum_alarm_zero", "صوت المنبّه على الصفر: لن تسمع المنبّه المهم.", "Alarm volume is at zero: you will not hear an important alarm."),
("bbfix_sum_normal_muted", "قناة «تذكيرات عادية» مكتومة: لن تسمع التذكير العادي.", "The “Reminders” channel is muted: you will not hear a normal reminder."),
("bbfix_sum_alarm_muted", "قناة «منبّهات مهمة» مكتومة: المنبّه المهم لن يرنّ.", "The “Important alarms” channel is muted: an important alarm will not ring."),
# U2-4: the alert test, after «لا» to «هل سمعت الصوت؟»: what to check, most likely first.
("bbfix_hint_notif_zero", "صوت الإشعارات على الصفر. ارفعه بزر الصوت أو من إعدادات الصوت.", "Notification volume is at zero. Turn it up with the volume keys or in Sound settings."),
("bbfix_hint_alarm_zero", "صوت المنبّه على الصفر. ارفعه من إعدادات الصوت.", "Alarm volume is at zero. Turn it up in Sound settings."),
("bbfix_hint_ringer", "الهاتف على الصامت أو الاهتزاز، فالتذكير العادي لا يرنّ. غيّر وضع الرنين، أو اجعل التذكير «منبّه مهم».", "The phone is on silent or vibrate, so a normal reminder does not ring. Change the ringer mode, or make the reminder an important alarm."),
("bbfix_hint_dnd", "«عدم الإزعاج» مفعّل ويُسكت الصوت. المنبّه المهم يرنّ أثناءه إن سمحت له.", "Do Not Disturb is on and silences the sound. An important alarm rings during it if you allow it."),
("bbfix_hint_channel_normal", "قناة «تذكيرات عادية» مكتومة في إعدادات أندرويد. افتحها وشغّل الصوت.", "The “Reminders” channel is muted in Android settings. Open it and turn the sound on."),
("bbfix_hint_channel_alarm", "قناة «منبّهات مهمة» مكتومة في إعدادات أندرويد. افتحها وشغّل الصوت.", "The “Important alarms” channel is muted in Android settings. Open it and turn the sound on."),
("bbfix_hint_none", "لم يجد ذكرني سببًا في الإعدادات التي يقرؤها. تحقّق من مستوى الصوت، ومن سماعة بلوتوث متصلة.", "Remember Me found no cause in the settings it can read. Check the volume, and whether Bluetooth headphones are connected."),
]
