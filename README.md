# Samsung Frame Remote

Android remote for Samsung Frame TVs over local Wi-Fi.

## APK build

GitHub Actions builds the debug APK on every push to main. Open the Actions tab, choose **Build Android APK**, and download the `FrameRemote-debug-apk` artifact when the run succeeds. Extract `app-debug.apk` from the ZIP to install on Android.

The phone and TV must be on the same network. Enter the TV IP address and approve pairing on the TV. This is an experimental build; Art Mode, app shortcuts and power-on depend on TV model. Some Samsung TVs require secure WebSocket port 8002, which this prototype does not yet support.
