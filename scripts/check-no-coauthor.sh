#!/bin/sh
# Fails if any commit in the given range carries a Co-Authored-By trailer. CI runs it on pull requests.
# Usage: scripts/check-no-coauthor.sh <base-ref>   (checks <base-ref>..HEAD)
base="${1:?usage: check-no-coauthor.sh <base-ref>}"
bad=$(git log --format='%h %s' -i --grep='^[[:space:]]*co-authored-by:' "$base"..HEAD)
if [ -n "$bad" ]; then
  echo "These commits carry a Co-Authored-By trailer, which this repository does not allow:" >&2
  echo "$bad" >&2
  exit 1
fi
echo "No Co-Authored-By trailers."
