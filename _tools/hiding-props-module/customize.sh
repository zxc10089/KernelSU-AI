#!/system/bin/sh
# KSU Hide Props -- install hook.
#
# Nothing is unpacked from /sdcard: the table ships inside the module and is read
# at boot. The chmod is the one real job here -- the zip is produced by
# PowerShell's Compress-Archive, which does not carry the executable bit, so the
# two boot scripts are marked executable explicitly at install time.

ui_print "- KSU Hide Props: system property laundering table"

chmod 0755 "$MODPATH/post-fs-data.sh" "$MODPATH/service.sh" 2>/dev/null

if [ ! -f "$MODPATH/props.txt" ]; then
    ui_print "! props.txt is missing from the module; nothing will be applied"
    abort "KSU Hide Props: props.txt missing"
fi

ui_print "  post-fs-data.sh + service.sh re-apply the table on every boot"
ui_print "  log: /data/adb/ksu-hide-props.log"
