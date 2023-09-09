# Androidacy Module Manager: Developer Documentation

## Table of Contents

- [Introduction](#introduction)
- [Special Considerations](#special-considerations)
- [Custom Repository Format](#custom-repository-format)
- [Module Properties](#module-properties)
- [ANSI Styling](#ansi-styling)
- [Installer Commands](#installer-commands)
- [Developer Mode](#developer-mode)
- [Conclusion](#conclusion)

## Introduction

This document serves as a comprehensive guide for developers interested in integrating their modules with the Androidacy Module Manager (AMM). It is assumed that the reader is already familiar with the [official Magisk module developer guide](https://topjohnwu.github.io/Magisk/guides.html).

> **Note:** The official Magisk repository no longer accepts new modules. Developers are encouraged to submit their modules to the [Androidacy Repository](https://www.androidacy.com/magisk-modules-repository/).

## Special Considerations

### MitM and Certificate Pinning

AMM enforces certificate pinning for certain origins and will not allow connections to be established if the certificate is not valid. This is done to prevent MitM attacks and ensure that the application is communicating with the intended server. This behavior cannot be disabled. 

### App Hiding

The application does not support hiding features. The package names should either be `com.fox2code.mmm` or `com.androidacy.mmm`.

### Low-Quality Module Filter

The low-quality module filter is implemented at `com.fox2code.mmm.utils.io.PropUtils.isLowQualityModule`. This filter ensures that only modules meeting a minimum quality standard are displayed to the end-users.

## Custom Repository Format

This section outlines the JSON format for custom repositories.

```json
{
  "name": "Repo name",
  "website": "repo website",
  "support": "optional support url",
  "donate": "optional support url",
  "submitModule": "optional submit module URL",
  "last_update": 0,
  "modules": [
    {
      "id": "module id",
      "last_update": 0,
      "notes_url": "notes url",
      "prop_url": "module.prop url",
      "zip_url": "module.zip url"
    }
  ]
}
```

### Module Properties
In addition to the standard Magisk properties, AMM supports several optional properties to enhance module functionality and user experience.

```properties
# AMM supported properties
minApi=<int>
maxApi=<int>
minMagisk=<int>
needRamdisk=<boolean>
support=<url>
donate=<url>
config=<package>
changeBoot=<boolean>
mmtReborn=<boolean>
```

Note: All URLs must start with `https://`.

### ANSI Styling
AMM declares the ANSI_SUPPORT variable as true if ANSI is supported. The application utilizes the AndroidANSI library for this feature.

### Installer Commands
AMM provides a set of specialized commands to control the installer interface. These commands are prefixed with #!.

Note: A wrapper script example is provided for better understanding.

### Developer Mode
Developer mode in AMM unlocks features that are unstable, dangerous, or experimental. Developer mode can be enabled by tapping the version number in the settings menu 7 times. **We don't recommend enabling developer mode unless you know what you're doing. Bug reports will not be accepted with developer mode on.**

### Conclusion
The Androidacy Module Manager API offers a robust set of features designed to enhance the user experience during module installation. Developers are encouraged to leverage these features to create a unique installation experience.


> **Copyright © 2023 Androidacy. All rights reserved.**
> 