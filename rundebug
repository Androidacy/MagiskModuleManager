#!/bin/sh
#
# Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
#

# File created to run debug when IDE is failing.
./gradlew app:assembleDefaultDebug
adb install -r -t -d ./app/build/outputs/apk/default/debug/app-default-debug.apk
if [ "$?" == "0" ]; then
  adb shell am start com.fox2code.mmm.debug/com.fox2code.mmm.MainActivity
fi
