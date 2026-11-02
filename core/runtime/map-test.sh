#!/bin/sh
# Off-car test of the hook map gate (hmgapi.engine.CarMap, through `engine map`
# and `engine plan`): which map a car gets, when its hooks run, and which points a
# partial map lacks. Mods apply on any car; without a good map, without their hooks.
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d); T=$(mktemp -d); trap 'rm -rf "$T" "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" core/runtime/java/hmgapi/engine/*.java 2>/dev/null
R=$T/root
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
M=$R/data/hmgapi/maps/kia/test/usa/260507
mkdir -p $M $R/system/app $R/data/hmgapi/state $R/data/hmgapi/mods $R/data/hmgapi/settings
printf 'ro.build.product=TEST.USA.0000.V015.012.260507\nro.board.platform=tcc803x\n' > $R/props.txt
echo stock-app > $R/system/app/Test.odex
S=$(md5sum < $R/system/app/Test.odex | cut -c1-32)
mkdir -p $R/system/framework; echo stock-fw > $R/system/framework/framework.odex
F=$(md5sum < $R/system/framework/framework.odex | cut -c1-32)
car() {   # car <status> [extra car.json fields]
    printf '{"make": "kia", "model": "test", "name": "Test", "region": "usa", "firmware": "260507", "products": ["TEST.USA."], "status": "%s", "files": {"/system/app/Test.odex": "%s", "/system/framework/framework.odex": "%s"}, "apps": ["test", "framework"], "app_files": {"test": {"package": "x", "files": ["/system/app/Test.odex"]}, "framework": {"package": null, "files": ["/system/framework/framework.odex"]}}%s}\n' "$1" "$S" "$F" "$2" > $M/car.json
}
printf '{"test.a": {"class": "x.A"}, "test.a.go": {"method": "b", "params": [], "ret": "void", "static": false}}\n' > $M/test.json
printf '{"framework.x": {"class": "android.X"}}\n' > $M/framework.json
printf 'test.a\ntest.a.go\nframework.x\n' > $R/data/hmgapi/maps/points.txt

eng map; has "no map for another car" "no hook map for this car" "$out"
mkdir -p $R/data/hmgapi/mods/hooker
printf '{"id": "hooker", "version": "1.0.0", "hooks": {"jar": "h.jar", "entry": "h.H", "points": {"test.a.go": "observe"}}}\n' > $R/data/hmgapi/mods/hooker/mod.json
eng plan; check "no map: plan does not fail with a hook mod" 0 $rc
has "no map: hook mod stays off with a reason" "hooks    hooker won't load: hook point test.a.go isn't mapped on this car" "$out"
rm -r $R/data/hmgapi/mods/hooker
car verified
eng map; has "map found by product prefix and firmware" "hook map: verified for Test (usa 260507), hooks on" "$out"
printf 'ro.build.product=TEST.USA.0000.V015.012.260206\n' > $R/props.txt
eng map; has "other firmware: no map" "no hook map for this car" "$out"
printf 'ro.build.product=TEST.USA.0000.V015.012.260507\nro.board.platform=tcc803x\n' > $R/props.txt

printf 'test.a\ntest.a.go\nframework.x\ntest.a.new_point\n' > $R/data/hmgapi/maps/points.txt
eng map; has "a point newer than the map: hooks still on" "hooks on, 1 point(s) not mapped" "$out"
printf 'test.a\ntest.a.go\nframework.x\n' > $R/data/hmgapi/maps/points.txt
car verified ', "missing": {"test.a.later": "0 methods match in x.A"}'
eng map; has "a point the mapper didn't find: hooks still on" "hooks on, 1 point(s) not mapped" "$out"
car verified

echo changed >> $R/system/app/Test.odex
eng map; has "different app build: only its points go" "hooks on, 2 point(s) not mapped" "$out"
mkdir -p $R/data/hmgapi/mods/hooker
printf '{"id": "hooker", "name": "H", "version": "1.0.0", "hmgapi": ">=1.0.0", "hooks": {"jar": "h.jar", "entry": "h.H", "points": {"test.a.go": "observe"}}}\n' > $R/data/hmgapi/mods/hooker/mod.json
printf '{"enabled": {"hooker": true}}\n' > $R/data/hmgapi/settings/hmgapi.json
eng plan; has "a mod hooking an app that changed isn't loaded, and says why" "hook point test.a.go isn't mapped on this car (/system/app/Test.odex is" "$out"
rm -r $R/data/hmgapi/mods/hooker $R/data/hmgapi/settings/hmgapi.json
mkdir -p $R/data/hmgapi/stock/system/app; echo stock-app > $R/data/hmgapi/stock/system/app/Test.odex
eng map; has "a mod's change to the app: checked against HMG-API's stock backup" "hooks on" "$out"
rm -r $R/data/hmgapi/stock; echo stock-app > $R/system/app/Test.odex
echo changed >> $R/system/framework/framework.odex
eng map; has "different framework build: hooks off" "hook map: mismatch" "$out"
echo stock-fw > $R/system/framework/framework.odex

car mapped
eng map; has "mapped, not checked: off" "isn't checked on a car yet" "$out"
eng plan; check "mods still apply then" 0 $rc
has "without their hooks, and says why" "hooks are off on this car (the map for Test (usa 260507) isn't checked" "$out"
eng restore; check "restore still works" 0 $rc
printf '{"testing_maps": true}\n' > $R/data/hmgapi/settings/hmgapi.json
eng map; has "mapped + testing mode: on" "running because hmgapi.json testing_maps is on" "$out"
rm $R/data/hmgapi/settings/hmgapi.json
printf '{"folder": "kia/test/usa/260507", "files": {"/system/app/Test.odex": "%s", "/system/framework/framework.odex": "%s"}, "ok": true}\n' "$S" "$F" > $R/data/hmgapi/state/map-check.json
eng map; has "mapped + checked on this car: on" "map checked on this car" "$out"
eng plan; check "engine applies mods then" 0 $rc
printf '{"folder": "kia/test/usa/260507", "files": {"/system/app/Test.odex": "%s", "/system/framework/framework.odex": "%s"}, "ok": false}\n' "$S" "$F" > $R/data/hmgapi/state/map-check.json
eng map; has "a failed check doesn't count" "hooks off" "$out"
exit $fail
