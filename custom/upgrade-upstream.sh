#!/usr/bin/env bash
#
# Upgrade this fork to an upstream termux/termux-app release and reapply the fork's customizations.
#
# Usage: custom/upgrade-upstream.sh [<upstream-tag>]
#
# If no tag is given, the latest stable (non pre-release) upstream tag is used.
#
# Steps:
# 1. Commit the source tree of the upstream tag, plus the fork-only files listed in
#    `custom/fork-files.txt` copied from the current commit, as "Sync source tree to upstream
#    release <tag>". History is kept, nothing is force pushed.
# 2. Apply each patch in `custom/patches/` as its own commit with `git am -3`.
#
# If a patch does not apply, `git am` stops so that the conflict can be resolved, then continued
# with `git am --continue`. Afterwards, run `custom/update-patches.sh` so the patches match the
# new upstream source.

set -euo pipefail

UPSTREAM_URL="https://github.com/termux/termux-app.git"

cd "$(git rev-parse --show-toplevel)"

if [ -n "$(git status --porcelain)" ]; then
    echo "The working tree has uncommitted changes, commit or stash them first." >&2
    exit 1
fi

echo "Fetching upstream tags from $UPSTREAM_URL"
git fetch --quiet --tags "$UPSTREAM_URL"

tag="${1:-}"
if [ -z "$tag" ]; then
    # Latest tag in the `vX.Y.Z` format, which excludes pre-releases like `v0.119.0-beta.3`.
    tag="$(git ls-remote --tags --refs --sort=-v:refname "$UPSTREAM_URL" 'v*' \
        | sed 's#.*refs/tags/##' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | head -n 1)"
fi
if ! git rev-parse --quiet --verify "refs/tags/$tag^{commit}" >/dev/null; then
    echo "Upstream tag '$tag' not found." >&2
    exit 1
fi
echo "Upgrading to upstream release $tag ($(git rev-parse --short "$tag^{commit}"))"

old_head="$(git rev-parse HEAD)"
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

# The patches are replaced by the upstream tree below, so keep a copy to apply them from.
cp custom/patches/*.patch "$tmp_dir/"

mapfile -t fork_files < <(grep -vE '^\s*(#|$)' custom/fork-files.txt)

# Replace the tree with the upstream release, then restore the fork-only files.
git read-tree -u --reset "$tag^{commit}"
git checkout "$old_head" -- "${fork_files[@]}"
git commit --quiet -m "Sync source tree to upstream release $tag

Replace the source tree with the contents of termux/termux-app tag
$tag ($(git rev-parse --short "$tag^{commit}")) and keep the fork-only files listed
in \`custom/fork-files.txt\`. Fork customizations of upstream files are
reapplied by the following commits from \`custom/patches/\`."
echo "Committed sync to $tag"

for patch in "$tmp_dir"/*.patch; do
    echo "Applying $(basename "$patch")"
    if ! git am --quiet -3 --committer-date-is-author-date "$patch"; then
        # Keep the patches so the remaining ones can be applied after resolving the conflict.
        trap - EXIT
        cat >&2 <<EOF

Failed to apply $(basename "$patch") on top of upstream $tag.
Resolve the conflicts, \`git add\` the files and run \`git am --continue\`, then apply the
patches after it from $tmp_dir with \`git am -3 <patch>\`.
After all patches are applied, run custom/update-patches.sh to refresh them.
To give up instead, run: git am --abort && git reset --hard $old_head
EOF
        exit 1
    fi
done

echo
echo "Upgraded to upstream $tag with all fork patches applied."
echo "Check the build, then push."
