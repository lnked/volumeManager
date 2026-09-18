#!/system/bin/sh
# Magisk late_start service: mark root volume path ready after boot.
# Per-app volume itself is applied by the APK via libsu RootService.

MODDIR=${0%/*}

until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 1
done

mkdir -p /data/adb/volumemanager
echo "1" > /data/adb/volumemanager/module_active
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > /data/adb/volumemanager/late_start_at

log -t volumemanager_root "late_start ready moddir=$MODDIR"
