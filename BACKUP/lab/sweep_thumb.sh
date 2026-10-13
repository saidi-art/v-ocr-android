export JAVA_HOME=$(ls -d ~/android/jdk-17*) ANDROID_HOME=$HOME/android/sdk FLRAW=/tmp/f2/f50.rgb FLFPS=50
cd /tmp/wt-c && FLDUMP=1 FLTHUMB=1 ~/android/gradle-8.13/bin/gradle --no-daemon -q -Dorg.gradle.jvmargs=-Xmx700m cleanTestDebugUnitTest testDebugUnitTest --tests '*FlingLabTest*' -i > /tmp/sw_thumb.log 2>&1
echo done > /tmp/sw_thumb.done
