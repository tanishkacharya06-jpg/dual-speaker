# AI Camera

Android camera app using CameraX and Jetpack Compose.

## Build
GitHub Actions runs the build from `.github/workflows/build-apk.yml` whenever the `ai-camera-ci-upload` branch is updated. Download the APK from the workflow's Artifacts section.

## Requirements
- Android 10 (API 29) or newer
- Camera permission

The current APK build is a camera capture shell. Remote AI enhancement requires a reachable server endpoint and authentication settings, which are not configured in this source package.
