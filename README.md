# Echo: Music Player

<p>
An Extension-based Music Player for Android, designed with a clean and intuitive UI.
</p>

> [!NOTE]
> The developer of this application is not liable for any misuse or legal issues arising from its
> use and is not affiliated with any content providers. This application hosts zero content.
>
> Echo is intended for offline use only by default; the user manages any external sources. Echo does
> not condone or supports piracy.

## Development
The focus of the app has now been shifted to be [Multiplatform](https://github.com/brahmkshatriya/echo/tree/compose) (using compose). That means Echo Desktop and a remake of Android app is under development. If you still want to download the old version, you can look around in the discord server.

## Official Communities

Join our communities to stay updated and contribute to the discussion:

<a href="https://discord.gg/J3WvbBUU8Z" style="margin-right: 10px; display: inline-block;"><img src="https://uxwing.com/wp-content/themes/uxwing/download/brands-and-social-media/discord-round-color-icon.png" alt="Discord" height="40" style="vertical-align: middle;"></a>

---

## 🌿 About This Custom Fork

This project is a modified fork of the original **[Echo Music Player](https://github.com/brahmkshatriya/echo)** created by [brahmkshatriya](https://github.com/brahmkshatriya).

While keeping the amazing extension-based foundation of the original app, this custom version introduces a fresh look and several Quality-of-Life (QoL) improvements.

### ✨ New Features in this version:

*   **Spotify-Style Lyrics Sharing:** Easily select your favorite lines from a song and generate a beautiful, shareable PNG card featuring the lyrics and the album cover.
*   **Search by Artist in Playlists:** You can now filter and search for specific artists directly inside your playlists, making it much easier to navigate large collections.
*   **Multi-Select Playlist Editing:** Editing your playlists is now faster than ever. You can select multiple songs at once in the edit view and delete them in bulk.
*   **Fresh Green UI & Logo:** The application has been redesigned with a brand new default Green color scheme and a matching updated app icon.
*   **Fetch lyrics:** from metadata and builtin lrclib extension.
*   **Karaoke mode:** Syncs songs word by word, you can now also choose between unsynced, synced and karaoke mode (only for supported songs).

### 🎤 Karaoke Sources (Word-by-Word Lyrics)

To provide the best word-by-word synchronization for Karaoke mode, this app fetches lyrics in parallel from multiple APIs and uses a custom scoring algorithm to pick the most accurate one. Thanks to the open-source community for making this possible:

*   **Musixmatch** (Native `richsync` format)
*   **Spicy Lyrics** - *Based on / inspired by [better-lyrics](https://github.com/better-lyrics/better-lyrics)*
*   **LyricsPlus / KPoe** - *Aggregated via community APIs and projects: [YouLyPlus (ibratabian17)](https://github.com/ibratabian17/YouLyPlus), [am-lyrics (binimum)](https://github.com/binimum/am-lyrics), and [YouLyPlus (Paxsenix0)](https://github.com/Paxsenix0/YouLyPlus)*
*   **Apple Music** (TTML format) - *Thanks to [ancientcatz/echo-apple-music-extension](https://github.com/ancientcatz/echo-apple-music-extension/)*
*   **LRCLIB** (Enhanced LRC format)
*   **NetEase Cloud Music** (Native YRC format)

### 💬 Join the Fork's Community

Join my Discord server for updates, support, or just to chat about this specific version:

<a href="https://discord.gg/bHsMPrgWRy" style="margin-right: 10px; display: inline-block;"><img src="https://uxwing.com/wp-content/themes/uxwing/download/brands-and-social-media/discord-round-color-icon.png" alt="Discord Fork" height="40" style="vertical-align: middle;"></a>
