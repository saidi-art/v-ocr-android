#!/bin/bash
# Demo clips on the CI emulator. Host-side capture: "adb emu screenrecord" (the emulator films its own screen, no Android projection).
PKG=app.scrollrsi; A=$PWD/.github/demo; OUT=$PWD/clips; mkdir -p $OUT
ui() { python3 $A/ui.py "$@"; }
adb install -r vocr-release.apk
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell settings put global window_animation_scale 1
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard; adb shell input keyevent 82
adb shell wm size 540x1170; adb shell wm density 240; sleep 3
adb shell pm list packages | grep -E "chrome|documentsui|viewer|gallery|photos" > $OUT/pkgs.txt
adb logcat -c
# ---- host recorder: the emulator console records its own screen to webm; chained in 170 s segments
rec_start() { NAME=$1; touch /tmp/rec_$NAME.on; ( j=0; while [ -f /tmp/rec_$NAME.on ]; do adb shell screenrecord --bit-rate 3000000 --time-limit 120 /sdcard/dev_${NAME}_$j.mp4; j=$((j+1)); done ) > $OUT/devrec_$NAME.log 2>&1 & ( i=0; while [ -f /tmp/rec_$NAME.on ]; do adb emu screenrecord start --time-limit 170 $OUT/${NAME}_seg$i.webm; for s in $(seq 1 172); do [ -f /tmp/rec_$NAME.on ] || break; sleep 1; done; adb emu screenrecord stop; sleep 2; i=$((i+1)); done ) > $OUT/rec_$NAME.log 2>&1 & }
rec_stop() { NAME=$1; rm -f /tmp/rec_$NAME.on; adb shell pkill -l SIGINT screenrecord; wait; sleep 3; for f in $(adb shell ls /sdcard/dev_${NAME}_*.mp4 | tr -d '\r'); do adb pull $f $OUT/$(basename $f); done; ls $OUT/${NAME}_seg*.webm 2>/dev/null | sort -V | sed "s|^|file '|;s|\$|'|" > /tmp/l_$NAME.txt; [ -s /tmp/l_$NAME.txt ] && ffmpeg -y -loglevel error -f concat -safe 0 -i /tmp/l_$NAME.txt -vf "scale=trunc(iw/2)*2:trunc(ih/2)*2" -c:v libx264 -pix_fmt yuv420p $OUT/clip-$NAME.mp4; ls -l $OUT/clip-$NAME.mp4; }

bubble_xy() { adb shell dumpsys window windows > $OUT/winlist_$1.txt 2>&1; python3 - "$OUT/winlist_$1.txt" <<'PY'
import re,sys
t=open(sys.argv[1]).read()
best=None
for blk in re.split(r'\n  Window #', t):
    if 'app.scrollrsi' not in blk.split('\n')[0]: continue
    if 'APPLICATION_OVERLAY' not in blk and 'type=2038' not in blk: continue
    m=re.search(r'frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]', blk) or re.search(r'mFrame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]', blk) or re.search(r'Rect\((\d+), (\d+) - (\d+), (\d+)\)', blk)
    if not m: continue
    l,tp,r,b=map(int,m.groups()); a=(r-l)*(b-tp)
    if a>0 and (best is None or a<best[0]): best=(a,(l+r)//2,(tp+b)//2)
print(f"{best[1]} {best[2]}" if best else "NONE")
PY
}
resumed() { adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | head -2; }
bubble_tap() { XY=$(bubble_xy $1); echo "bubble at: $XY" | tee -a $OUT/bubble.txt; if [ "$XY" = NONE ]; then XY="520 520"; fi; adb shell input tap $XY; }
snap() { adb exec-out screencap -p > $OUT/$1.png; ui dump $OUT/$1.xml; }

# ================= clip b: media projection =================
adb shell am start -a android.settings.DEVICE_INFO_SETTINGS; sleep 4
rec_start b; sleep 6
adb shell am start -n $PKG/app.vocr.MainActivity; sleep 5; snap b1-home
ui tap '^Start$|Allow overlay' || true; sleep 2; adb exec-out screencap -p > $OUT/b2a-bubble.png; sleep 4; snap b2-bubble
bubble_tap b1; sleep 3; resumed | tee -a $OUT/bubble.txt
if ! resumed | grep -qiE "MediaProjection|Consent"; then bubble_tap b2; sleep 3; resumed | tee -a $OUT/bubble.txt; fi
adb exec-out screencap -p > $OUT/b3a-consent.png
ui tap 'Start now' ; sleep 4; adb exec-out screencap -p > $OUT/b4a-recording.png; snap b4-recording
adb shell cmd statusbar expand-notifications; sleep 3; adb exec-out screencap -p > $OUT/b5a-shade.png; sleep 4
ui tap 'Stop and read' || true; sleep 4; adb exec-out screencap -p > $OUT/b6a-stopped.png
adb shell cmd statusbar collapse; sleep 3
adb shell cmd statusbar expand-notifications; sleep 3; adb exec-out screencap -p > $OUT/b7a-shade-after.png; adb shell cmd statusbar collapse; sleep 3
rec_stop b
adb logcat -d | grep -E "vocr|AndroidRuntime|FATAL|MediaProjection" | tail -150 > $OUT/log-b.txt

# ================= clip c: media processing =================
adb shell am force-stop $PKG; sleep 2
unzip -oq vocr-release.apk assets/demo.mp4 -d /tmp/ex
adb push /tmp/ex/assets/demo.mp4 /sdcard/Movies/ScrollDemo.mp4
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/ScrollDemo.mp4; sleep 4
adb logcat -c
rec_start c; sleep 3
adb shell am start -n $PKG/app.vocr.MainActivity; sleep 5; snap c1-home
ui tap 'Choose videos|Pick screen recordings'; sleep 5; snap c2-picker
ui tap 'ScrollDemo|Video' || ui tap 'thumbnail|icon_thumbnail'; sleep 2; snap c3-picked
ui tap '^Add \(\d+\)$|button_add' || true; sleep 5; snap c4-queued
adb shell cmd statusbar expand-notifications; sleep 3; snap c5-progress-shade; adb shell cmd statusbar collapse; sleep 2
DONE=0
for i in $(seq 1 90); do
  if adb shell dumpsys notification --noredact | grep -q "Text ready: "; then DONE=1; break; fi
  sleep 10
done
echo "done=$DONE after $((i*10)) s" > $OUT/wait-c.txt
sleep 3; snap c6-done
adb shell cmd statusbar expand-notifications; sleep 3; snap c7-ready-shade
ui tap 'Text ready: ' ; sleep 5; snap c8-opened
sleep 4; adb exec-out screencap -p > $OUT/c9-final.png
rec_stop c
adb logcat -d | grep -E "vocr|AndroidRuntime|FATAL" | tail -200 > $OUT/log-c.txt
adb shell ls -la /sdcard/Download/ScrollRSI/ > $OUT/saved-files.txt 2>&1
