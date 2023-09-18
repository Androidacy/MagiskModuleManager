/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        google()
        mavenCentral()
        maven {
            setUrl("https://jitpack.io")
        }
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.1.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.10")
        classpath("com.mikepenz.aboutlibraries.plugin:aboutlibraries-plugin:10.8.3")
    }
}

tasks.register("clean", Delete::class) {
    delete(layout.buildDirectory)
}

afterEvaluate {
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = JavaVersion.VERSION_17.toString()
        targetCompatibility = JavaVersion.VERSION_17.toString()
    }
}
