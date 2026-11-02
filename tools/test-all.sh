#!/bin/sh
# Run every off-car test. Needs the build (./build.sh), qemu-arm binfmt, and for
# install/dev: an ARM busybox (UNIT_SH="<busybox> ash") and a stock app_process
# (STOCK_APP_PROCESS); without them that suite is skipped.
#   tools/test-all.sh [test script...]   only those (default: every one)
cd "$(dirname "$0")/.."
rc=0
[ -x out/lpatch-host ] || sh build.sh lpatch >/dev/null
ALL="core/hmgapid/test.sh core/zygote/test.sh tools/lpatch/test.sh core/runtime/engine-test.sh core/runtime/signing-test.sh core/runtime/system-test.sh core/runtime/safety-test.sh core/runtime/packages-test.sh core/runtime/map-test.sh core/runtime/usb-test.sh core/runtime/hooks-test.sh core/runtime/live-test.sh core/runtime/native-test.sh core/runtime/runtime-test.sh core/runtime/net-test.sh core/runtime/overlap-test.sh core/runtime/keys-test.sh core/runtime/commands-test.sh core/hookpoints/test.sh mods/adb/test.sh mods/wireless-carplay-aa/test.sh mods/wireless-carplay-aa/tools/kor/test.sh core/app/qr-test.sh mods/wireless-carplay-aa/tools/aaturns/test.sh mods/wireless-carplay-aa/tools/cpturns/test.sh install/common/test.sh install/common/install-test.sh install/usb/test.sh tools/mod-draft-test.sh tools/micom-decode-test.sh tools/check-scripts.sh"
[ $# -gt 0 ] && ALL="$*"   # tests named on the command line (tools/test-select.py picks them in CI)
for t in $ALL; do
    if sh "$t" > /tmp/hmgapi-test.$$ 2>&1; then echo "ok    $t ($(grep -c '^ok' /tmp/hmgapi-test.$$) checks)"
    else echo "FAIL  $t"; grep -E "^FAIL" /tmp/hmgapi-test.$$ || tail -15 /tmp/hmgapi-test.$$; rc=1; fi
done
if [ -n "$UNIT_SH" ] && [ -n "$STOCK_APP_PROCESS" ]; then
    if sh install/dev/test.sh > /tmp/hmgapi-test.$$ 2>&1; then echo "ok    install/dev/test.sh ($(grep -c '^ok' /tmp/hmgapi-test.$$) checks)"
    else echo "FAIL  install/dev/test.sh"; grep -E "^FAIL" /tmp/hmgapi-test.$$; rc=1; fi
else
    echo "skip  install/dev/test.sh (set UNIT_SH and STOCK_APP_PROCESS)"
fi
rm -f /tmp/hmgapi-test.$$
exit $rc
