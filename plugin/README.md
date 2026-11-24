# Sea Charting Route — RuneLite plugin

An in-game companion to the [web route planner](../index.html): overlays OSRS Sailing sea-charting
tasks and (eventually) the full planned route onto the world map and the sea around you.

**Overlays and reads only — never automation.** It draws markers and reads your charting progress;
it never sends input or steers the boat. That keeps it inside Jagex's 3rd-party client rules (the same
category as Shortest Path / WikiSync). Do not add anything that plays the game for you.

## Status / roadmap

- **v1 (now):** loads the 358-task dataset, drops a color-coded dot per task on the world map
  (tooltip = name · ocean › sea · type · level), and shows a "nearest task" info box. Proves the
  data + player-position + overlay pipeline.
- **v2:** port the A\* water router + route builder (`waterPath` / `buildRoute` from the web app) and
  draw the ordered route on the world map; weather tasks as round-trips, ducks one-way.
- **v3:** auto-read charting completion from varbits (same data WikiSync reads) so done tasks drop off
  live — no manual ticking.
- **v4:** Party sync — share the route + your boat's position over RuneLite's Party channel so a second
  account (e.g. your main) sees where the alt should sail next.

## Build & run (local test)

RuneLite plugin dev is smoothest on **JDK 11** (Temurin/Adoptium). You have JDK 25, which compiles fine
(we target release 11), but the RuneLite client can hit module-access errors on very new JDKs — if
`run` fails with `InaccessibleObjectException` / `--add-opens` noise, install JDK 11 and point the
project at it.

**Recommended: IntelliJ IDEA** (RuneLite's standard, bundles Gradle — no wrapper needed):
1. `File > Open` → select this `plugin/` folder. Let it import the Gradle project.
2. Open `src/test/java/com/seacharting/SeaChartingPluginTest.java`, click the green ▶ by `main`.
3. RuneLite launches in developer mode with the plugin on. Log in, open the world map — you'll see the
   task dots; the "Sea Charting" box shows the nearest task.

**Or command line** — needs the Gradle wrapper, which isn't committed (binary). Get it once with either:
- `gradle wrapper` (if you install Gradle), **or**
- copy `gradlew`, `gradlew.bat`, and the `gradle/` folder from a fresh
  `git clone https://github.com/runelite/example-plugin`.

Then: `./gradlew run`

## Data

`src/main/resources/charting/tasks.json` and `watermask.json` are generated from the web app's
`index.html` — the one source of truth. After changing the web app's data, regenerate:

```
python plugin/tools/extract-data.py
```

## Publishing (later)

For the [RuneLite Plugin Hub](https://github.com/runelite/plugin-hub): this needs its own public repo,
then a PR to `runelite/plugin-hub` adding a properties file pointing at that repo + commit hash.
Reviewers check it for 3rd-party compliance. Keep dependencies to zero beyond the RuneLite client to
speed review.
