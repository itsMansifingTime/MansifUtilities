# MansifUtilities flip bridge (MansifTracker / EC2)

When the BIN deal scanner sends a flip to Discord, the same flip is queued on the MansifTracker API. MansifUtilities polls that feed and prints it in Minecraft chat. A keybind runs Hypixel `/viewauction` for the **latest** flip.

## Server (EC2 — repo JSON, no PM2 env)

1. Edit `data/bin-deal-ingame-bridge.json` in Cursor (`apiBase`, `feedSecret`, `pollIntervalMs`), then `git pull` on EC2 and `pm2 reload mansif-next-api`.
2. Do **not** set `enabled: false` unless you want to disable the in-game feed (Discord unchanged).
3. Open TCP **3001** on EC2 so your PC can reach `GET /api/bin-deal-ingame-feed`.

## Client (Minecraft)

1. Build: `./gradlew build` (Java 21). Install `build/libs/mansifutilities-*.jar` into your Fabric `mods` folder (1.21.11).
2. **First launch** — the mod auto-writes `.minecraft/config/mansifutilities-flip-bridge.json` from bundled defaults + `/mansifbridge sync` (matches `feedSecret` in `data/bin-deal-ingame-bridge.json`). Override in-game only if needed: `/mansifbridge secret …`

3. **Commands** (all save to the JSON file immediately):

| Command | Action |
|---------|--------|
| `/mansifbridge status` | Show config path and readiness |
| `/mansifbridge sync` | Pull `apiBase` from server `/api/bin-deal-ingame-bridge-config` |
| `/mansifbridge api <url>` | Set and save API base |
| `/mansifbridge secret <value>` | Set and save feed secret |
| `/mansifbridge poll <ms>` | Poll interval (500–60000) |
| `/mansifbridge enable` / `disable` | Toggle polling |

4. In **Controls → Key Binds → MansifUtilities Flip Alerts**, bind **View latest flip auction** (default `V`).
5. When a flip arrives, chat shows a green summary with a clickable **[View]**. Press your keybind to run `/viewauction` for that flip.

## Notes

- MansifTracker persists recent flips in `data/bin-deal-ingame-feed.json` on EC2 (survives pm2 restart).
- Vercel-only deploys need `SITE_API_ORIGIN` / `NEXT_PUBLIC_SITE_API_ORIGIN` pointing at EC2 if the scanner runs there.
