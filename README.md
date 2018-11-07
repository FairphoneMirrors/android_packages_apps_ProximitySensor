## Description

The calibration algorithm is documented in the [CalibrationActivity](app/src/main/java/com/fairphone/psensor/CalibrationActivity.java) file.

The style and versioning scheme follows the general Fairphone style.

## Building

ProximitySensorTools can be built using _Android Studio_ or directly with Gradle.

### Android Studio

In order to build in _Android Studio_, import the root of the Git repository
as a new project and use the features of the IDE to edit, build, run, and
debug _ProximitySensorTools_.


### Commandline

To build from commandline you will need the Android SDK and Gradle.
Follow the
[instructions in Android documentation.](https://developer.android.com/studio/build/building-cmdline)


## Signing for Release

By default both _debug_ and _release_ build variants are signed using default
keys included in the Android SDK. To sign the app for release, you need

* a keystore containing the release key and
* a configuration file describing how to access the key.

The release key can be put in a keystore using a
[script in `vendor/fairphone/tools/`](https://review.fairphone.software:29443/3494).

Point the build system to the keystore using a configuration file which includes
the path to the keystore, the alias of the key, and passwords for keystore and
key. The file `keystore.properties` needs to be at the root of the Git
repository and contain information like:

    storePassword=<pwd-of-keystore>
    keyPassword=<pwd-of-key-in-keystore>
    keyAlias=ProximitySensorToolsReleaseKey
    storeFile=../relative/path/to/proximitysensortools.keystore

You can verify that Gradle can find and open the key by checking the output of
`gradlew signingReport`. The sections for `release` and `releaseUnitTest`
variants should look similar to:

    Variant: release
    Config: release
    Store: /absolute/path/to/proximitysensortools.keystore
    Alias: ProximitySensorToolsReleaseKey
    MD5: 7D:F2:FC:AA:FD:75:F3:83:5C:ED:AE:63:B7:F2:AD:AB
    SHA1: 30:63:51:FB:98:33:47:1A:26:00:2D:58:C7:F1:1D:76:DE:F0:34:8E
    Valid until: Monday, November 24, 2042

If configuration is correct, `gradlew assembleRelease` can be used to create the
signed app at `./app/build/outputs/apk/release/app-release.apk`.
