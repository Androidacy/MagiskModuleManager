#
# Copyright (c) 2019-2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
#

if [ -z "$AK3TMPFS" ]; then
  echo "AK3TMPFS is not defined? Are you running FoxMMM?"
  exit 1
fi

if [ ! -e "$AK3TMPFS" ]; then
  mkdir $AK3TMPFS
  chmod 755 $AK3TMPFS
fi

ZIPFILE=$3;

# Mount tmpfs early
mount -t tmpfs -o size=400M,noatime tmpfs $AK3TMPFS;
mount | grep -q " $AK3TMPFS " || exit 1;

unzip -p $Z tools*/busybox > $AK3TMPFS/busybox;
unzip -p $Z META-INF/com/google/android/update-binary > $AK3TMPFS/update-binary;
##

chmod 755 $AK3TMPFS/busybox;
$AK3TMPFS/busybox chmod 755 $AK3TMPFS/update-binary;
$AK3TMPFS/busybox chown root:root $AK3TMPFS/busybox $AK3TMPFS/update-binary;

# work around Android passing the app what is actually a non-absolute path
AK3TMPFS=$($AK3TMPFS/busybox readlink -f $AK3TMPFS);

# AK3 allows the zip to be flashed from anywhere so avoids any need to remount /
if $AK3TMPFS/busybox grep -q AnyKernel3 $AK3TMPFS/update-binary; then
  # work around more restrictive upstream SELinux policies for Magisk <19306
  magiskpolicy --live "allow kernel app_data_file file write" || true;
else
  echo "Module is not an AnyKernel3 module!"
  exit 1
fi;

# update-binary <RECOVERY_API_VERSION> <OUTFD> <ZIPFILE>
ASH_STANDALONE=1 AKHOME=$AK3TMPFS/anykernel $AK3TMPFS/busybox ash $AK3TMPFS/update-binary 3 1 "$Z";
RC=$?;

# Original script delete all generated files,
# But we just need to unmount as we store everything inside tmpfs
umount $AK3TMPFS;

return $RC;