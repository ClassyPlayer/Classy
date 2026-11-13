---
name: release-notes
description: >-
  Draft, audit, and update GitHub Release descriptions and changelogs for
  ClassyPlayer/Classy using the gh CLI and Humanizer writing rules.
  Use this skill whenever preparing a new release, updating existing GitHub
  Releases, or writing user-facing changelog entries for Classy.
---

# Classy Release Notes & Changelog Guide

Use this skill to write or update GitHub Release descriptions on `ClassyPlayer/Classy` so every release starts with a human-readable changelog focused on user impact and features.

## Release Description Structure

Every GitHub release body must follow this exact structure:

1. **Human-readable changelog first**:
   - For a beta (a GitHub pre-release, tag `YYYYMMDD-beta` or `YYYYMMDD-beta.N`), the first line says it is a beta, for example: `This is a beta release. To get it on the player, choose Settings > General > System Update > Update Channel > Beta.` The bullets follow after a blank line.
   - Otherwise, start immediately with the changelog bullets (or a short one-sentence overview for major milestone releases, followed by bullets).
   - Do **not** include a top-level `### Classy Release` heading or "Automated build with full support..." boilerplate.
   - Focus on what changed for the user (features, UI changes, audio/hardware behavior, bug fixes) with light technical context where helpful (for example, `mtkbt` daemon, `libaudiocustparam.so`, `/system/app`, `.y1_thumbs_cache`).
2. **Release assets table**:
   - Use sentence case with no emoji (`#### Release assets`).
   - Keep file names in inline code (`Classy-<TAG>.apk`, `update.json`, `rom_y2.zip`, `rom_y1_type_a.zip`, `rom_y1_type_b.zip`, `SHA256SUMS.txt`) without bold decoration on every cell.
3. **Full changelog link**:
   - End with `Full changelog: https://github.com/ClassyPlayer/Classy/compare/<PREV_TAG>...<TAG>`.
   - **Never** include the `Flashing Instructions (SP Flash Tool)` section or decorative `---` horizontal rules.

### Template

```markdown
- <Bullet 1: user-facing feature or fix>
- <Bullet 2: user-facing feature or fix>

#### Release assets

| File | Target device | Notes |
| :--- | :--- | :--- |
| `Classy-<TAG>.apk` | All devices (Y1 / Y2) | Standalone launcher APK (sideload or install) |
| `update.json` | All devices | OTA update metadata for in-app updater |
| `rom_y2.zip` | Innioasis Y2 (MT6582) | Complete flashable ROM with dual-storage, watchdog & clickwheel keylayout |
| `rom_y1_type_a.zip` | Innioasis Y1 (Type A) | Flashable ROM for Y1 players with original firmware 2.0.0 or higher |
| `rom_y1_type_b.zip` | Innioasis Y1 (Type B) | Flashable ROM for Y1 players with original firmware below 2.0.0 |
| `SHA256SUMS.txt` | All files | SHA-256 integrity checksums |

Full changelog: https://github.com/ClassyPlayer/Classy/compare/<PREV_TAG>...<TAG>
```

## Tags and the compare range

Tags are based on the build date. The first build of a day has no number: `20261114` (stable) or `20261114-beta` (beta). From the second build of that day there is a number, from one counter shared by both channels: `20261114-vN` (stable) or `20261114-beta.N` (beta). Older tags also include `YYYYMMDD-build.N`. The `y1-exp.N` pre-releases are hand-made test builds: never use them as `<PREV_TAG>`.

Choose `<PREV_TAG>` by the kind of release:

- A beta compares against the previous release of either kind (beta or stable).
- A stable release compares against the previous stable release, so its notes cover all the betas in between. Write them for players who skipped the betas: describe the end result, not each beta's fixes to features that were new in those betas.

A promoted stable release is a new build of the same commit as its beta, so `git log <PREV_STABLE>..<STABLE_TAG>` already covers the betas. See [RELEASING.md](../../../RELEASING.md) for the release process.

## Humanizer Writing Rules

Apply the [blader/humanizer](https://github.com/blader/humanizer/raw/refs/heads/main/SKILL.md) guidelines to every release description:

1. **No bold labels on list items (§19)**: Do not start bullets with `- **Feature Name:** ...` or bold random words for decoration. Write normal sentences in each bullet.
2. **Vary sentence openings (§7)**: Do not start every bullet with a past-tense verb (`Added...`, `Fixed...`, `Improved...`). Mix subject-first openings (`The About screen now...`, `FM Radio remembers...`, `Volume uses...`) with action openings.
3. **No em dashes or en dashes (§8)**: Do not use `—`, `–`, or spaced double hyphens (` -- `) in prose. Use commas, periods, colons, or parentheses instead. (Hyphens inside inline code, paths, or UI quotes like `"Connected - Eject before disconnecting"` are fine.)
4. **No "not X, but Y" contrasts (§1) or one-line closers (§2)**: State what changed directly without rhetorical contrast or summary taglines.
5. **No AI watch words (§12-§18)**: Avoid `additionally`, `align with`, `bolstered`, `crucial`, `deep dive`, `delve`, `enduring`, `enhance`, `featuring`, `boasts`, `serves as`, `offers`, `garner`, `highlight`, `interplay`, `intricate`, `key` (adjective), `landscape`, `meticulous`, `pivotal`, `quietly`, `robust` (figurative), `seamless`, `showcase`, `tapestry`, `testament`, `underscore`, `valuable`, `vibrant`, and shallow `-ing` riders (`, ensuring...`, `, highlighting...`, `, reflecting...`).
6. **Decorative formatting (§20-§21)**: Use sentence-case headings (`#### Release assets`), no emojis in headings or bullets, no horizontal rules (`---`), and straight quotes (`"`).
7. **Strict factual accuracy**: Every detail must come from the git commits in the tag range. Never invent causes, metrics, or behavior.

## Workflow for Updating a New Release

Note: Triggering the **Build & Release** workflow manually from GitHub Actions (or pushing a release tag) publishes a pre-release (`channel: beta`, the default) or a release marked Latest (`channel: stable`). The release body comes from a static template that still contains the legacy `Flashing Instructions` block unless overwritten. Merging to `main` no longer deploys a release automatically. After a release is published (or when preparing release notes):

1. **List releases and find the tag range** (pick `<PREV_TAG>` as described in [Tags and the compare range](#tags-and-the-compare-range); `gh release list` marks betas as `Pre-release` and the newest stable release as `Latest`):
   ```bash
   gh release list --repo ClassyPlayer/Classy --limit 20
   git fetch --tags origin
   git log --format="- %h %s%n%w(0,4,4)%b" <PREV_TAG>..<NEW_TAG>
   ```
2. **Inspect commits with short messages**:
   For any commit that only has a one-line subject, inspect its diff so you capture the real user impact:
   ```bash
   git show --stat <COMMIT_SHA>
   git show <COMMIT_SHA> -- app/src/main/assets/languages/english.json
   ```
3. **Draft and audit the release notes**:
   - Write the release body using the template above.
   - Check against the Humanizer rules (no `—`/`–`, no `- **Label:**`, varied bullet openings, no AI watch words, no `Flashing Instructions`).
   - Compare against past examples in [release-history.md](./references/release-history.md).
4. **Update the GitHub Release**:
   ```bash
   gh release edit <NEW_TAG> --repo ClassyPlayer/Classy --notes-file /tmp/release_notes.md
   rm -f /tmp/release_notes.md
   ```
5. **Verify**:
   ```bash
   gh release view <NEW_TAG> --repo ClassyPlayer/Classy
   ```
