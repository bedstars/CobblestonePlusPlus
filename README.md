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

## 0.8.0 changes
- **Worlds**: only the six real worlds exist now (Overworld, Frost Castle, Swamp, Honey Tree, Infernal Citadel, The Library). Charms, seals and locked places are never shown. Which world you are in comes from the crop you farm or the Treasury item, whichever changed last (`/cpp where`).
- **Milestones**: title shows the world name; one summary line per other world (`/cpp milestones others`).
- **Pins fixed**: the sidebar format `PINNED (Combat Armor) [0/2]` with `Spider (0/1,700)` lines is now understood, and "Unpin Progress" in the recipe tooltip also marks a pin. `/cpp pins debug` prints what is read.
- **Treasures**: your own finds ("You unearthed ...", "Duplicate ... Level 2/3", "On all Honey Tree crops: +7% Fortune") are read across the separate chat lines and update the vault instantly.
- **Multipliers**: armor / tool / hand changes correct the panel immediately (shown with `~` and "updating"), then `/stats` is read and re-read up to 2 more times if the total did not move. Open Gem Shop follows the crop's "Multiplier: x" live. Expired / activated boosters trigger a refresh.
- **Backpack**: sell / upgrade chat lines re-read the bundle every tick for 5s; bundle read every 0.25s.
- **Shiny**: echo keeps its history while the glow flashes, also counts flashing and animating blue blocks; rush needs stable concentric ring pieces (not near players, yellow when readable). `/cpp shiny debug` shows flashes, data changes and the ring breakdown.
- **Look**: `/cpp opacity <0-100>` (0 = only text, stripes hidden) and `/cpp menuopacity <0-100>`, also sliders in the menu (GENERAL).

## 0.9.0 changes
- **No more surprise menus.** The mod never opens `/stats` or `/treasures` by itself any more (`/cpp automenus` or the MULTI tab turns the old behaviour back on). Everything is read passively: chat, action bar, sidebar, item tooltips and any menu you open yourself. Multipliers still correct themselves instantly for armor / tool changes (shown as `estimate`) and are confirmed whenever you open `/stats` yourself.
- **Completion marker** (always-on panel): the overall percentage with a bar, read from the `Completion` item in `/stats` (when you open it, or when it is in your inventory) and from the `[27.5%]` the server puts before your name in your own chat lines. Global mode lists the three parts closest to done.
- **What to do next** (`/cpp plan`, button in the menu, or bind a key): every progression part (crops, milestones, treasures, gem shop, reforge, bosses, enchants, recipes, pets, AFK, charms, achievements ...) with its progress, least finished last. Click one to track only that part on screen (progress, what is left, where to do it); click it again or GLOBAL to track everything. `/cpp plan <part>` / `/cpp plan global` work too.
- Boss Index title (`Boss Index (2/6)`) updates the bosses count when you open it.

## 0.9.11
- **Milestones count from the sidebar `FARMED` line** (new default, `/cpp milestones source farmed`; existing configs are switched over once). FARMED grows by exactly what the server counted, so the amount is accurate; the crop is the block you are mining (most of the last 3s under the crosshair). A block that does not look like a crop by name (pattern / concrete block) is learned from the `+x Crop` action bar line and remembered in `milestones.json`. The status line shows `farmed | aim: Brown Concrete Powder -> Beehive | +120 Beehive`. `blocks` / `mine` are still there.
- **Completion** only reads the real `/stats` Completion item (it must list the known categories: Crops Discovered, Crop Milestones ...). The team "Completion" item (Created / Members / Upgrades) was being picked up instead; wrong data saved earlier is discarded on start.
- **Pins**: the PINNED header is matched case-insensitively and in small caps; recipes are also learned from any item that offers "Pin Progress" and lists steps; if the PINNED header cannot be read, sidebar step lines that belong to one learned recipe are shown as the pin.

## 0.9.10
- Crops whose block has no crop name (texture pack / pattern block) are now learned from the floor: the block that makes up most of the floor under you while a `+x Crop` line shows belongs to that crop, so every one of them breaking near you counts (before, only the block you aimed at counted: ~50 for 6000 blocks).

## 0.9.9
- **Counts every crop block that breaks near you** (default, `/cpp milestones source blocks`): the server's milestone counts the blocks your enchants break too, so counting only the block under the crosshair ran behind. A crop block turning into the cooldown block (or air) is +1; the cooldown block turning back is not. `/cpp milestones radius <3-16>` sets how far around you (12 by default). `/cpp milestones source mine` goes back to counting only the block you aim at.

## 0.9.8
- A status line under the milestones panel shows what the live counter sees: `aim: Bee Nest -> Bee Nest | counted 12`, or why it is stuck (`no crop match`, `cannot read the block you look at`, `error: ...`). Menu toggle: "Status line under the panel".

## 0.9.6
- Open `/warp` > "Warp to Crop" once: its items are the crop blocks (`Bee Nest` = `minecraft:bee_nest`), so the mod learns which block belongs to which crop whatever the texture pack makes it look like.

## 0.9.5
- Crops that look like another block (pattern blocks): a block you hit that turns into the cooldown block while a `+x Crop` line is showing counts +1; the cooldown block is learned, so it going back to the crop is not counted. The block under the crosshair is now found by type, and `/cpp milestones debug` lists what you are looking at.
- If `/stats` is open and no Completion item can be read, the menu is written to `config/cobblestoneplus/stats-dump.txt`.

## 0.9.4 changes
- **Milestones count up live, by 1.** The block you break yourself (the one under your crosshair while you hold attack) adds 1 to its crop: 2019 -> 2020. Enchant-broken blocks are not counted. The block goes into its cooldown block and back; only the break counts. A block whose name is not a crop name counts for the crop of the last `+x Crop (total)` action bar line (within 4s).
- **Mobs.** A mob with a milestone name (`Chicken`) that dies within 16 blocks (or vanishes within 10) shortly after you swung adds 1, enchant kills included. A mob that respawns after 3s counts again when killed again.
- **Stage-ups.** "Stage N ... completed / reached" lines finish that stage at once; finished stages also move the matching Completion category.
- **Exact numbers win.** Tracked counts show a `~`; opening `/milestones` puts the exact count back.
- **Ratio mode (off by default).** `/cpp milestones ratio` or the menu toggle. Every menu read teaches the mod how much the menu count grew per block / kill it counted live; with the toggle on, each counted block / kill moves the milestone by that ratio instead of a plain +1 (it overrides the +1).
- **Other source.** `/cpp milestones source farmed` counts from the growth of the sidebar `FARMED` line instead (blocks is the default). `/cpp milestones live` turns live counting off, `/cpp milestones debug` shows what was counted last.
- **Better rows.** The crop / mob you are working on is first (gold), its numbers flash green on every +1, and each row shows the full count, the target and the percent (`2,020/2,500  80.8%`).
- **Title, subtitle and boss bars are read** (not only chat and action bar).
- **`/stats` reader is more tolerant** (any item with "Completion" in its name, percent in the name or tooltip, `Name: 12/48` lines).
- **`/cpp feed on`** writes every server line no reader understood (chat, action bar, title, subtitle, boss bar, sidebar) and every menu you open (item names + tooltip lines) to `config/cobblestoneplus/feed.log`, so the readers can be matched to the server's exact wording.
