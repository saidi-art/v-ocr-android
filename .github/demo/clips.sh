#!/bin/bash
# Demo clips on the CI emulator. Host-side capture: "adb emu screenrecord" (the emulator films its own screen, no Android projection).
PKG=app.scrollrsi; A=$PWD/.github/demo; OUT=$PWD/clips; mkdir -p $OUT
ui() { python3 $A/ui.py "$@"; }
adb install -r vocr-release.apk
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
adb shell settings put global window_animation_scale 1
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard; adb shell input keyevent 82
adb shell pm list packages | grep -E "chrome|documentsui|viewer|gallery|photos" > $OUT/pkgs.txt
adb logcat -c
# ---- host recorder: the emulator console records its own screen to webm; chained in 170 s segments
rec_start() { NAME=$1; touch /tmp/rec_$NAME.on; ( i=0; while [ -f /tmp/rec_$NAME.on ]; do adb emu screenrecord start --time-limit 170 $OUT/${NAME}_seg$i.webm; for s in $(seq 1 172); do [ -f /tmp/rec_$NAME.on ] || break; sleep 1; done; adb emu screenrecord stop; sleep 2; i=$((i+1)); done ) > $OUT/rec_$NAME.log 2>&1 & }
rec_stop() { NAME=$1; rm -f /tmp/rec_$NAME.on; wait; sleep 3; ls $OUT/${NAME}_seg*.webm | sort -V | sed "s|^|file '|;s|\$|'|" > /tmp/l_$NAME.txt; ffmpeg -y -loglevel error -f concat -safe 0 -i /tmp/l_$NAME.txt -vf "scale=trunc(iw/2)*2:trunc(ih/2)*2" -c:v libx264 -pix_fmt yuv420p $OUT/clip-$NAME.mp4; ls -l $OUT/clip-$NAME.mp4; }
snap() { adb exec-out screencap -p > $OUT/$1.png; ui dump $OUT/$1.xml; }

# ================= clip b: media projection =================
adb shell am start -a android.settings.DEVICE_INFO_SETTINGS; sleep 4
rec_start b; sleep 6
adb shell am start -n $PKG/app.vocr.MainActivity; sleep 5; snap b1-home
ui tap '^Start$|Allow overlay' || true; sleep 4; snap b2-bubble
ui tappkg $PKG; sleep 3; snap b3-consent
ui tap 'Start now' ; sleep 4; snap b4-recording
adb shell cmd statusbar expand-notifications; sleep 3; snap b5-shade; adb exec-out screencap -p > $OUT/b5-shade-shot.png
sleep 2; adb shell cmd statusbar collapse; sleep 6
ui tappkg $PKG; sleep 4; snap b6-stopped
adb shell cmd statusbar expand-notifications; sleep 3; snap b7-shade-after; adb shell cmd statusbar collapse; sleep 3
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
ui tap '^Add( \(\d+\))?$|Select|Done' || true; sleep 5; snap c4-queued
adb shell cmd statusbar expand-notifications; sleep 3; snap c5-progress-shade; adb shell cmd statusbar collapse; sleep 2
DONE=0
for i in $(seq 1 90); do
  if adb shell dumpsys notification --noredact | grep -q "Text ready"; then DONE=1; break; fi
  sleep 10
done
echo "done=$DONE after $((i*10)) s" > $OUT/wait-c.txt
sleep 3; snap c6-done
adb shell cmd statusbar expand-notifications; sleep 3; snap c7-ready-shade
ui tap 'Text ready' ; sleep 5; snap c8-opened
sleep 4; adb exec-out screencap -p > $OUT/c9-final.png
rec_stop c
adb logcat -d | grep -E "vocr|AndroidRuntime|FATAL" | tail -200 > $OUT/log-c.txt
adb shell ls -la /sdcard/Download/ScrollRSI/ > $OUT/saved-files.txt 2>&1
