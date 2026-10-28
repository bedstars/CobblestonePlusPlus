# cobblestone++

Client-side Fabric QoL mod for **play.cobblestone.gg** (Minecraft 26.1.2, 26.2 and 26.3). Nothing is ever sent to the server.

## Feature 1 - Backpack HUD
- Reads the action bar (`+2.90k Crying Obsidian (17.22M)`) so every block you break updates gained + total instantly.
- Open `/backpack` (any tab) once: exact totals and sell prices are read from the menu (coins for crops/fish, gems for mob drops).
- The hotbar Backpack item's tooltip is read every 2s, so totals stay synced without opening the menu.
- Saved to `config/cobblestoneplus/backpack.json`.

Commands: `/cpp backpack on|off|mode recent|all|secs <n>|rows <n>|value|reset|wipe|list`

## Feature 2 - Multiplier breakdown
- Open `/stats` (or any menu that has the **Boosters** item): the lore is read automatically and kept.
- Always-on panel showing every booster (Fortune Cookie, Rank, Crop Multiplier, Guild, Global, Gem, Pet, Armor, Treasury, Trinket...) with its x value, the separate XP value in cyan, a share bar of how much each contributes to the total, and the crop it was read for.
- Saved to `config/cobblestoneplus/boosters.json`.

- **Always up to date:** the mod asks the server for `/stats` itself (the menu is read and closed again in a blink) whenever armor goes on / off, the held item changes, a menu you used is closed (shop / upgrades), a purchase message shows up in chat, you switch crop, or at least every 90s. It only asks when no screen is open. Turn it off with `/cpp boosters auto`; `/cpp boosters refresh` or the MULTI tab updates it right now.
- The Backpack bundle tooltip is now re-read every second.

Commands: `/cpp boosters on|off|auto|every <secs>|refresh`

## Feature 3 - Crafting pins
- Open the crafting menus (e.g. Frost Castle Crafting) once: recipes and progress requirements are learned from their tooltips.
- Pin things as usual. The HUD matches the sidebar `PINNED [x/y]` block to a learned recipe and shows **every** step, not just the few the scoreboard fits.
- Each step is **green once you have enough**, red while missing, with `have/need`. Counts come live from the scoreboard, then your tracked backpack, then the last tooltip.
- Works for item recipes (`1x Cat Ears [III]`, `14.80M Froglight`) and kill/progress pins (`Creaking slain 400/400`).

Commands: `/cpp pins on|off|forget`

## Feature 4 - Treasures (per world)
- Read from the menus you open: **The Treasury** (every vault, seal state, active boosts) and each **Vault** menu (found relics, relic levels, and what the menu says about every still-unfound `?` relic). `/cpp treasure refresh` (or the REFRESH button in the menu) asks the server for `/treasures`, reads it and closes it again.
- Only real worlds are listed: world n = the n-th open vault in The Treasury; locked / unreleased places are dropped.
- The panel shows the world you are in in detail (it follows the active vault of your Treasury item, else the crop you farm; lock one with `/cpp treasure world <n>`): seal state, boosts, and a `MISSING` line per relic still to find plus `UPGRADE` lines for relics below max level (max mode). The other worlds are one line each.
- Open each world's vault once so it knows what is missing there.
- A treasure you find is read from the chat announcement (name + level + the stats it gives): it shows as a `NEW` line for 90s, updates that crop's treasure level, and re-reads the treasury and your multiplier.
- Two goals: `completion` (at least 1 of each relic) and `max` (every relic at 3/3).

Commands: `/cpp treasure on|off|mode completion|max|refresh|world <0-9>|others|forget`

## Feature 5 - Shiny block finder (echo + rush)
- Looks at the glowing floating blocks the server has sent you. Normal shinies (steady purple / pink) are ignored.
- **Echo** = the glow *pulses* (dark blue -> cyan -> near white and back). A rainbow cycle or a steady blue / cyan glow counts too.
- **Rush** = steady yellow glow **and the yellow rings around it** (display entities within ~6 blocks). A yellow glow without rings is treated as a normal block, so rush no longer blends with normals on yellow terrain.
- A compass strip at the top shows every echo / rush marker relative to where you are looking, with its distance; markers pinned to an edge with `<` / `>` mean turn that way. A line below gives the nearest of each kind, direction and height difference.
- Only blocks within the server's entity tracking range can be seen.

Commands: `/cpp shiny on|off|echo|rush|rings|range <n>|debug` (`rings` toggles the ring requirement; `debug` lists glowing entities near you with their colour, how much it moved, ring count and what else is around them, to tune detection).

## Feature 6 - Milestones per world
- Open `/milestones` (Farming and Mob, every page) once: stages, progress, idols and treasure level are read from the tooltips and remembered.
- Every crop / mob gets a global number (page x 7 + column); a world holds 8 of them: world 1 = wheat..pineapple / cow..horse, world 2 = froglight..quartz / frog..iron golem, world 3 = clay..verdant froglight / turtle..witch, world 4 = hay bale..honeycomb / llama..honey bear.
- The panel shows the world you are in (it follows the crop you farm, or lock a world in the menu): per crop / mob the finished stages `x/5`, a progress bar with `current/next target`, idols `I x/10` and treasure level `T x/3`. Finished ones are hidden so only what is left stays on screen.

Commands: `/cpp milestones on|off|world <0-9>|mobs|done|forget`

## Menu + HUD editor
- **Right Ctrl** (rebindable) or `/cpp menu` opens the settings: panel on/off, backpack / treasure / shiny options, text size, data resets. Everything is laid out on one canvas that scales down to fit small windows.
- **HUD editor** (button in the menu, `/cpp edit`, or bind a key): drag any panel anywhere (snaps to edges and centre), drag the green corner handle or use the mouse wheel / `-` `+` to scale it, `ON/OFF` to hide it, `R` to reset it.
- **Text shrinks with the window**: panels and the menu shrink automatically when the GUI area is smaller than the reference width (settings: *Shrink with window size*, *Full size at GUI width*), and *Text size* scales everything globally.
- Positions are saved as fractions of the screen, so your layout stays put when you resize the window.

## Build
`gradle build` (Java 25) or push to GitHub - the Actions workflow builds the jar.
