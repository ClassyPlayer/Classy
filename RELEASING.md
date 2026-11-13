# Releasing Classy

This guide is for maintainers. It explains how to publish beta and stable releases, how to promote a beta, how to fix a stable release, and how to write the release notes.

## Channels

Classy has two update channels:

- **Stable**: GitHub releases marked Latest. Most players are on this channel.
- **Beta**: GitHub pre-releases. Testers get new features before the stable release.

Both channels are built by the same **Build & Release** workflow. Its `channel` input chooses the kind of release. The default is `beta`.

On the player, Settings > General > System Update > Update Channel chooses Stable or Beta. By default the player follows the channel of the build that is installed. A choice made in Settings stays after an in-app update. A build installed by hand (a ROM flash or a sideloaded APK) sets the player back to the channel of that build.

A player on Beta gets the newest release of either kind, so testers move onto a newer stable release when there is one. A player that leaves Beta waits for the next stable release.

System Update does not offer older code, because the data migrations only go one way. A newer tag does not always have newer code: a promoted older beta and a hotfix get a new date tag, but they do not have the newest changes of `main`. For this reason, `update.json` and the app record how many commits of `main` the build has (`mainCommits`). System Update does not offer a release with fewer commits of `main` than the installed build. On Beta it then reads the next older release (up to 3 releases), because a newer beta can have an older tag than a hotfix; when none is newer, it shows the installed build as the latest. When one of the two has no number (releases from before this check, builds made on a computer), only the tags are compared. Never force-push `main`: the number must only go up.

Beta players find releases through the GitHub API. They skip a pre-release that has no `update.json` asset, such as the hand-made `y1-exp.N` builds.

All releases are signed with one fixed key, so `adb install -r` updates any release to any newer one.

## Tags

Tags are based on the build date. The first build of a day has no number. From the second build of that day there is a number, and one counter is shared by both channels.

| Build of the day | Stable | Beta |
| :--- | :--- | :--- |
| First | `20261114` | `20261114-beta` |
| Second and later | `20261114-vN` | `20261114-beta.N` |

For example, on 14 November 2026:

1. A beta in the morning gets `20261114-beta`.
2. A second beta gets `20261114-beta.2`.
3. A stable release that evening gets `20261114-v3`, because it is the third build of the day.

Older tags such as `20260928-build.6` and `20261107-v2` follow the same date rule.

If you type a tag, it must not have the date and number of an existing tag (for example `20261114` when `20261114-beta` exists): the player would see the two as one version. The workflow stops on such a tag.

## Cut a beta

1. Merge the changes to `main`.
2. Open Actions > Build & Release > Run workflow.
3. Leave "Use workflow from" on `main` and `channel` on `beta`. Leave the tag empty to get the date-based tag.
4. The workflow publishes a pre-release with the APK, `update.json`, the ROM zips and `SHA256SUMS.txt`.
5. Write the release notes (see [Release notes](#release-notes)).

## Promote a beta to stable

When a beta has been tested and is ready for everyone:

1. Open Actions > Build & Release > Run workflow.
2. In "Use workflow from", choose the beta tag (for example `20261114-beta`).
3. Set `channel` to `stable` and run the workflow.

This makes a new build of the same commit, with a new stable tag, marked Latest. If you promote a beta that is not the newest one, players on a newer beta keep their beta: the stable release has older code. Never un-tick "Set as a pre-release" on a beta release to promote it: the beta build carries the beta channel inside it, so players that install it stay on Beta.

## Fix a stable release (hotfix)

Use a hotfix when a stable release needs a fix but `main` has changes that are not ready for stable yet.

1. Make a branch from the stable tag. Name it `release/<tag>`:
   ```bash
   git fetch --tags origin
   git switch -c release/20261114 20261114
   ```
2. Cherry-pick or commit the fix on that branch and push it.
3. Run Build & Release from the `release/20261114` branch with `channel` set to `stable`.
4. Make sure the fix is also on `main`, and cut a beta from `main`.

Players on a beta that is newer than the stable tag do not get the hotfix, because it does not have the newer code of their beta. They get the fix with the next beta from `main`.

If `main` is ready for stable, skip the branch: cut a beta from `main` and promote it.

## Replace a bad beta

If a beta has a bug, publish a newer beta with the fix (or with the bad change reverted). Players on the bad beta update to the new one.

Never delete a release that players may be on. Players need a newer release to move to, and the links in older release notes stop working. You can edit the notes of the bad beta to say which release replaces it.

## Release notes

The workflow publishes a release with a template body. Replace it with the real notes. Follow the [release-notes skill](.agents/skills/release-notes/SKILL.md):

- A beta's notes start with a line saying it is a beta.
- A beta compares against the previous release of either kind.
- A stable release compares against the previous stable release, so its notes cover the betas in between.
