"""Vendors Material Symbols Rounded (Apache-2.0, github.com/google/material-design-icons) as
res/drawable/ms_<name>.xml (DESIGN-SPEC §3.4). Mirroring is set here, from the spec's list only.

Usage: python tools/vendor_symbols.py <downloaded-dir> app/src/main/res/drawable
<downloaded-dir> holds <name>_24px.xml / <name>_fill1_24px.xml as fetched from
symbols/android/<name>/materialsymbolsrounded/ in that repository.
"""
import os, re, sys

NAMES = """arrow_back arrow_forward chevron_left chevron_right close calendar_view_month search settings more_vert add
radio_button_unchecked check_circle warning repeat snooze event flag label tag list filter_alt notifications alarm
music_note library_music folder vibration play_arrow stop fullscreen push_pin visibility_off record_voice_over checklist
drag_indicator health_and_safety notifications_active do_not_disturb_on error info help phone_android battery_alert
refresh history dashboard_customize settings_backup_restore share content_copy select_all drive_file_move delete mosque
calendar_month wb_twilight mic check edit expand_more expand_less undo redo volume_up volume_off format_list_bulleted
sort notes receipt_long open_in_new send lock schedule remove language contrast cloud_off verified_user hourglass_empty
download upload restart_alt date_range keyboard_arrow_right today swipe text_fields palette dark_mode backup
restore_from_trash bolt tune""".split()
FILL1 = ["check_circle", "flag"]
MIRROR = set("""arrow_back arrow_forward chevron_left chevron_right label list format_list_bulleted checklist sort notes undo
redo volume_up volume_off drive_file_move receipt_long open_in_new send keyboard_arrow_right""".split())

def fix(xml, mirrored):
    xml = re.sub(r'\s*android:autoMirrored="(true|false)"', "", xml)
    xml = re.sub(r'\s*android:tint="[^"]*"', "", xml)  # tinted by Compose (LocalContentColor)
    if mirrored:
        xml = xml.replace("<vector ", '<vector android:autoMirrored="true" ', 1)
    return xml

if __name__ == "__main__":
    src, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    missing = []
    for name in NAMES + [n + "_fill1" for n in FILL1]:
        f = os.path.join(src, name + "_24px.xml")
        if not os.path.exists(f):
            missing.append(name); continue
        base = name.replace("_fill1", "")
        xml = fix(open(f, encoding="utf-8").read(), base in MIRROR)
        open(os.path.join(out, "ms_" + name + ".xml"), "w", encoding="utf-8", newline="\n").write(xml)
    print(len(NAMES) + len(FILL1) - len(missing), "written; missing:", " ".join(missing) or "none")
