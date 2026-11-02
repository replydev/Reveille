#!/usr/bin/env bash
# Semantic versioning from Conventional Commits since the last vX.Y.Z tag.
#
#   scripts/semver.sh next    # prints the next version (e.g. 1.4.0), or nothing if no release is due
#   scripts/semver.sh notes   # prints Markdown release notes for the commits since the last tag
#
# Bumps: "type!:" or a "BREAKING CHANGE:" footer → major; "feat" → minor; "fix" / "perf" → patch.
# Other types (docs, chore, ci, test, refactor, style, build) do not trigger a release.
set -euo pipefail

last_tag() {
  git describe --tags --abbrev=0 --match 'v[0-9]*.[0-9]*.[0-9]*' 2>/dev/null || true
}

commit_range() {
  local tag
  tag="$(last_tag)"
  if [[ -n "$tag" ]]; then echo "$tag..HEAD"; else echo "HEAD"; fi
}

# One record per commit: "<subject>\x1f<body>\x1e"
commits() {
  git log --format='%s%x1f%b%x1e' "$(commit_range)"
}

bump_level() {
  local level=0 subject body
  while IFS=$'\x1f' read -r -d $'\x1e' subject body; do
    subject="${subject#$'\n'}"
    if [[ "$subject" =~ ^[a-z]+(\([^\)]*\))?!: ]] || [[ "$body" == *"BREAKING CHANGE:"* ]] || [[ "$body" == *"BREAKING-CHANGE:"* ]]; then
      level=3
    elif [[ "$subject" =~ ^feat(\([^\)]*\))?: ]]; then
      (( level < 2 )) && level=2
    elif [[ "$subject" =~ ^(fix|perf)(\([^\)]*\))?: ]]; then
      (( level < 1 )) && level=1
    fi
  done < <(commits)
  echo "$level"
}

next_version() {
  local tag current major minor patch level
  tag="$(last_tag)"
  current="${tag#v}"
  [[ -z "$current" ]] && current="0.0.0"
  IFS=. read -r major minor patch <<<"$current"
  level="$(bump_level)"
  case "$level" in
    3) echo "$((major + 1)).0.0" ;;
    2) echo "$major.$((minor + 1)).0" ;;
    1) echo "$major.$minor.$((patch + 1))" ;;
    *) ;; # nothing releasable
  esac
}

release_notes() {
  local subject body breaking="" features="" fixes="" perf=""
  while IFS=$'\x1f' read -r -d $'\x1e' subject body; do
    subject="${subject#$'\n'}"
    local line="- ${subject}"
    if [[ "$subject" =~ ^[a-z]+(\([^\)]*\))?!: ]] || [[ "$body" == *"BREAKING CHANGE:"* ]] || [[ "$body" == *"BREAKING-CHANGE:"* ]]; then
      breaking+="$line"$'\n'
    elif [[ "$subject" =~ ^feat(\([^\)]*\))?: ]]; then
      features+="$line"$'\n'
    elif [[ "$subject" =~ ^fix(\([^\)]*\))?: ]]; then
      fixes+="$line"$'\n'
    elif [[ "$subject" =~ ^perf(\([^\)]*\))?: ]]; then
      perf+="$line"$'\n'
    fi
  done < <(commits)
  [[ -n "$breaking" ]] && printf '## Breaking changes\n\n%s\n' "$breaking"
  [[ -n "$features" ]] && printf '## Features\n\n%s\n' "$features"
  [[ -n "$fixes" ]] && printf '## Fixes\n\n%s\n' "$fixes"
  [[ -n "$perf" ]] && printf '## Performance\n\n%s\n' "$perf"
  return 0
}

case "${1:-}" in
  next) next_version ;;
  notes) release_notes ;;
  *) echo "usage: $0 next|notes" >&2; exit 2 ;;
esac
