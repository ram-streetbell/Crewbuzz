# CrewBuzz

CrewBuzz is an Android restaurant waitstaff paging terminal. This build is based on the preserved CrewBuzz Phase 1 source artifact and provides the core terminal UI with an in-memory data model.

## Features
- Staff login with demo PIN `1234`
- Dashboard with active calls and online devices
- Simulated table/service calls for testing
- Attend active calls
- Device registration and online/offline simulation
- Call history and response-time tracking
- Terminal settings and reset controls

## Build
Open the repository in Android Studio and run the `app` configuration, or run `gradle :app:assembleRelease` with Gradle 8.10.2 and JDK 17.

GitHub Actions builds the release APK automatically on pushes to `main` and publishes the APK as a GitHub Release.
