#!/bin/sh
# Off-car test of mod system requirements (hmgapi.engine.SystemCheck, and the
# engine refusing or leaving out parts) on a desktop JVM, against a fake US EV6.
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d)
javac --release 8 -Xlint:-options -nowarn -d "$CP" core/runtime/java/hmgapi/engine/*.java
T=$(mktemp -d)
trap 'rm -rf "$T" "$CP"' EXIT
R=$T/root
fail=0
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
sc() { set +e; out=$(HMGAPI_ROOT=$R java -cp "$CP" hmgapi.engine.SystemCheck "$@" 2>&1); rc=$?; set -e; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
mod() {   # mod <id> <system json> [parts json]
    mkdir -p $R/data/hmgapi/mods/$1
    printf '{"id": "%s", "version": "1.0.0", "system": %s%s}\n' "$1" "$2" "${3:+, \"parts\": $3}" > $R/data/hmgapi/mods/$1/mod.json
    printf '{"changes": []}\n' > $R/data/hmgapi/mods/$1/patches.json
}
ok() { sc $R/data/hmgapi/mods/$1; has "$2" "ok: $1" "$out"; rm -r $R/data/hmgapi/mods/$1; }
no() { sc $R/data/hmgapi/mods/$1; has "$2" "$3" "$out"; [ $rc = 1 ] || { echo "FAIL  $2: exit $rc"; fail=1; }; rm -r $R/data/hmgapi/mods/$1; }

mkdir -p $R/system/etc/permissions $R/sys/bus/sdio/devices/mmc1:0001:1 $R/data/hmgapi/mods $R/data/hmgapi/settings
printf '%s\n' ro.build.product=CVEV.USA.0000.V015.012.260507 ro.board.platform=tcc803x ro.build.version.release=4.4.2 \
    ro.mobis.onebindir=cvev_us ro.product.opt.stepofspec=105 > $R/props.txt
printf '<permissions>\n    <feature name="com.hkmc.hardware.ev" />\n</permissions>\n' > $R/system/etc/permissions/ev.xml
printf '<permissions><feature name="com.hkmc.software.wifi.module"/></permissions>\n' > $R/system/etc/permissions/wifi.xml

sc
has "facts from the props" "model CVEV, region USA, firmware 260507, make kia, name EV6, platform tcc803x" "$out"
has "Wi-Fi chip seen on SDIO" "Wi-Fi chip found" "$out"

mod a '{"make": ["kia", "hyundai"], "region": ["USA", "CAN"], "firmware": ">=260206"}'; ok a "lists and a range"
mod a '{"make": "all", "model": "*", "region": ["all"]}'; ok a "all and *"
mod a '{"model": ["!NEEV"]}'; ok a "exclusion of another model"
mod a '{"product": ["NEEV.CAN.", "cvev.usa."]}'; ok a "product prefixes, any case"
mod a '{"features": ["com.hkmc.hardware.ev", "hmgapi.wifi", "!com.hkmc.hardware.ice"]}'; ok a "features present and absent"
mod a '{"props": {"ro.product.opt.stepofspec": ">=100"}, "android": "4.4.2", "platform": ["tcc803x"]}'; ok a "props range, android, platform"
mod a '[{"model": "NEEV", "region": "CAN"}, {"model": "CVEV", "region": "USA", "firmware": "260507"}]'; ok a "any one of several setups"
mod a '{"region": ["CAN"]}'; no a "wrong region" "needs region CAN (this unit: USA)"
mod a '{"firmware": [">260507"]}'; no a "firmware too old" "needs firmware >260507"
mod a '{"model": ["!CVEV"]}'; no a "excluded model" "not for model CVEV"
mod a '{"features": ["!com.hkmc.hardware.ev"]}'; no a "excluded feature" "not for units with com.hkmc.hardware.ev"
mod a '{"year": ">=2023"}'; no a "unknown year says what to set" "set the car's model year in HMG-API's settings (car.year)"
mod a '[{"region": "CAN"}, {"make": "hyundai"}]'; no a "none of the setups" "matches none of its 2 supported setups"
mod a '{"colour": "red"}'; no a "unknown key refused" 'unknown system requirement "colour"'

printf '{"car": {"year": 2023}}\n' > $R/data/hmgapi/settings/hmgapi.json
mod a '{"year": ">=2022 <2025", "name": "EV6"}'; ok a "owner's year and the model table's name"
rmdir $R/sys/bus/sdio/devices/mmc1:0001:1
mod a '{"features": ["hmgapi.wifi"]}'; no a "no Wi-Fi chip" "no Wi-Fi chip found on this unit"

# the engine: refuses the mod, leaves out a part, override at the owner's risk
mod b '{"region": "KOR"}'
eng plan; has "engine refuses an unsupported mod" "b: needs region KOR" "$out"
printf '{"car": {"year": 2023}, "overrides": {"ignore_system": ["b"]}}\n' > $R/data/hmgapi/settings/hmgapi.json
eng plan; has "override goes on with a warning" "WARNING: b: needs region KOR" "$out"
rm -r $R/data/hmgapi/mods/b
mod c '"all"' '[{"id": "base"}, {"id": "wifi", "system": {"features": ["hmgapi.wifi"]}}]'
eng plan; has "a part the unit can't run is left out" "c: part wifi left out: needs hmgapi.wifi" "$out"

# On Korean NEEV 260902 the stock hotspot removal can leave even a present chip
# unpowered. Only this mod/firmware accepts unknown hardware; other cars keep the
# SDIO requirement, and the engine warns before its file changes.
printf 'ro.build.product=NEEV.KOR.0000.V039.001.260902\nro.board.platform=tcc803x\nro.mobis.onebindir=neev_kr\n' > $R/props.txt
sc mods/wireless-carplay-aa; has "Korean Ioniq 5 accepted with no SDIO device" "supports this unit" "$out"
printf 'ro.build.product=NEEV.KOR.0000.V039.001.260901\nro.board.platform=tcc803x\nro.mobis.onebindir=neev_kr\n' > $R/props.txt
sc mods/wireless-carplay-aa; has "other Korean firmware refused" "matches none" "$out"
exit $fail
