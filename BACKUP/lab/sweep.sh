export JAVA_HOME=$(ls -d ~/android/jdk-17*) ANDROID_HOME=$HOME/android/sdk
export FLRAW=/tmp/f2/f50.rgb FLFPS=50
run() { name=$1; shift
  cd /tmp/wt-c && env "$@" ~/android/gradle-8.13/bin/gradle --no-daemon -q -Dorg.gradle.jvmargs=-Xmx700m cleanTestDebugUnitTest testDebugUnitTest --tests '*FlingLabTest*' -i > /tmp/sw_$name.log 2>&1
  grep LABTXT /tmp/sw_$name.log | sed 's/.*LABTXT //' > /tmp/sw_$name.txt
  echo done > /tmp/sw_$name.done; }
run base_dump FLDUMP=1
run rech32_dump FLDUMP=1 FLRECH=32
run trim FLREC=/tmp/wt-c/rec_trim.onnx FLKEYS=/tmp/wt-c/keys_trim.txt
run dynmm FLREC=/tmp/wt-c/rec_dynmm.onnx
run en FLREC=/tmp/wt-c/rec_en.onnx FLKEYS=/tmp/wt-c/keys_en.txt
