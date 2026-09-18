# AlarmPair v3.1 repaired

Two-person private synchronized alarm prototype.

## Build
GitHub Actions installs Gradle 8.9 and runs `gradle assembleDebug`; a local Gradle installation is not required.

## Server
Render WebSocket endpoint: `wss://alarmpair-server.onrender.com`. The Node server exposes an HTTP health endpoint and uses `process.env.PORT`.

## Important reliability notes
- Android exact alarms are scheduled locally from the server-authoritative timestamp.
- Alarm ringing is handled by a foreground service plus a high-priority full-screen alarm notification, so it does not depend on MainActivity staying alive.
- STOP from the alarm screen is sent by the foreground service using the saved device token.
- The server owns the five-minute wait and the repeated 30-second wake-check loop.
- Pair/session state is in memory on the Render process; a server restart loses active pairs/sessions. Production persistence should be added before relying on this for unattended overnight use.
- Android OEM battery settings and notification/full-screen-intent policy can still affect behavior on particular phones.
