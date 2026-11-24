# WebDAV Sync

Copies music from a WebDAV server (a Synology NAS, Nextcloud, any server that speaks WebDAV) to the player, so you can
listen without a cable. The copy goes one way: the server to the player. Classy never uploads, renames or deletes
anything on the server.

Settings → Library → WebDAV Sync.

## Part 1: For users

### Set it up

1. **Server URL**: the address of the folder that holds your music, for example `https://192.168.1.10:5006/music`.
   Everything below that folder can be synced.
2. **Username** and **Password**: type them with the keyboard screen.
3. **Test Connection** checks the address and the login. It says Connected, or why it could not connect.

Typing a long address on the wheel is slow. Instead, put a file named `classy_webdav.json` in the top folder of the
player's storage:

```json
{ "url": "https://192.168.1.10:5006/music", "user": "name", "password": "secret" }
```

Opening WebDAV Sync reads the file and then **deletes it**, so the password does not stay on the card in plain text.

### A self-signed certificate (https)

A certificate that Android trusts (from a known authority, for the name in the Server URL) works with no step.

A NAS at home usually has a certificate that no authority signed. The first time you connect, Classy shows the
certificate's fingerprint (four lines of hex). Compare it with the one on your NAS. If it is the same, choose **Trust
Certificate**. Classy then accepts that one certificate: if it changes (a renewal), the connection stops until you
trust the new one. Android 4.4 does not know some newer authorities, for example Let's Encrypt: such a certificate
also needs Trust Certificate, and again after each renewal.

### Choose the folders

**Folders** shows the folders of the server, and how many you ticked.

- **Center** opens a folder.
- **Hold** Center ticks or unticks it (a tick shows at the left).
- Ticking a folder takes everything inside it. The folders inside show ticked, and cannot be unticked one by one.
  To leave one out, untick the parent and tick the folders you want.

Folders you tick are what the player keeps. A folder you untick is removed from the player at the next sync.

### Sync

- **Sync Now** lists the ticked folders, downloads new and changed files, and deletes the files of folders you
  unticked. A screen shows the folder or file that is in progress and the speed, and it can be cancelled.
- Files go to `Music` on **Save To**: Internal, or the SD Card. **Move Synced Files** shows up when you change Save To
  and moves the files already synced (nothing is downloaded again). Until you move them, a sync leaves them where they
  are; only a file that changed on the server downloads to the new place, and its old copy is deleted.
- When the sync downloaded or deleted files, the library scans again, so the songs show up.
- **Parallel Downloads** (1 to 4): files that download at the same time. 1 is the gentlest for a weak Wi-Fi or NAS.
- **Last Synced** shows the time of the last sync. After a sync that failed (also an Auto Sync), **Last sync failed**
  shows its error under it, until a sync works.

A song that is playing is never replaced or deleted. It waits for the next sync.

If nothing is ticked, Sync Now asks before it deletes everything it synced.

### Auto Sync

With **Auto Sync** on, the player syncs by itself when it is on **Wi-Fi and charging**, at most once an hour. It
downloads, but it never deletes everything when no folder is ticked.

### Files you deleted on the server

When a synced file is gone from the server, Classy does not delete your copy. It lists the file under **Deleted on
NAS**, and you choose: delete one, or Delete All. Your copy is kept until you do.

### Log Out / Reset

Forgets the address, login and certificate, and the ticked folders. The songs stay on the player: Classy forgets that
it synced them, so no later sync deletes them. Delete them with a file manager if you do not want them.

A **Server URL** of another server (another host, port, or `http`/`https`) does the same: no ticked folders, no
certificate, and the songs of the old server stay. A sync of the same folders downloads them again over the old copies.
Another path on the same server keeps everything.

### If something goes wrong

| Message | What to do |
|---|---|
| Server unreachable | Is the player on the right Wi-Fi? Is the address right, and the NAS on? |
| Check username/password | The server refused the login. |
| Certificate not trusted | Compare the fingerprint, then Trust Certificate. |
| Not enough space | Free some space, or tick fewer folders. |
| HTTP 404 on a folder | The folder is gone from the server. Its files go to Deleted on NAS. |

On a FAT card (the SD card), characters that FAT does not take (`< > : " | ? * \`) in a file name become `_`.

---

## Part 2: For developers

### Where the code is

| File | What it does |
|---|---|
| `sync/webdav/WebDavSyncProvider.kt` | Server settings and the ticked folders, in the private prefs `webdav_sync`. One-time import of `classy_webdav.json`. |
| `sync/webdav/WebDavClient.kt` | The HTTP side: PROPFIND (Depth 1) and GET. OkHttp. |
| `sync/webdav/CertPin.kt` | The system's authorities, or trust on first use: one pinned leaf certificate (SHA-256). |
| `sync/webdav/SyncPlan.kt` | Pure functions: what to download, mark or delete. Path mapping. No I/O except `pruneEmptyDirs`. |
| `sync/webdav/WebDavSync.kt` | Runs a sync or a move on one background thread. Owns the ledger. |
| `sync/webdav/AutoSyncGate.kt`, `WebDavAutoSyncReceiver.kt` | When Auto Sync may start. |
| `sync/webdav/WebDavErrors.kt` | `errorText`: the one-line message for an error. |
| `sync/SyncLedger.kt` | The record of what was synced (shared with Jellyfin Sync). |
| `controllers/sync/WebDavUiController.kt` | The screens: settings, folder picker, Deleted on NAS, progress. |

### Data

- **Prefs** `webdav_sync`: `url`, `user`, `password`, `pin`, `dest_sd`, `auto_sync`, `parallel`, `ticked` (a set of
  hrefs), `last_sync`, `last_auto_attempt`, `last_error` (`errorText` of the last failed sync, empty after one that worked). The password is in plain text in the app's private prefs. Clear Data or
  Log Out removes it.
- **Ledger** `webdav_ledger.json` in the primary storage root. One item for each synced file, keyed by the file's
  **href** (percent-encoded path on the server). The `LedgerItem` fields, with the Jellyfin names they keep:

  | Field | Here |
  |---|---|
  | `jellyfinId` | the file's href |
  | `libraryId` | the ticked folder it was found under (can go stale; paths decide, see below) |
  | `localPath` | the file on the player |
  | `fileSize` | bytes written |
  | `serverDateModified` | `"<etag>\|<lastModified>"` of the server file, the change check |
  | `orphaned` | gone from the server, waiting for the user |

### The client

- `list(href)`: `PROPFIND` with `Depth: 1` and a small property body. Sub-folders are walked one request each, with
  a visited set and a `MAX_FOLDERS` (20 000) cap, so a server that links back to itself ends in an error.
- `download(href, target, size, cancelled, progress)`: `GET` into `target.part`, check the length, then
  `replaceFile` (rename; on FAT, delete the old file and rename). A failed or cancelled download leaves the target as
  it was.
- Auth is HTTP Basic, sent with every request. `urlFor(href)` refuses an absolute href that names another host, so
  the password is never sent to a server the user did not enter.
- Redirects are not followed: a 3xx answer is an error (`HTTP 301`), so no request, and no password, goes to another
  host. A relative href must be a path starting with `/`. An absolute href of this server is turned into its path when the listing
  is read, so the ledger key and the folder hrefs are always paths.
- hrefs are compared percent-encoded; `decodeHref` decodes each segment as UTF-8 (`+` stays `+`).
- Timeouts: 10 s connect, 30 s read.

### Certificates

`https://` uses `PinningTrustManager(pin, systemTrustManager())`. The pinned leaf passes, and the host name is not
checked for it (a NAS by IP address). Else a chain that Android's authorities trust passes, and OkHttp's
`OkHostnameVerifier` then checks the host name. Else `checkServerTrusted` throws `CertNotTrusted(fingerprint)`. `findCertNotTrusted` finds it in the cause chain, `WebDavUiController.handleCert` keeps the
fingerprint and shows it, and Trust Certificate saves it as the pin. A Server URL of another server clears the pin
(`WebDavSyncProvider.changeUrl`, also for the config file). `http://` is allowed and has no protection.

### A sync (`WebDavSync.run`)

1. Read the ticked folders and load the ledger.
2. For each ticked folder, `walk` it (all files below, breadth first). A 404 on the ticked folder itself is an empty list (the folder was
   removed on the server); a 404 on a sub-folder skips only that sub-folder; another error stops the sync. A folder that did not list is **not** in `remote`, so its ledger
   items are left alone: a failed listing never means deleted.
3. `SyncPlan.build(remote, ledger, ticked, dest, baseHref, safeNames, exists)` returns a `Plan`:
   - **downloads**: a file that is not in the ledger, whose local file is gone, or whose `etag|lastModified` changed. A
     ledger path that is not this target also downloads, except the same file on the other storage (`otherRoot`, after
     a Save To change): Move Synced Files moves that one. A download whose ledger path is another file has it in
     `replaces`; the sync deletes that copy when the new one is complete, so no copy stays untracked. A target is
     planned once: two names that share a FAT file are not written at the same time.
   - **unorphan**: a file that is back on the server.
   - **orphan**: a ledger item that is gone from the server but whose folder is ticked and was listed.
   - **remove**: a ledger item that no ticked folder covers any more (the user unticked it), unless the local file is
     also the target of a file of a ticked folder (FAT names that became one).
   - Coverage is by **path** (`isUnder`), not by `libraryId`: the stored folder goes stale when you tick a parent or
     a sub-folder instead.
4. Check free space for `plan.bytes` (`NotEnoughSpace`).
5. Save the orphan marks. Delete `plan.remove` with `deleteTracked`, but only if a folder is ticked, or the user
   confirmed (`removeAllUnticked`). Auto Sync never sets it.
6. Download with a fixed pool of `parallel` threads. The first error stops the others. The ledger is saved every 20
   files and in a `finally`, so finished files stay tracked after an error.
7. Update `lastSync`. If anything was downloaded or deleted, `onDone(changed = true)` makes the UI send
   `RESCAN_LIBRARY`.

If a sync fails or is cancelled after it downloaded or deleted files, the UI still asks for a library rescan
(`WebDavSync.changedByRun`).

`deleteTracked` skips a file that is playing (`AudioPlayerManager.isFileCurrentlyPlaying`) or that cannot be deleted:
it stays in the ledger and goes in the next sync. After a delete it calls `pruneEmptyDirs`, which removes the empty
folders above the file, up to the `Music` roots of both storages (`File.delete()` fails on a folder that has files, so
nothing else is removed).

`isRunning`, one single-thread executor and `launch` make sure there is only one sync at a time. `forgetAll(change)`
(Log Out / Reset, another server) takes the same lock, refuses while a sync runs, and empties the ledger: the files stay
on the player, untracked. `launch` holds a
partial wake lock and a Wi-Fi lock (`Classy:WebDavSync`) for the run; the wake lock has a 6 hour cap.

### Paths

`localFileFor(href, baseHref, destRoot, safeNames)` maps a server path to `<Music>/<path below the base URL>`. It
returns null for an href outside the base, an empty one, or one with `.` or `..` (a server cannot write outside
`Music`). With `safeNames` (the SD card, which is FAT), `fatSafe` replaces the characters FAT cannot take. Two names that
differ only in those characters end up as one file.

### Moving between storages

`startMove` runs `move`: for each ledger item whose path is under the other storage's `Music`, copy to `*.part`, check
the length, replace the target, then delete the source. A record whose source is missing (the SD card is out) is moved
only if a complete file of the recorded size is already at the target; else it stays as it was. If the delete fails, the new copy is removed and the item stays
where it was, so a move never leaves a file in two places, and a failure or cancel never loses one. The ledger is updated
for each file. A file that is playing waits. The ledger is saved when the move ends, also after an error or a cancel.

### Auto Sync

`AutoSyncGate.shouldRun(enabled, configured, wifi, charging, running, now, lastSync)` is a pure function: on, set up,
Wi-Fi, charging, not running, and at least an hour since the last sync or attempt (`MIN_INTERVAL_MS`; a clock set back
also passes). `lastAutoAttempt` is saved also when the sync fails, so a server that is down is not tried at every
broadcast. A stored time that is after now (the clock was set back) does not count (`lastCounted`): the first try is
allowed and its own time throttles the next.

`WebDavAutoSyncReceiver` is registered in code by `ClassyApplication.onCreate`, for `CONNECTIVITY_ACTION` and
`ACTION_POWER_CONNECTED`. It is **not in the manifest**: on the Y2 (Android 4.4) a manifest receiver for a connectivity
change makes Android start a second process of the app, and `ClassyApplication.stopStaleProcesses` then stops the
running launcher as the old process, which restarted Classy every time you turned Wi-Fi on or off.
The limit: no auto sync while the app process is not running. A `JobScheduler` would fix it, but it needs API 21.

### The UI

`WebDavUiController` builds three `ListSpec`s from a `WebDavLevel`: `Root` (settings), `Folders(href)` and `Orphans`.

- Sub-folders load on demand, one `PROPFIND` for each, and are cached in `folders` (cleared on Log Out and when the
  Server URL changes). A refresh of a
  loaded folder keeps the old list on screen if it fails.
- A folder row is `RowLook.CHOICE`. Center opens (`onClick`), Hold ticks (`onHold`, a long click through the list).
  A folder under a ticked parent shows ticked and has no `onHold`; a ticked folder is not under itself, so its own Hold
  unticks it.
- `toggle` unticks, or ticks and drops the ticked folders inside it (the new folder covers them).
- `launchSync` opens a progress screen (`ActionScreens`), updates it at most 4 times a second, and smooths the speed.
- A new text needs all seven language packs (`scripts/check_translations.py --strict`).

### Tests

`app/src/test/java/com/themoon/y1/sync/webdav/`: `SyncPlanTest` (plan, paths, FAT names, `pruneEmptyDirs`),
`WebDavClientTest` (PROPFIND parsing, hrefs, same server), `AutoSyncGateTest`, `CertPinTest`, `WebDavConfigFileTest`,
`WebDavErrorsTest`.

### Limits

- One way only: no upload, no two-way merge.
- The check for a changed file is the server's `etag` and `lastModified`, not a hash.
- Basic auth only, and the password is stored as plain text in the private prefs.
- A file name that is the same after `fatSafe` shares one file.
- No partial resume: a cancelled download starts again.
- Music only: the ledger item type is `music`; Jellyfin Sync is the one with other kinds.
