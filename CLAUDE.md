# termux-app fork

This is a fork of termux/termux-app: an upstream release plus this fork's customizations.
See `custom/README.md` for the customizations and how they are kept.

- Upgrade to a new upstream release with `custom/upgrade-upstream.sh [<tag>]`, which syncs the
  source tree to the upstream tag, keeps the fork-only files in `custom/fork-files.txt` and
  reapplies `custom/patches/`. Resolve patch conflicts, then run `custom/update-patches.sh`.
- After committing a change to a file that also exists upstream, run `custom/update-patches.sh`
  and commit the regenerated patches. Add new fork-only files to `custom/fork-files.txt`.
- Pushing to `master` builds APKs in the `Build` workflow. Publish a release from that build by
  adding `.github/release-notes/<tag>.md` and pushing a `release/<tag>` branch on the same commit.
