#!/usr/bin/env bash
# Template sweep: presents every template of the library (<spec>/fixtures/templates) in the sample app on a running
# emulator or device and checks that the app is alive, that nothing crashed, and that the screen is not blank. Screenshots go to
# $OUT (default build/sweep/<label>), one PNG per template, for a look afterwards.
#
#   ./gradlew :example:installDebug
#   ./sweep-templates.sh                      # all templates, light mode, normal text
#   ./sweep-templates.sh dark                 # the system in dark mode
#   ./sweep-templates.sh large                # the largest system font size (font_scale 2.0)
#   TEMPLATES=calm-money,streak ./sweep-templates.sh
#   GROUP=components ./sweep-templates.sh    # the small carousel / tabs / video / multiple-choice screens (fixtures/components), not the templates
# The settings it changes (night mode, font scale) are put back at the end.
set -u
cd "$(dirname "$0")"
MODE="${1:-light}"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[ -x "$ADB" ] || ADB=adb
OUT="${OUT:-build/sweep/$MODE}"
[ "${GROUP:-templates}" = templates ] || OUT="$OUT-${GROUP}"
mkdir -p "$OUT"
GROUP="${GROUP:-templates}"
SPEC="${MINIWALL_SPEC_DIR:-.spec}"   # the same folder the build bundled (-PspecDir / MINIWALL_SPEC_DIR)
NAMES=${TEMPLATES:-$(ls "$SPEC/fixtures/$GROUP" | sed 's/\.json$//' | tr '\n' ',')}

case "$MODE" in
  dark) "$ADB" shell cmd uimode night yes >/dev/null ;;
  large) "$ADB" shell settings put system font_scale 2.0 ;;
  light) "$ADB" shell cmd uimode night no >/dev/null ;;
esac
trap '"$ADB" shell cmd uimode night no >/dev/null; "$ADB" shell settings put system font_scale 1.0' EXIT

fail=0
for name in ${NAMES//,/ }; do
  "$ADB" shell am force-stop com.miniwall.example
  "$ADB" logcat -c
  # -W waits until the app's first frame is drawn (the launch splash is gone); the template appears about a second after that.
  "$ADB" shell am start -W -n com.miniwall.example/.MainActivity --es tpl "$name" >/dev/null
  sleep 4
  "$ADB" exec-out screencap -p > "$OUT/$name.png"
  problems=""
  [ -n "$("$ADB" shell pidof com.miniwall.example | tr -d '\r')" ] || problems="app not running (crashed?)"
  if "$ADB" logcat -d -b crash 2>/dev/null | grep -q "com.miniwall"; then problems="$problems; crash in the log"; fi
  # a flat screen: fewer than 6 distinct colors in a coarse sample
  if ! python3 -I - "$OUT/$name.png" <<'PY'
import sys
from PIL import Image  # pip install pillow
im = Image.open(sys.argv[1]).convert('RGB').resize((48, 96))
seen = {(r >> 4, g >> 4, b >> 4) for r, g, b in im.getdata()}
sys.exit(1 if len(seen) < 6 else 0)
PY
 then problems="$problems; the screen is blank"; fi
  if [ -n "$problems" ]; then echo "FAIL $name: $problems"; fail=$((fail+1)); else echo "ok   $name"; fi
done
echo "sweep ($MODE): $fail failed"
[ "$fail" -eq 0 ]
