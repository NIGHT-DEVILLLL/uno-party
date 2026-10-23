# UNO Party (Kotlin, Android)

Offline UNO for 2–10 phones. No server, no internet. Data lives on the phone.

## Set up (5 minutes)
1. Android Studio → New Project → **Empty Activity** (Compose), package `com.example.unoparty`, min SDK 24.
2. Replace `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts` and the files in
   `app/src/main/java/com/example/unoparty/` with the ones in this folder.
3. In the **root** `build.gradle.kts`, make sure these plugins exist (versions can be newer):
   ```kotlin
   plugins {
       id("com.android.application") version "8.7.3" apply false
       id("org.jetbrains.kotlin.android") version "2.0.21" apply false
       id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
       id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
   }
   ```
4. Install on 2+ real phones (Nearby Connections does not work on emulators). Needs Google Play services.
5. Phone A: login → **Create room**. Phone B/C: login → room appears under "Rooms nearby" → **Join**.
   Host taps **Start game**.

## How it works
- **Login**: name + 4-digit PIN, stored hashed in a Room (SQLite) DB on the phone. The last signed-in
  user is logged in automatically next time ("Switch user" to change).
- **Rooms**: the host advertises a room; other phones discover it automatically (Bluetooth / Wi-Fi Direct).
- **Game logic** runs only on the host phone (`UnoGame.kt`). Each player is sent only their own hand.

## UNO rules implemented
108-card deck (4 colours: 0–9, Skip, Reverse, Draw Two; 4 Wild; 4 Wild Draw Four), 7 cards each,
match colour / number / symbol, Skip, Reverse (acts as Skip with 2 players), Draw Two (draw and lose turn),
Wild with colour choice, Wild Draw Four (only legal with no card of current colour; the next player can
**challenge**: bluffer draws 4, wrong challenge = challenger draws 6), draw one card when you can't play
(play it or keep it), **UNO call** (forget and get caught = +2), draw pile reshuffles from discards,
round scoring (number = face value, action = 20, wild = 50), match ends at 500 points.

## Known simplifications
- If the first card is a Wild, the colour is chosen randomly (official: first player chooses).
- Winning with a Wild Draw Four skips the challenge step.
- If a player disconnects mid-game, the game ends.
