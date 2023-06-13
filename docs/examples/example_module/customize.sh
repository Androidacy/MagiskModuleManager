#!/bin/sh

#
# Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
#

if [ -n "$MMM_EXT_SUPPORT" ]; then
  ui_print "#!useExt"
  mmm_exec() {
    ui_print "$(echo "#!$@")"
  }
else
  mmm_exec() { true; }
  abort "! This module need to be executed in Androidacy Module Manager"
  exit 1
fi

ui_print "- Doing stuff"
ui_print "- Current state: LOADING"
mmm_exec showLoading
sleep 4
mmm_exec setLastLine "- Current state: LOADING AGAIN"
sleep 4
mmm_exec setLastLine "- Current state: LOADED"
mmm_exec hideLoading
ui_print "- Doing more stuff"
sleep 4
# You can even set youtube links as support links
# Note: Button only appear once install ended
mmm_exec setSupportLink "https://youtu.be/dQw4w9WgXcQ"
ui_print "- Modules installer can also set custom shortcut"
abort "! Check top right button to see where it goes"