#!/usr/bin/env bash
#
# Regenerate `custom/patches/` from the commits after the last upstream sync commit.
#
# Usage: custom/update-patches.sh
#
# Every commit after the last "Sync source tree to upstream release" commit that changes files
# that also exist upstream becomes one patch. Changes to the fork-only files listed in
# `custom/fork-files.txt` are left out, since `upgrade-upstream.sh` copies those as-is.
#
# Run this after changing fork customizations of upstream files, or after resolving conflicts
# when upgrading to a new upstream release, and commit the result.

set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

sync_commit="$(git log -1 --format=%H --grep='^Sync source tree to upstream release ')"
if [ -z "$sync_commit" ]; then
    echo "No \"Sync source tree to upstream release\" commit found." >&2
    exit 1
fi
echo "Last upstream sync commit: $(git log -1 --format='%h %s' "$sync_commit")"

mapfile -t fork_files < <(grep -vE '^\s*(#|$)' custom/fork-files.txt)
exclude_pathspecs=()
for path in "${fork_files[@]}"; do exclude_pathspecs+=(":(exclude)$path"); done

rm -f custom/patches/*.patch
mkdir -p custom/patches

n=0
for commit in $(git rev-list --reverse --no-merges "$sync_commit"..HEAD -- . "${exclude_pathspecs[@]}"); do
    n=$((n + 1))
    git format-patch --quiet -1 "$commit" --start-number "$n" -o custom/patches -- . "${exclude_pathspecs[@]}"
done

echo "Wrote $n patches to custom/patches:"
ls -1 custom/patches
