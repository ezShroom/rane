# rane

A server-side Fabric port of [vane](https://github.com/oddlama/vane) for Minecraft 26.3. Players join with an
unmodified vanilla client. Custom items use the rane resource pack, which the server pushes while they join.

It needs Fabric API and Fabric Language Kotlin. sgui and fabric-permissions-api are bundled, LuckPerms is optional
(any permissions mod works), and BlueMap is optional.

## Modules

Every module can be switched off in `config/rane/rane.json`. Each one has its own `config/rane/<module>.json`.

| Module | What it does |
|---|---|
| **admin** | Hazard protection (creepers, withers, endermen, door-breaking zombies, lightning fire, withers limited to some dimensions). Suppressed explosions rebuild themselves block by block, chest contents included. Vane-style chat (`name: message`), `/gm` and `/slimechunk`. |
| **bedtime** | When a share of players (50% by default) sleep, the night skips with a smooth sunrise and sleep/wake announcements. |
| **greylist** | Replaces vane's vouching with a Discord-backed greylist (see below). |
| **regions** | `/rg`: buy land with diamonds (refunded on delete), group regions under shared rules, assign roles (build, use, containers, portals, admin) and environment settings (monsters, PvP, explosions, fire, trampling). |
| **portals** | Vane's portals. Build a frame from obsidian, crying obsidian, gold, gilded blackstone or emerald blocks around a netherite origin. Link an enchanting-table console (sneak and right-click it, then right-click the frame), name the portal, pick a destination, flip a lever. Any entity can travel, minecarts included. Visibility can be private, public or region-group. Floating icons show the destination. |
| **enchantments** | Angel, Wings, Take Off, Grappling Hook, Hell Bent, Leafchopper, Lightning (off by default), Rake, Seeding, Soulbound and Unbreakable. They're data-driven, so vanilla clients display them. You get them from ancient tomes, which you craft or find in structure chests. |
| **trifles** | Home, spawn, unstable, lodestone and death scrolls. Sickles, file, trowel, XP bottles, reinforced elytra, north compass, slime in a bucket, pouch and backpack. Also right-click harvesting, double doors, chest sorting with buttons, faster paths, invisible item frames (shears), no "Too Expensive!", all recipes unlocked, `/finditem`, `/heads` (32k heads for bones) and `/setspawn`. |

### What's different from vane

- **Left out:** autostop, vane-permissions/vouch (LuckPerms covers permissions and the greylist replaces vouching),
  proxy/velocity/geyser/busybar, and vane's own spawn protection.
- **Vanilla commands stay:** `/time`, `/weather` and `/gamemode` aren't overridden. `/gm` is just a toggle.
- **No portal styling yet.** Frames keep the blocks you built them from, the active area is end gateway, and the
  origin is disguised as obsidian (vane's default style).
- **Resource pack hosting:** packs are pushed from the URLs in `config/rane/rane.json` instead of a built-in web
  server. Several packs can be listed.

## The Discord greylist

New players can look around (adventure mode, invulnerable, no chat, no pickups). Each one gets a code in chat and
runs `/link <code>` in one of the Discord servers you allow. After that the account is linked, the restrictions lift
immediately, and the link is stored with the world.

Set it up in `config/rane/greylist.json`:

```json
{
  "botToken": "",                        // or the RANE_DISCORD_TOKEN environment variable
  "allowedGuilds": ["123456789012345678"],
  "onLinkCommands": ["lp user {player} parent add member"]
}
```

The bot needs no privileged intents. Invite it with the `applications.commands` and `bot` scopes. Admin commands are
`/greylist status|approve|unlink <player>`. Anyone with `rane.greylist.bypass` (ops by default) is never greylisted.

## Building

```sh
./gradlew build   # build/libs/rane-<version>.jar and build/libs/rane-resourcepack.zip
```

Textures and the head library come from vane (MIT). The slime bucket overlay is new, because vane's version came from
Quark under a non-commercial licence.
