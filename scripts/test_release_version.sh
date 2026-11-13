#!/usr/bin/env bash
# Table tests for scripts/release_version.sh. Run: bash scripts/test_release_version.sh
set -uo pipefail

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/release_version.sh"
PASS=0
FAIL=0
ERR_FILE=$(mktemp)
trap 'rm -f "$ERR_FILE"' EXIT

# Each row: description | environment (space-separated NAME=value, ',' between tags in EXISTING_TAGS)
#           | expected "tag_name;version_code;release_name;channel", or FAIL for a non-zero exit.
# Every row runs with DATE=20261114 and EXISTING_TAGS empty unless the row sets them.
CASES=(
  "dispatch beta, no tags that day|EVENT_NAME=workflow_dispatch INPUT_CHANNEL=beta REF_TYPE=branch REF_NAME=main|20261114-beta;2026111400;20261114 (beta);beta"
  "dispatch stable, no tags that day|EVENT_NAME=workflow_dispatch INPUT_CHANNEL=stable REF_TYPE=branch REF_NAME=main|20261114;2026111400;20261114;stable"
  "dispatch beta after DATE-beta|EVENT_NAME=workflow_dispatch INPUT_CHANNEL=beta EXISTING_TAGS=20261114-beta|20261114-beta.2;2026111402;20261114 (beta 2);beta"
  "dispatch stable after DATE-beta and -beta.2|EVENT_NAME=workflow_dispatch INPUT_CHANNEL=stable EXISTING_TAGS=20261114-beta,20261114-beta.2|20261114-v3;2026111403;20261114 (v3);stable"
  "dispatch beta after plain DATE|EVENT_NAME=workflow_dispatch INPUT_CHANNEL=beta EXISTING_TAGS=20261114|20261114-beta.2;2026111402;20261114 (beta 2);beta"
  "dispatch stable after DATE and -v2|EVENT_NAME=workflow_dispatch INPUT_CHANNEL=stable EXISTING_TAGS=20261114,20261114-v2|20261114-v3;2026111403;20261114 (v3);stable"
  "dispatch beta after -build.4|EVENT_NAME=workflow_dispatch INPUT_CHANNEL=beta EXISTING_TAGS=20261114,20261114-build.4|20261114-beta.5;2026111405;20261114 (beta 5);beta"
  "tags of other days are ignored|EVENT_NAME=workflow_dispatch INPUT_CHANNEL=stable EXISTING_TAGS=20261113,20261113-v7,20261115-beta|20261114;2026111400;20261114;stable"
  "dispatch stable reaching 10|EVENT_NAME=workflow_dispatch INPUT_CHANNEL=stable EXISTING_TAGS=20261114,20261114-beta.9|20261114-v10;2026111410;20261114 (v10);stable"
  "branch push uses stable names|EVENT_NAME=push REF_TYPE=branch REF_NAME=main EXISTING_TAGS=20261114-beta|20261114-v2;2026111402;20261114 (v2);stable"
  "pull request uses stable names|EVENT_NAME=pull_request REF_TYPE=branch REF_NAME=42/merge|20261114;2026111400;20261114;stable"
  "dispatch from a beta tag ref promotes to a new stable tag|EVENT_NAME=workflow_dispatch INPUT_CHANNEL=stable REF_TYPE=tag REF_NAME=20261114-beta EXISTING_TAGS=20261114-beta|20261114-v2;2026111402;20261114 (v2);stable"
  "dispatch from a tag ref with channel beta makes a new beta|EVENT_NAME=workflow_dispatch INPUT_CHANNEL=beta REF_TYPE=tag REF_NAME=20261114|20261114-beta;2026111400;20261114 (beta);beta"
  "pushed stable tag|EVENT_NAME=push REF_TYPE=tag REF_NAME=20261102|20261102;2026110200;20261102;stable"
  "pushed -vN tag|EVENT_NAME=push REF_TYPE=tag REF_NAME=20261102-v4|20261102-v4;2026110204;20261102 (v4);stable"
  "pushed beta tag|EVENT_NAME=push REF_TYPE=tag REF_NAME=20261102-beta|20261102-beta;2026110200;20261102 (beta);beta"
  "pushed -beta.N tag|EVENT_NAME=push REF_TYPE=tag REF_NAME=20261102-beta.3|20261102-beta.3;2026110203;20261102 (beta 3);beta"
  "pushed non-date tag falls back to 150|EVENT_NAME=push REF_TYPE=tag REF_NAME=v1.6.0|v1.6.0;150;v1.6.0;stable"
  "input tag beta with channel beta|EVENT_NAME=workflow_dispatch INPUT_CHANNEL=beta INPUT_TAG=20261110-beta.2|20261110-beta.2;2026111002;20261110 (beta 2);beta"
  "input tag stable with channel stable|EVENT_NAME=workflow_dispatch INPUT_CHANNEL=stable INPUT_TAG=20261110-v5|20261110-v5;2026111005;20261110 (v5);stable"
  "input tag wins over the tag ref|EVENT_NAME=workflow_dispatch INPUT_CHANNEL=stable INPUT_TAG=20261110 REF_TYPE=tag REF_NAME=20261114-beta|20261110;2026111000;20261110;stable"
  "input stable tag with channel beta fails|EVENT_NAME=workflow_dispatch INPUT_CHANNEL=beta INPUT_TAG=20261110|FAIL"
  "input beta tag with channel stable fails|EVENT_NAME=workflow_dispatch INPUT_CHANNEL=stable INPUT_TAG=20261110-beta|FAIL"
  "unknown channel fails|EVENT_NAME=workflow_dispatch INPUT_CHANNEL=nightly|FAIL"
  "tag with shell characters fails|EVENT_NAME=workflow_dispatch INPUT_CHANNEL=stable INPUT_TAG=a\$(id)b|FAIL"
)

for row in "${CASES[@]}"; do
  IFS='|' read -r desc envs expected <<<"$row"

  run_env=(env -i "PATH=$PATH" "DATE=20261114" "EXISTING_TAGS=")
  for kv in $envs; do
    [[ "$kv" == EXISTING_TAGS=* ]] && kv="${kv//,/ }"
    run_env+=("$kv")
  done

  out=$("${run_env[@]}" bash "$SCRIPT" 2>"$ERR_FILE") && status=0 || status=$?
  err=$(cat "$ERR_FILE")

  if [ "$expected" = "FAIL" ]; then
    if [ "$status" -ne 0 ]; then
      PASS=$((PASS + 1))
    else
      FAIL=$((FAIL + 1))
      echo "FAIL: $desc: expected a failure, got:"
      echo "$out" | sed 's/^/    /'
    fi
    continue
  fi

  get() { sed -n "s/^$1=//p" <<<"$out"; }
  IFS=';' read -r e_tag e_code e_name e_channel <<<"$expected"
  want="tag_name=$e_tag
version_name=$e_tag
version_code=$e_code
release_name=$e_name
apk_name=Classy-$e_tag.apk
apk_name_y1=Classy-$e_tag-y1.apk
channel=$e_channel"

  if [ "$status" -eq 0 ] && [ "$out" = "$want" ]; then
    PASS=$((PASS + 1))
  else
    FAIL=$((FAIL + 1))
    echo "FAIL: $desc (exit $status)"
    diff <(echo "$want") <(echo "$out") | sed 's/^/    /'
    [ -n "$err" ] && echo "$err" | sed 's/^/    stderr: /'
  fi
done

echo "release_version.sh: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
