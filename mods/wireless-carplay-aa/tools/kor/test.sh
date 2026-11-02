#!/bin/sh
# Synthetic NEEV.KOR 260902 projection test; no private stock tree required.
set -e
cd "$(dirname "$0")/../../../.."
REPO=$PWD
[ -x out/lpatch-host ] || sh build.sh lpatch >/dev/null
CP=$(mktemp -d); T=$(mktemp -d); trap 'rm -rf "$T" "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" core/runtime/java/hmgapi/engine/*.java 2>/dev/null
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 \
    HMGAPI_PRODUCT=NEEV.KOR.0000.V039.001.260902 HMGAPI_ONEBIN=neev_kr \
    HMGAPI_LPATCH=$REPO/out/lpatch-host java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
tree() { (cd "$R/system" && find . -type f | sort | while read -r f; do md5sum "$f"; done); }

for case in absent present; do
    R=$T/$case
    mkdir -p "$R/system/etc/permissions" "$R/system/onebin/neev_kr" \
        "$R/data/hmgapi/mods" "$R/data/hmgapi/settings" "$R/sys/bus/sdio/devices"
    printf 'ro.build.product=NEEV.KOR.0000.V039.001.260902\nro.board.platform=tcc803x\nro.mobis.onebindir=neev_kr\n' > "$R/props.txt"
    printf '%s\n' \
        '-/system/etc/permissions/com.hkmc.software.wifihotspot.xml' \
        '-/system/etc/permissions/com.hkmc.software.connectivity.androidauto_wireless.xml' \
        '-/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml' \
        > "$R/system/onebin/neev_kr/featurexml.cfg"
    printf 'ro.build.product=NEEV.KOR.0000.V039.001.260902\n' > "$R/system/build.prop"
    [ "$case" = absent ] || printf '<permissions><feature name="com.hkmc.software.wifihotspot" /></permissions>\n' \
        > "$R/system/etc/permissions/com.hkmc.software.wifihotspot.xml"
    cp -R mods/wireless-carplay-aa "$R/data/hmgapi/mods/"
    BEFORE=$(tree)
    eng plan; check "$case plan" 0 "$rc"
    has "$case Wi-Fi uncertainty warning" 'Wi-Fi hardware is unconfirmed' "$out"
    eng apply; check "$case apply" 0 "$rc"
    [ "$rc" = 0 ] || echo "$out"
    has "$case hotspot removal disabled" '#-/system/etc/permissions/com.hkmc.software.wifihotspot.xml' \
        "$(cat "$R/system/onebin/neev_kr/featurexml.cfg")"
    check "$case hotspot feature installed" yes \
        "$([ -f "$R/system/etc/permissions/com.hkmc.software.wifihotspot.xml" ] && echo yes || echo no)"
    check "$case no Wi-Fi client feature" no \
        "$([ -f "$R/system/etc/permissions/android.hardware.wifi.xml" ] && echo yes || echo no)"
    check "$case no Wi-Fi client property" no \
        "$([ -f "$R/data/property/persist.wifi.state" ] && echo yes || echo no)"
    eng restore; check "$case restore" 0 "$rc"
    check "$case /system byte-identical" "$BEFORE" "$(tree)"
done
exit "$fail"
