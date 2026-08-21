# ScreenStream Unified

A single Android APK that can operate in two modes:

- **Viewer** — connects to a room and renders the sender's WebRTC screen.
- **Stream** — requests Android MediaProjection permission and publishes the device screen over WebRTC.

## Architecture

`Android Viewer <-> WebSocket signaling <-> Android Stream` for SDP/ICE negotiation, followed by a direct WebRTC media path. The signaling server never carries video.

The app accepts `screenstream://connect?server=wss://...&room=ABC123` links so a setup link can pre-fill a device.

## Security

- Internet signaling is expected to use `wss://`.
- Screen capture requires Android's system MediaProjection consent.
- No signaling credentials are embedded in the app.
- STUN is used initially. TURN is deliberately not required for the first deployment and can be added as a fallback later if restrictive NATs require it.

## Build

Android Studio can import the project directly. CI uses JDK 17 and Gradle 8.10.2 and publishes the debug APK as a GitHub Actions artifact.

## Signaling compatibility

The app uses the existing ScreenStream signaling protocol: `join`, `peer-joined`, `offer`, `answer`, `ice-candidate`, and `peer-left`. The signaling server only relays JSON between one `phone` and one `viewer` in a room.
