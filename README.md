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

Commands: `/cpp boosters on|off`

## Feature 3 - Crafting pins
- Open the crafting menus (e.g. Frost Castle Crafting) once: recipes and progress requirements are learned from their tooltips.
- Pin things as usual. The HUD matches the sidebar `PINNED [x/y]` block to a learned recipe and shows **every** step, not just the few the scoreboard fits.
- Each step is **green once you have enough**, red while missing, with `have/need`. Counts come live from the scoreboard, then your tracked backpack, then the last tooltip.
- Works for item recipes (`1x Cat Ears [III]`, `14.80M Froglight`) and kill/progress pins (`Creaking slain 400/400`).

Commands: `/cpp pins on|off|forget`

## Feature 4 - Treasures
- Open **The Treasury** once: every vault's tooltip (relics x/8, bonuses, seal forged / relics left, locked vaults) and the Treasury summary (total relics, seals, active boosts for your zone) are read automatically. Opening a vault's own menu additionally lets the mod read relic levels for max mode.
- Two goals: `completion` (at least 1 of each relic) and `max` (every relic at max level, 3/3). In max mode a `*` after a vault's count means the level could not be read yet, so it falls back to found/total.
- Incomplete vaults sort first; finished ones turn green; a small gold square marks a forged seal.

Commands: `/cpp treasure on|off|mode completion|max|forget`

## Feature 5 - Shiny block finder (echo + rush)
- Looks at the glowing floating blocks the server has sent you. **Echo** = outline colour cycles through the rainbow, **Rush** = steady yellow / gold outline. Normal shinies are ignored.
- A compass strip at the top shows every echo / rush marker relative to where you are looking, with its distance; markers pinned to an edge with `<` / `>` mean turn that way. A line below gives the nearest of each kind, direction and height difference.
- Only blocks within the server's entity tracking range can be seen.

Commands: `/cpp shiny on|off|echo|rush|range <n>|debug` (`debug` lists glowing entities near you with their type and outline colour, to tune detection).

## Menu + HUD editor
- **Right Ctrl** (rebindable) or `/cpp menu` opens the settings: panel on/off, backpack / treasure / shiny options, text size, data resets. Everything is laid out on one canvas that scales down to fit small windows.
- **HUD editor** (button in the menu, `/cpp edit`, or bind a key): drag any panel anywhere (snaps to edges and centre), drag the green corner handle or use the mouse wheel / `-` `+` to scale it, `ON/OFF` to hide it, `R` to reset it.
- **Text shrinks with the window**: panels and the menu shrink automatically when the GUI area is smaller than the reference width (settings: *Shrink with window size*, *Full size at GUI width*), and *Text size* scales everything globally.
- Positions are saved as fractions of the screen, so your layout stays put when you resize the window.

## Build
`gradle build` (Java 25) or push to GitHub - the Actions workflow builds the jar.
