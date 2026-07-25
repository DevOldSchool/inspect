# Inspect

Inspect is a RuneLite plugin for optional Old School RuneScape inspect tools. It adds a single sidebar panel for looking up items, NPCs, players, and recent inspections.

Most features are backed by OSRS Wiki data. Enable OSRS Wiki lookups once in the plugin config, then the individual wiki-backed features are available by default and can be disabled separately.

## Features

- **Item inspect**: inspect item widgets or search items from the sidebar.
- **NPC inspect**: inspect NPCs or search NPCs from the sidebar.
- **Exact item variant picker**: choose a specific item version by wiki anchor and game ID when a search page contains multiple variants.
- **Player inspect**: inspect visible player equipment from client-side player composition data.
- **Recent inspections**: return to recent item, NPC, and player inspections from the sidebar.
- **Equipment recommendations**: rank owned bank/equipped gear for inspected NPC weaknesses.
- **Bank highlights**: highlight recommended bank items with rank indicators.
- **Item prices**: show GE price, high alch, low alch, and high-alch profit or loss.
- **Item sources**: show how an item can be obtained from shops, monsters, skilling, quests, and clues where available.
- **Requirement checks**: show item equip and source skill requirements with local skill readiness.
- **NPC required items**: show item requirements for monsters that need a finishing item, with inventory/equipment readiness checks.
- **Drop filters**: filter NPC drops into useful categories like valuable, rare, Slayer-only, clue, Ironman, alchable, and upgrade materials.
- **Compare tray**: save an NPC, item, or player inspection as the current comparison and reopen it from the sidebar.
- **Slayer and drop summaries**: show NPC Slayer details, clickable Slayer master links, and drop-table information when available from the wiki.

## Item Inspect

Item inspect shows wiki-backed item details, prices, requirements, sources, gear-role tags, bonuses, and comparison details where available.

Searches for multi-version items show each exact variant with its item icon, version anchor, and game ID. Searches that already name a unique variant, such as `Dragon dagger(p++)`, open that variant directly.

The Sources section expands item acquisition into readable categories such as shops, monsters, skilling, quests, and clue-related sources.

The Requirements section combines equip requirements and source requirements in one readiness panel. Skill requirements are checked against your local real skill levels and update after login or level changes.

![Item inspect example](images/item.png)

## NPC Inspect

NPC inspect shows combat stats, weakness summaries, Slayer details, required items, drop filters, a lightweight kill checklist, and equipment recommendations.

Required items are grouped by condition. For example, gargoyles show the valid finishing items as alternatives, with each row marked as missing, in inventory, or equipped. The check refreshes when inventory or equipment changes.

Drop filters show one item per row, with item icons where RuneLite can resolve them. Rows with resolved item IDs can be right-clicked and inspected directly. The current filters are valuable, rare, Slayer-only, clue, Ironman, alchable, and upgrade.

Slayer master tags open their OSRS Wiki pages.

![NPC inspect example](images/npc.png)

## Player Inspect

Player inspect shows equipment visible through RuneLite's player composition data. It can compare visible gear against your current equipment and show simple visible gear tags.

Player equipment inspect does not upload, store, or crowdsource player gear.

![Player inspect example](images/player.png)

Player inspect is disabled in PVP areas.

![Player inspect in PVP example](images/player-pvp.png)

## Compare

The Compare tray keeps one pinned NPC, item, and player inspection. Pinning an inspection makes future inspections show a compact comparison against the saved entry. Pinned rows are clickable, so saved comparisons can be reopened without searching again.

## Recent Inspections

The Recent tab keeps short clickable lists for returning to item, NPC, and player inspections.

![Recent inspections example](images/recent.png)

## Configuration

- **Enable OSRS Wiki lookups**: required for wiki-backed item, NPC, search, and recommendation features. Disabled by default because it contacts a third-party server.
- **Player equipment inspect**: adds an Inspect menu option to players.
- **NPC Inspect**: adds an Inspect menu option to NPCs. Requires OSRS Wiki lookups.
- **Item Inspect**: adds an Inspect menu option to item widgets. Requires OSRS Wiki lookups.
- **Inspect search**: enables sidebar search for item and NPC information. Requires OSRS Wiki lookups.
- **Equipment recommendations**: enables NPC gear recommendations and bank highlighting. Requires OSRS Wiki lookups.
- **Inspect cache days**: controls how long wiki data is cached.
- **Clear NPC Inspect cache**: clears cached inspect wiki data when the plugin starts.

## Data And Privacy

Enhanced uses OSRS Wiki lookups only when the wiki lookup config is enabled. Wiki responses are cached under the RuneLite directory for the configured cache duration.

NPC and item search results, including item variant choices, are served from the local Inspect cache when a matching cached entry is still fresh.

Player inspect uses locally visible client data only. It does not expose player information over HTTP.

## Development

Build and test:

```sh
./gradlew test
```

Run a development RuneLite client:

```sh
./gradlew run
```
