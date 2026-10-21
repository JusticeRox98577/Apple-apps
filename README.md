# apple-apps

One repo for all my Apple apps. Each app lives in its own folder.

## Apps

| Folder | App |
| --- | --- |
| `ocean-live/ios` | Fisker Ocean Live for iPhone (SwiftUI) |
| `ocean-live/android` | Fisker Ocean Live for Android (Kotlin, Compose) |

## Xcode Cloud

Make one Xcode Cloud workflow per app. Point each workflow at that app's `.xcodeproj` and set its start condition to only build when that app's folder changes. Turn on automatic build numbers.

## Android

Pushes that change `ocean-live/android` build a debug APK with GitHub Actions and attach it to a GitHub Release.
