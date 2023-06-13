#!/sbin/sh
#
# Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
#

# This script is only used to test debug builds

umask 022

API=$(getprop ro.build.version.sdk)
OUTFD=$2
ZIPFILE=$3
MODPATH="${ZIPFILE%/*}"

ui_print() { echo "$1"; }
abort() {
  ui_print "$1"
  [ -f $MODPATH/customize.sh ] && rm -f $MODPATH/customize.sh
  exit 1
}

ui_print "! Using rootless installer test script"

unzip -o "$ZIPFILE" customize.sh -d $MODPATH >&2

[ -f $MODPATH/customize.sh ] && . $MODPATH/customize.sh

ui_print "- Done"
