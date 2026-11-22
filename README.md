# SleepCast

> ## ⚠️ AI-generated code: use at your own risk
> This app was "vibe coded": it was written almost entirely by an AI assistant (Claude, by Anthropic) from plain-language requests. A human has **not** reviewed it line by line.
>
> - It's a hobby project, provided **as-is** with **no warranty**. It may have bugs, battery-drain issues, or behave differently on your phone.
> - It hasn't been security-audited. Read the code before trusting it with anything important.
> - It downloads podcast audio over your network. Check your data plan and storage, and the "Allow streaming on mobile data" setting.
> - The author isn't responsible for lost data, data charges, missed alarms, or anything else that happens from using it.
> - Podcasts belong to their creators. This app only plays public RSS feeds the same way any podcast app does. Respect the creators' terms.
>
> Issues and fixes are welcome, but expect AI-written code quality.

---

A simple Android "background noise" podcast player. Open it at bedtime and it picks random episodes from a podcast feed, in an episode range you choose. It downloads them on Wi-Fi and plays until you stop it in the morning.

## Features
- **Any podcast:** works with any public RSS/XML podcast feed (and basic Atom feeds).
- **Episode range:** e.g. only episodes #1–#700, or #200 onward.
- **Random queue each night:** 20 episodes by default, and you can change the number. Optionally adds another batch when the queue runs out, so it never goes silent.
- **Wi-Fi caching:** downloads the queue in the background on Wi-Fi. Off Wi-Fi it plays only downloaded episodes, unless you allow mobile data.
- **Sleep-friendly design:** a night-sky screen with twinkling stars, a big "Now playing" card with a progress bar, an "Up next" list, and a large Stop button.
- **Bedtime extras:** an optional sleep timer and adjustable playback speed. It pauses for alarms and calls and resumes after, and pauses if headphones are unplugged.
- **Side menu (☰):** Now playing, Settings, Shuffle a new queue, How it works, About & disclaimer.
- **Doesn't restart itself:** opening the app while it's playing just shows the controls.
- **No third-party libraries:** plain Android framework only (Android 8.0+).

## The Settings page
Open it from the ☰ menu. Everything except the Podcast section saves as you change it, and takes effect right away, even mid-playback.

| Section | Settings |
|---|---|
| **Podcast** | RSS feed URL · episode range (from / to, 0 = no limit) · episode numbering (automatic / feed numbers / release order) · **Test feed** (shows the show name and how many episodes are in range) · **Save** |
| **Playback** | Episodes per night · start playing when opened · keep going after the queue · sleep timer (15 min – 8 h) · playback speed (0.75× – 1.5×) · pause when headphones unplug |
| **Downloads & data** | Download queue ahead on Wi-Fi · stream on mobile data · keep up to N episodes · storage used, with a **Clear** button |
| **Episode list** | How often to check the feed for new episodes · refresh now |
| **About** | Disclaimer · project page · version |

**Reset all settings** at the bottom goes back to the built-in defaults in `config.xml`. Downloaded episodes are kept.

## Setting it up for your podcast

There are two ways. Use either or both.

### 1. Built-in defaults (before building)
Edit **`app/src/main/res/values/config.xml`**:

| Setting | What it does | Example |
|---|---|---|
| `app_name` | Name under the app icon | `SleepCast` |
| `default_feed_url` | The podcast's RSS feed | `https://feeds.megaphone.fm/roosterteethpodcast` |
| `default_min_episode` | First episode allowed | `1` |
| `default_max_episode` | Last episode allowed (`0` = no limit) | `700` |
| `default_queue_size` | Episodes picked per night | `20` |
| `default_numbering` | `auto`, `tag` or `order` (see below) | `auto` |
| `auto_start_on_open` | Start playing as soon as the app opens | `true` |
| `max_cached_episodes` | Max episodes kept on the phone | `30` |
| `feed_refresh_days` | Re-check the feed every N days (`0` = fetch once) | `7` |
| `project_url` | Link shown on the About screen | your repo URL |

The shipped defaults are an example (Rooster Teeth Podcast, episodes 1–700). Change them to your show.

To run more than one copy side by side (one per podcast), also change `applicationId` in `app/build.gradle`.

### 2. In the app
Open **☰ → Settings** to change any of it without rebuilding (see above).

### How episodes get their numbers
- **auto (default):** uses the feed's own numbers (`<itunes:episode>`, or "#123" / "Episode 123" in the title) when at least 80% of episodes have one. Otherwise it uses release order.
- **tag:** only the feed's own numbers. Episodes without one are skipped.
- **order:** counts by release date, so the oldest episode is #1. Use this when a show's numbering is messy or missing.

Episodes marked as bonus or trailer in the feed are always skipped.

**Finding a feed URL:** search the show on [podcastindex.org](https://podcastindex.org) or a site like Podnews/Castbox and look for "RSS". Many hosts (Megaphone, Libsyn, Anchor/Spotify for Podcasters, Buzzsprout) show it on the show page.

## Building the APK

### Option A: Android Studio
1. **File → Open** and choose this folder. Wait for Gradle sync.
2. **Build → Build App Bundle(s)/APK(s) → Build APK(s)**, or press Run with your phone plugged in.

### Option B: GitHub Actions (no PC needed)
1. Put this folder in a GitHub repo, including the hidden `.github` folder.
2. The **Actions** tab runs **Build APK** on every push. Download the **SleepCast-apk** artifact from the finished run.

### Signing
Builds are signed with `app/sleepcast.keystore` (password `sleepcast`) so updates install over each other. That's fine for personal use. **If you publish builds for other people, generate your own keystore** and keep it out of a public repo:
```
keytool -genkeypair -keystore app/my.keystore -alias mykey -keyalg RSA -keysize 2048 -validity 20000
```
Then update `signingConfigs` in `app/build.gradle`.

## Installing
1. Open the APK on your phone and allow "Install unknown apps" when asked.
2. Allow notifications. That's how the lock-screen controls show.
3. **For reliable overnight playback:** Settings → Apps → SleepCast → Battery → **Unrestricted**. Samsung, Xiaomi and OnePlus phones in particular can stop background audio otherwise.

## Project layout
```
app/src/main/res/values/config.xml   ← your settings
app/src/main/java/com/sleepcast/player/
  MainActivity.java     now-playing screen + side menu
  SettingsActivity.java the Settings page
  PlayerService.java    playback, queue, Wi-Fi downloads, sleep timer, notification
  StarfieldView.java    the night-sky background
  Downloads.java        saved-episode storage
  FeedRepository.java   RSS parsing, numbering, range filter, feed cache
  Settings.java         config.xml defaults + in-app overrides
  Episode.java
.github/workflows/build.yml          cloud build
```

## License
Do what you like with it (public domain / [Unlicense](https://unlicense.org)). See the disclaimer at the top.
