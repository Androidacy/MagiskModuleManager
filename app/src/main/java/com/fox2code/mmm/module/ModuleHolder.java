package com.fox2code.mmm.module;

import android.content.Context;
import android.content.pm.PackageManager;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import com.fox2code.mmm.MainApplication;
import com.fox2code.mmm.NotificationType;
import com.fox2code.mmm.R;
import com.fox2code.mmm.XHooks;
import com.fox2code.mmm.manager.LocalModuleInfo;
import com.fox2code.mmm.manager.ModuleInfo;
import com.fox2code.mmm.repo.RepoModule;
import com.fox2code.mmm.utils.IntentHelper;
import com.fox2code.mmm.utils.io.PropUtils;
import com.fox2code.mmm.utils.io.net.Http;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import timber.log.Timber;

public final class ModuleHolder implements Comparable<ModuleHolder> {

    public final String moduleId;
    public final NotificationType notificationType;
    public final Type separator;
    public int footerPx;
    public View.OnClickListener onClickListener;

    public LocalModuleInfo moduleInfo;
    public RepoModule repoModule;
    public int filterLevel;

    public ModuleHolder(String moduleId) {
        this.moduleId = Objects.requireNonNull(moduleId);
        this.notificationType = null;
        this.separator = null;
        this.footerPx = -1;
    }

    public ModuleHolder(NotificationType notificationType) {
        this.moduleId = "";
        this.notificationType = Objects.requireNonNull(notificationType);
        this.separator = null;
        this.footerPx = -1;
    }

    public ModuleHolder(Type separator) {
        this.moduleId = "";
        this.notificationType = null;
        this.separator = separator;
        this.footerPx = -1;
    }

    @SuppressWarnings("unused")
    public ModuleHolder(int footerPx, boolean header) {
        this.moduleId = "";
        this.notificationType = null;
        this.separator = null;
        this.footerPx = footerPx;
        this.filterLevel = header ? 1 : 0;
    }

    public boolean isModuleHolder() {
        return this.notificationType == null && this.separator == null && this.footerPx == -1;
    }

    public ModuleInfo getMainModuleInfo() {
        return this.repoModule != null && (this.moduleInfo == null || this.moduleInfo.versionCode < this.repoModule.moduleInfo.versionCode) ? this.repoModule.moduleInfo : this.moduleInfo;
    }

    public String getUpdateZipUrl() {
        return this.moduleInfo == null || (this.repoModule != null && this.moduleInfo.updateVersionCode < this.repoModule.moduleInfo.versionCode) ? this.repoModule.zipUrl : this.moduleInfo.updateZipUrl;
    }

    public String getUpdateZipRepo() {
        return this.moduleInfo == null || (this.repoModule != null && this.moduleInfo.updateVersionCode < this.repoModule.moduleInfo.versionCode) ? this.repoModule.repoData.id : "update_json";
    }

    public String getUpdateZipChecksum() {
        return this.moduleInfo == null || (this.repoModule != null && this.moduleInfo.updateVersionCode < this.repoModule.moduleInfo.versionCode) ? this.repoModule.checksum : this.moduleInfo.updateChecksum;
    }

    public String getMainModuleName() {
        ModuleInfo moduleInfo = this.getMainModuleInfo();
        if (moduleInfo == null || moduleInfo.name == null)
            throw new Error("Error for " + this.getType().name() + " id " + this.moduleId);
        return moduleInfo.name;
    }

    public String getMainModuleNameLowercase() {
        return this.getMainModuleName().toLowerCase(Locale.ROOT);
    }

    public String getMainModuleConfig() {
        if (this.moduleInfo == null) return null;
        String config = this.moduleInfo.config;
        if (config == null && this.repoModule != null) {
            config = this.repoModule.moduleInfo.config;
        }
        return config;
    }

    public String getUpdateTimeText() {
        if (this.repoModule == null) return "";
        long timeStamp = this.repoModule.lastUpdated;
        return timeStamp <= 0 ? "" : MainApplication.formatTime(timeStamp);
    }

    public String getRepoName() {
        if (this.repoModule == null) return "";
        return this.repoModule.repoName;
    }

    public boolean hasFlag(int flag) {
        return this.moduleInfo != null && this.moduleInfo.hasFlag(flag);
    }

    public Type getType() {
        if (this.footerPx != -1) {
            Timber.i("Module %s is footer", this.moduleId);
            return Type.FOOTER;
        } else if (this.separator != null) {
            Timber.i("Module %s is separator", this.moduleId);
            return Type.SEPARATOR;
        } else if (this.notificationType != null) {
            Timber.i("Module %s is notification", this.moduleId);
            return Type.NOTIFICATION;
        } else if (this.moduleInfo == null) {
            return Type.INSTALLABLE;
        } else if (this.moduleInfo.versionCode < this.moduleInfo.updateVersionCode || (this.repoModule != null && this.moduleInfo.versionCode < this.repoModule.moduleInfo.versionCode)) {
            boolean ignoreUpdate = false;
            try {
                if (Objects.requireNonNull(MainApplication.getSharedPreferences("mmm").getStringSet("pref_background_update_check_excludes", new HashSet<>())).contains(moduleInfo.id))
                    ignoreUpdate = true;
            } catch (Exception ignored) {
            }
            // now, we just had to make it more fucking complicated, didn't we?
            // we now have pref_background_update_check_excludes_version, which is a id:version stringset of versions the user may want to "skip"
            // oh, and because i hate myself, i made ^ at the beginning match that version and newer, and $ at the end match that version and older
            Set<String> stringSetT = MainApplication.getSharedPreferences("mmm").getStringSet("pref_background_update_check_excludes_version", new HashSet<>());
            String version = "";
            Timber.d(stringSetT.toString());
            // unfortunately, stringsett.contains() doesn't work for partial matches
            // so we have to iterate through the set
            for (String s : stringSetT) {
                if (s.startsWith(this.moduleInfo.id)) {
                    version = s;
                    Timber.d("igV: %s", version);
                    break;
                }
            }
            String remoteVersionCode = String.valueOf(moduleInfo.updateVersionCode);
            if (repoModule != null) {
                remoteVersionCode = String.valueOf(repoModule.moduleInfo.versionCode);
            }
            if (!version.isEmpty()) {
                // now, coerce everything into an int
                int remoteVersionCodeInt = Integer.parseInt(remoteVersionCode);
                int wantsVersion = Integer.parseInt(version.split(":")[1].replaceAll("[^0-9]", ""));
                // now find out if user wants up to and including this version, or this version and newer
                Timber.d("igV start with");
                version = version.split(":")[1];
                // this version and newer
                if (version.startsWith("^")) {
                    Timber.d("igV: newer");
                    // the wantsversion and newer
                    if (remoteVersionCodeInt >= wantsVersion) {
                        Timber.d("igV: skipping");
                        // if it is, we skip it
                        ignoreUpdate = true;
                    }
                } else if (version.endsWith("$")) {
                    Timber.d("igV: older");
                    // this wantsversion and older
                    if (remoteVersionCodeInt <= wantsVersion) {
                        Timber.d("igV: skipping");
                        // if it is, we skip it
                        ignoreUpdate = true;
                    }
                } else if (wantsVersion == remoteVersionCodeInt) {
                    Timber.d("igV: equal");
                    // if it is, we skip it
                    ignoreUpdate = true;
                }

            }
            if (ignoreUpdate) {
                Timber.d("Module %s has update, but is ignored", this.moduleId);
                return Type.INSTALLABLE;
            } else {
                MainApplication.getINSTANCE().modulesHaveUpdates = true;
                if (!MainApplication.getINSTANCE().updateModules.contains(this.moduleId)) {
                    MainApplication.getINSTANCE().updateModules.add(this.moduleId);
                    MainApplication.getINSTANCE().updateModuleCount++;
                }
                Timber.d("modulesHaveUpdates = %s, updateModuleCount = %s", MainApplication.getINSTANCE().modulesHaveUpdates, MainApplication.getINSTANCE().updateModuleCount);
                Timber.d("Module %s has update", this.moduleId);
                return Type.UPDATABLE;
            }
        } else {
            return Type.INSTALLED;
        }
    }

    public Type getCompareType(Type type) {
        if (this.separator != null) {
            return this.separator;
        } else if (this.notificationType != null && this.notificationType.special) {
            return Type.SPECIAL_NOTIFICATIONS;
        } else {
            return type;
        }
    }

    public boolean shouldRemove() {
        if (this.repoModule != null && this.moduleInfo != null && !hasUpdate()) {
            return true;
        }
        return this.notificationType != null ? this.notificationType.shouldRemove() : this.footerPx == -1 && this.moduleInfo == null && (this.repoModule == null || !this.repoModule.repoData.isEnabled() || (PropUtils.isLowQualityModule(this.repoModule.moduleInfo) && !MainApplication.isDisableLowQualityModuleFilter()));
    }

    public void getButtons(Context context, List<ActionButtonType> buttonTypeList, boolean showcaseMode) {
        if (!this.isModuleHolder()) return;
        LocalModuleInfo localModuleInfo = this.moduleInfo;
        // Add warning button if module id begins with a dot - this is a hidden module which could indicate malware
        if (this.moduleId.startsWith(".") || !this.moduleId.matches("^[a-zA-Z][a-zA-Z0-9._-]+$")) {
            buttonTypeList.add(ActionButtonType.WARNING);
        }
        if (localModuleInfo != null && !showcaseMode) {
            buttonTypeList.add(ActionButtonType.UNINSTALL);
        }
        if (this.repoModule != null && this.repoModule.notesUrl != null) {
            buttonTypeList.add(ActionButtonType.INFO);
        }
        if ((this.repoModule != null || (localModuleInfo != null && localModuleInfo.updateZipUrl != null))) {
            buttonTypeList.add(ActionButtonType.UPDATE_INSTALL);
        }
        String config = this.getMainModuleConfig();
        if (config != null) {
            if (config.startsWith("https://www.androidacy.com/") && Http.hasWebView()) {
                buttonTypeList.add(ActionButtonType.CONFIG);
            } else {
                String pkg = IntentHelper.getPackageOfConfig(config);
                try {
                    XHooks.checkConfigTargetExists(context, pkg, config);
                    buttonTypeList.add(ActionButtonType.CONFIG);
                } catch (PackageManager.NameNotFoundException e) {
                    Timber.w("Config package \"" + pkg + "\" missing for module \"" + this.moduleId + "\"");
                }
            }
        }
        ModuleInfo moduleInfo = this.getMainModuleInfo();
        if (moduleInfo == null) { // Avoid concurrency NPE
            if (localModuleInfo == null) return;
            moduleInfo = localModuleInfo;
        }
        if (moduleInfo.support != null) {
            buttonTypeList.add(ActionButtonType.SUPPORT);
        }
        if (moduleInfo.donate != null) {
            buttonTypeList.add(ActionButtonType.DONATE);
        }
        if (moduleInfo.safe) {
            buttonTypeList.add(ActionButtonType.SAFE);
        } else {
            Timber.d("Module %s is not safe", this.moduleId);
        }
    }

    public boolean hasUpdate() {
        return this.moduleInfo != null && this.repoModule != null && this.moduleInfo.versionCode < this.repoModule.moduleInfo.versionCode;
    }

    @Override
    public int compareTo(ModuleHolder o) {
        // Compare depend on type, also allow type spoofing
        Type selfTypeReal = this.getType();
        Type otherTypeReal = o.getType();
        Type selfType = this.getCompareType(selfTypeReal);
        Type otherType = o.getCompareType(otherTypeReal);
        int compare = selfType.compareTo(otherType);
        return compare != 0 ? compare : selfTypeReal == otherTypeReal ? selfTypeReal.compare(this, o) : selfTypeReal.compareTo(otherTypeReal);
    }

    @NonNull
    @Override
    public String toString() {
        return "ModuleHolder{" + "moduleId='" + moduleId + '\'' + ", notificationType=" + notificationType + ", separator=" + separator + ", footerPx=" + footerPx + '}';
    }

    public enum Type implements Comparator<ModuleHolder> {
        HEADER(R.string.loading, false, false), SEPARATOR(R.string.loading, false, false) {
            @Override
            @SuppressWarnings("ConstantConditions")
            public int compare(ModuleHolder o1, ModuleHolder o2) {
                return o1.separator.compareTo(o2.separator);
            }
        }, NOTIFICATION(R.string.loading, true, false) {
            @Override
            @SuppressWarnings("ConstantConditions")
            public int compare(ModuleHolder o1, ModuleHolder o2) {
                return o1.notificationType.compareTo(o2.notificationType);
            }
        }, UPDATABLE(R.string.updatable, true, true) {
            @Override
            public int compare(ModuleHolder o1, ModuleHolder o2) {
                int cmp = Integer.compare(o1.filterLevel, o2.filterLevel);
                if (cmp != 0) return cmp;
                long lastUpdated1 = o1.repoModule == null ? 0L : o1.repoModule.lastUpdated;
                long lastUpdated2 = o2.repoModule == null ? 0L : o2.repoModule.lastUpdated;
                cmp = Long.compare(lastUpdated2, lastUpdated1);
                if (cmp != 0) return cmp;
                return o1.getMainModuleName().compareTo(o2.getMainModuleName());
            }
        }, INSTALLED(R.string.installed, true, true) {
            // get stacktrace for debugging
            @Override
            public int compare(ModuleHolder o1, ModuleHolder o2) {
                int cmp = Integer.compare(o1.filterLevel, o2.filterLevel);
                if (cmp != 0) return cmp;
                return o1.getMainModuleNameLowercase().compareTo(o2.getMainModuleNameLowercase());
            }
        }, SPECIAL_NOTIFICATIONS(R.string.loading, true, false), INSTALLABLE(R.string.online_repo, true, true) {
            @Override
            public int compare(ModuleHolder o1, ModuleHolder o2) {
                int cmp = Integer.compare(o1.filterLevel, o2.filterLevel);
                if (cmp != 0) return cmp;
                long lastUpdated1 = o1.repoModule == null ? 0L : o1.repoModule.lastUpdated;
                long lastUpdated2 = o2.repoModule == null ? 0L : o2.repoModule.lastUpdated;
                cmp = Long.compare(lastUpdated2, lastUpdated1);
                if (cmp != 0) return cmp;
                return o1.getMainModuleName().compareTo(o2.getMainModuleName());
            }
        }, FOOTER(R.string.loading, false, false);

        @StringRes
        public final int title;
        public final boolean hasBackground;
        public final boolean moduleHolder;

        Type(@StringRes int title, boolean hasBackground, boolean moduleHolder) {
            this.title = title;
            this.hasBackground = hasBackground;
            this.moduleHolder = moduleHolder;
        }

        // Note: This method should only be called if both element have the same type
        @Override
        public int compare(ModuleHolder o1, ModuleHolder o2) {
            if (o1 == o2) {
                return 0;
            } else if (o1 == null) {
                return -1;
            } else if (o2 == null) {
                return 1;
            } else {
                return o1.moduleId.compareTo(o2.moduleId);
            }
        }
    }
}
