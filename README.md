# Block Highlighter (Fabric, Minecraft 26.2)

Right Shift opens the menu (rebindable in Controls).
- Left-click a row: toggle that highlight
- Right-click a row (or click its swatch): RGBA color sliders
- Bottom row: scan range (left/right click to change)
- Header: collapse/expand

Build: install JDK 25, then `./gradlew build` -> build/libs/blockhighlighter-1.0.0.jar
(Generate the Gradle wrapper first if missing: `gradle wrapper --gradle-version 9.5.1`)
Drop the jar plus Fabric API (26.2) into your mods folder.
