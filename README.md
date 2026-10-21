# apple-apps

One repo for all my Apple apps. Each app lives in its own folder.

## Apps

| Folder | App |
| --- | --- |
| `ocean-live/ios` | Fisker Ocean Live for iPhone (SwiftUI). The Xcode project is made by XcodeGen from `project.yml` |
| `ocean-live/android` | Fisker Ocean Live for Android (Kotlin, Compose) |

## Android

Pushes that change `ocean-live/android` build a debug APK with GitHub Actions and attach it to a GitHub Release.

## iPhone builds without a Mac

Pushes that change `ocean-live/ios` run a GitHub Actions workflow on a Mac server. It builds the app and uploads it to TestFlight. It needs three repo secrets from App Store Connect

- `ASC_KEY_ID` is the key ID
- `ASC_ISSUER_ID` is the issuer ID
- `ASC_KEY_P8` is the full text of the downloaded .p8 key file

Change the version number in `ocean-live/ios/project.yml`. The build number is set by the workflow.
