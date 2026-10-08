#!/system/bin/sh
# KSU Hide Props -- second pass (service).
#
# Some names in the table (notably the ro.boot.* family, which originates in the
# kernel cmdline) can be re-created by the framework after post-fs-data. service
# runs later in boot, so re-applying the whole table here is the cheap way to keep
# the trace clean for apps that start afterwards.
#
# This is intentionally idempotent: resetprop -f rewrites the same values.

MODDIR=${0%/*}
LOGFILE=/data/adb/ksu-hide-props.log

# defs.rs:6-7 -- BINARY_DIR = /data/adb/ksu/bin/ (not /data/adb/ksud/bin/).
RESETPROP=/data/adb/ksu/bin/resetprop
if [ ! -x "$RESETPROP" ]; then
    RESETPROP=resetprop
fi

command -v "$RESETPROP" >/dev/null 2>&1 || exit 0

{
    echo "$(date '+%Y-%m-%d %H:%M:%S') service: re-applying $MODDIR/props.txt"
    "$RESETPROP" -n -f "$MODDIR/props.txt" 2>&1
} >> "$LOGFILE" 2>&1
