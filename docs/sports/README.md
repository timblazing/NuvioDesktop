# Sports test build

Based on NuvioDesktop `0.1.33-alpha`. Inspired by the event-first Sports hub in
[homelessbrian/NuvioPlusIPTV](https://github.com/homelessbrian/NuvioPlusIPTV),
reference revision `50e713e33b9ed6fc48bd3bdec5a773e0a552d160`. Both repositories use GPL-3.0; the existing
license remains in place. This implementation uses NuvioDesktop's Compose UI,
Ktor client and native player rather than importing Android TV UI/EPG machinery.

## Run

On macOS with JDK 25:

```sh
./scripts/run-sports-test.sh
```

The separate window opens directly to Sports without requiring an upstream account
or movie addons. Data lives under `~/Library/Application Support/Nuvio Sports Test`,
separate from the installed Nuvio app. The normal app also exposes Sports in both
desktop navigation layouts.

To make a separate, unsigned app bundle:

```sh
./gradlew :composeApp:createDistributable -Pnuvio.sportsTest=true
```

Output: `composeApp/build/compose/binaries/main/app/Nuvio Sports Test.app`.
This build does not register Nuvio/Stremio URL schemes. It is for local testing;
it does not replace `/Applications/Nuvio.app`.

## Use

- Pick a league, browse dates up to a week ahead/behind, filter live events, or search.
- Follow teams on their event cards; My teams filters the selected league/date.
- Connect IPTV with a server base URL, username and password. Use HLS first; MPEG-TS
  is available for providers that require it.
- Find channels suggests matches from team names and ESPN broadcast network names.
  Suggestions are not verified coverage. Search all channels when no suggestion fits.
- Clicking a channel opens Nuvio's existing native player. Back returns to the hub.
- Remember my choice saves a channel for this event, scoped to the provider/account.
- Browse channels lets you open any provider channel without a guide grid.

Credentials stay in memory and must be entered after restarting. They are not saved
in preferences, and live streams are excluded from watch-history persistence. Favorites
and manual channel IDs are saved locally. HTTP providers transmit login details without
TLS; use HTTPS if supported. Test mode skips Sentry and Discord startup.

## Scope

NFL, NBA, NHL, MLB, college football/basketball, Premier League, Champions League,
MLS, La Liga, UFC and Formula 1 use ESPN's public scoreboard feed. Times use the Mac's
timezone. Live schedules refresh every 30 seconds while visible; other schedules every
five minutes. Previous results remain visible if refresh fails, with an error message.
No channels or video are supplied by the app.

This first desktop version does not include the fork's full EPG matching, standings,
secondary sports datasets, catch-up, reminders, recordings or season schedules. It is an
initial Sports hub, not full feature parity with the Android TV fork. No TV-guide grid.

## Validation

```sh
./gradlew :composeApp:desktopTest --tests 'com.nuvio.app.features.sports.*' -Pnuvio.sportsTest=true
```

Native Compose tests cover connection, channel search, playback handoff, disconnected
playback, filters, and narrow rendering. Unit tests cover scores, malformed rows,
matching boundaries, URL escaping, provider-scoped choices, and live-history privacy.
UI fixtures are synthetic games and channels, not a live IPTV playback claim. Images
are generated in `composeApp/build/reports/sports/`; test preferences are isolated
under build directories. Real-provider validation is a separate acceptance step.

Sports schedules, scores, team names and logos come from ESPN's public feed. This fork
is not affiliated with ESPN or NuvioMedia; team and league marks belong to their owners.
