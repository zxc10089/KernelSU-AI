#!/system/bin/sh
# KSU Hide Props -- first pass (post-fs-data).
#
# Applies props.txt and then drops the traces that can only be removed, not
# overwritten. post-fs-data is the earliest point at which a KernelSU module
# script runs, so the table is in place before most framework services start.
#
# Why a resident module at all: resetprop is a runtime rewrite, and the one-key
# pipeline deliberately forces one reboot. Without a module that re-applies the
# table on every boot, every property would revert on the next restart.

MODDIR=${0%/*}
LOGFILE=/data/adb/ksu-hide-props.log

# defs.rs:6-7,17,20 -- BINARY_DIR = /data/adb/ksu/bin/, so the multicall symlink is
# here; /data/adb/ksud is the daemon itself and is NOT a resetprop entry point.
RESETPROP=/data/adb/ksu/bin/resetprop
if [ ! -x "$RESETPROP" ]; then
    RESETPROP=resetprop
fi

if ! command -v "$RESETPROP" >/dev/null 2>&1; then
    echo "$(date '+%Y-%m-%d %H:%M:%S') resetprop not found; properties NOT applied" >> "$LOGFILE"
    exit 0
fi

{
    echo "$(date '+%Y-%m-%d %H:%M:%S') post-fs-data: applying $MODDIR/props.txt"
    "$RESETPROP" -n -f "$MODDIR/props.txt" 2>&1

    # Trace removal. resetprop -d exits non-zero when the property is absent, so
    # each deletion is deliberately tolerant: a missing trace is a success, not a
    # failure, and one absent name must not abort the rest of the list.
    #
    # NOTE: the report also lists persist.com.luckyzyx.luckytool.*, which is a
    # wildcard. resetprop -d takes an exact name, so that family is reported by
    # _tools/hiding-status.ps1 instead of being deleted here. No silent skip.
    for name in \
        persist.service.adb.enable \
        persist.service.debuggable \
        persist.zygote.app_data_isolation \
        persist.hyperceiler.log.level
    do
        if "$RESETPROP" -n -d "$name" >/dev/null 2>&1; then
            echo "deleted $name"
        else
            echo "absent (ok) $name"
        fi
    done
} >> "$LOGFILE" 2>&1
