#!/usr/bin/env bash
# Works out the tag, version and channel of a release build.
#
# Input only through the environment (never through the script text, so a crafted tag cannot run commands):
#   INPUT_TAG      the workflow_dispatch tag_name input (may be empty)
#   INPUT_CHANNEL  the workflow_dispatch channel input: beta, stable or empty (push and pull_request runs)
#   EVENT_NAME     github.event_name
#   REF_TYPE       github.ref_type (branch or tag)
#   REF_NAME       github.ref_name
#   DATE           optional YYYYMMDD override of today's UTC date (tests)
#   EXISTING_TAGS  optional list of existing tags, separated by spaces or newlines (tests). When it is
#                  unset, the tags come from git (after a fetch from origin). Set but empty means no tags.
#
# Prints key=value lines: tag_name, version_name, version_code, release_name, apk_name, channel.
#
# Tag scheme (the app compares versions by date, then by the first number in the suffix):
#   first build of a day:   20261114 (stable)     20261114-beta (beta)
#   later builds that day:  20261114-vN (stable)  20261114-beta.N (beta)
# N comes from one counter shared by both channels: a plain date tag or DATE-beta counts as 1,
# -vN, -build.N and -beta.N count as N, and the next build takes the highest + 1.
# versionCode = date + 2 digits of N (00 when there is no number).
set -euo pipefail

DEFAULT_CODE="150"

fail() {
  echo "::error::$*" >&2
  exit 1
}

case "${INPUT_CHANNEL:-}" in
  "" | beta | stable) ;;
  *) fail "Unknown channel '${INPUT_CHANNEL}': use beta or stable" ;;
esac

# Channel of a tag: -beta or -beta.N at the end is beta, anything else is stable.
channel_of_tag() {
  if [[ "$1" =~ -beta(\.[0-9]+)?$ ]]; then echo beta; else echo stable; fi
}

# versionCode of a tag: its first 8-digit date + 2 digits of N (from -vN or -beta.N, else 00).
version_code_of_tag() {
  local tag="$1" date rev=""
  date=$(grep -oE '[0-9]{8}' <<<"$tag" | head -1 || true)
  if [ -z "$date" ]; then
    echo "$DEFAULT_CODE"
    return
  fi
  if [[ "$tag" =~ -v([0-9]+)$ ]] || [[ "$tag" =~ -beta\.([0-9]+)$ ]]; then
    rev="${BASH_REMATCH[1]}"
  fi
  printf '%s%02d\n' "$date" "$((10#${rev:-0}))"
}

# Release name of a tag in the date scheme; any other tag is used as it is.
release_name_of_tag() {
  local tag="$1"
  if [[ "$tag" =~ ^([0-9]{8})$ ]]; then
    echo "${BASH_REMATCH[1]}"
  elif [[ "$tag" =~ ^([0-9]{8})-v([0-9]+)$ ]]; then
    echo "${BASH_REMATCH[1]} (v${BASH_REMATCH[2]})"
  elif [[ "$tag" =~ ^([0-9]{8})-beta$ ]]; then
    echo "${BASH_REMATCH[1]} (beta)"
  elif [[ "$tag" =~ ^([0-9]{8})-beta\.([0-9]+)$ ]]; then
    echo "${BASH_REMATCH[1]} (beta ${BASH_REMATCH[2]})"
  else
    echo "$tag"
  fi
}

check_tag_chars() {
  # Keeps the outputs one line each and the tag usable in file names and URLs.
  [[ "$1" =~ ^[A-Za-z0-9._+-]+$ ]] || fail "Tag '$1' has characters other than letters, digits, '.', '_', '+' and '-'"
}

EXPLICIT_TAG=""
if [ -n "${INPUT_TAG:-}" ]; then
  EXPLICIT_TAG="$INPUT_TAG"
elif [ "${EVENT_NAME:-}" = "push" ] && [ "${REF_TYPE:-}" = "tag" ]; then
  # Only a pushed tag names the release. A manual run started from a tag ref builds a new tag,
  # which is how a beta is promoted: run again from the beta tag with channel stable.
  EXPLICIT_TAG="${REF_NAME:-}"
fi

if [ -n "$EXPLICIT_TAG" ]; then
  check_tag_chars "$EXPLICIT_TAG"
  TAG_NAME="$EXPLICIT_TAG"
  CHANNEL=$(channel_of_tag "$TAG_NAME")
  if [ -n "${INPUT_CHANNEL:-}" ] && [ "$INPUT_CHANNEL" != "$CHANNEL" ]; then
    fail "Tag '$TAG_NAME' is a $CHANNEL tag, but the channel is '$INPUT_CHANNEL'. Beta tags end in -beta or -beta.N; pick the matching channel or leave the tag blank."
  fi
else
  CHANNEL="${INPUT_CHANNEL:-stable}"
  DAY="${DATE:-$(date -u +'%Y%m%d')}"
  [[ "$DAY" =~ ^[0-9]{8}$ ]] || fail "DATE '$DAY' is not YYYYMMDD"

  if [ -n "${EXISTING_TAGS+set}" ]; then
    TAGS="$EXISTING_TAGS"
  else
    git fetch --tags origin >&2 || true
    TAGS=$(git tag -l "${DAY}*")
  fi

  HIGHEST=0
  for tag in $TAGS; do
    n=""
    if [[ "$tag" =~ ^${DAY}(-beta)?$ ]]; then
      n=1
    elif [[ "$tag" =~ ^${DAY}-(v|build\.|beta\.)([0-9]+)$ ]]; then
      n=$((10#${BASH_REMATCH[2]}))
    fi
    if [ -n "$n" ] && [ "$n" -gt "$HIGHEST" ]; then
      HIGHEST="$n"
    fi
  done

  if [ "$HIGHEST" -eq 0 ]; then
    SUFFIX=""
    [ "$CHANNEL" = beta ] && SUFFIX="-beta"
  else
    REV=$((HIGHEST + 1))
    [ "$REV" -le 99 ] || fail "More than 99 builds on $DAY: the versionCode has only 2 digits for the build number"
    if [ "$CHANNEL" = beta ]; then SUFFIX="-beta.${REV}"; else SUFFIX="-v${REV}"; fi
  fi
  TAG_NAME="${DAY}${SUFFIX}"
fi

echo "tag_name=${TAG_NAME}"
echo "version_name=${TAG_NAME}"
echo "version_code=$(version_code_of_tag "$TAG_NAME")"
echo "release_name=$(release_name_of_tag "$TAG_NAME")"
echo "apk_name=Classy-${TAG_NAME}.apk"
echo "channel=${CHANNEL}"
