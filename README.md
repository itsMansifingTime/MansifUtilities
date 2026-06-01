# MansifUtilities

Fabric 1.21.11 client mod — flip alerts, bazaar log (syncs to [MansifTracker](https://github.com/itsMansifingTime/mansiftracker) `/bazaar-portfolio`), inventory helpers for Hypixel SkyBlock.

## Build

Java 21:

```bash
./gradlew build
```

Install `build/libs/mansifutilities-*.jar` into `.minecraft/mods`.

## Flip bridge (MansifTracker EC2)

Server config: `data/bin-deal-ingame-bridge.json` in MansifTracker (not in this repo).

Client: auto-writes `.minecraft/config/mansifutilities-flip-bridge.json`. Commands: `/mansifbridge status`, `sync`, `secret`, etc.

See [FLIP_BRIDGE.md](FLIP_BRIDGE.md).
