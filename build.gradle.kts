/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        mavenCentral()
        google()
        maven {
            setUrl("https://jitpack.io")
        }
        gradlePluginPortal()
    }
    extra.apply {
        set("sentryConfigFile", rootProject.file("sentry.properties"))
        set("hasSentryConfig", false)
        set("sentryVersion", "6.18.1")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.0.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.21")
        classpath("com.mikepenz.aboutlibraries.plugin:aboutlibraries-plugin:10.6.2")

        // NOTE: Do not place your application dependencies here; they belong
        // in the individual module build.gradle files
        classpath("io.realm:realm-gradle-plugin:10.16.0")
        classpath("io.sentry:sentry-android-gradle-plugin:3.7.0")
        classpath("org.gradle.android.cache-fix:org.gradle.android.cache-fix.gradle.plugin:2.7.1")
    }
}

subprojects {
    plugins.withType<com.android.build.gradle.api.AndroidBasePlugin> {
        apply(plugin = "org.gradle.android.cache-fix")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}

afterEvaluate {
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = JavaVersion.VERSION_17.toString()
        targetCompatibility = JavaVersion.VERSION_17.toString()
    }
}

