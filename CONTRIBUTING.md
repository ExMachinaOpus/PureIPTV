# Contributing to PureIPTV

Thanks for helping. PureIPTV is, and will stay, free software under the
[GNU General Public License, version 3 or later](LICENSE).

## Licence of contributions

By submitting a contribution you agree that it is licensed under GPL-3.0-or-later, the same
licence as the rest of the project. There is no contributor licence agreement and no copyright
assignment: you keep the copyright on your work, and nobody (including the maintainers) can take
the project closed.

Please only submit code you wrote yourself or that comes from a GPL-3.0-compatible source
(e.g. Apache-2.0, MIT, BSD), and say where it comes from. New dependencies must be GPL-3.0
compatible too.

Signing off your commits is welcome (`git commit -s`). It adds a `Signed-off-by` line certifying
the [Developer Certificate of Origin](https://developercertificate.org/): that you have the right
to submit the work under the project's licence.

## Before you start

- Read [AGENTS.md](AGENTS.md): the product rules (zero-config, remote-first, plain-language
  errors) and code conventions apply to everyone, not only coding agents.
- Design decisions and their reasons are in [dev/architecture.md](dev/architecture.md).
- For anything bigger than a fix, open an issue first so we can agree on the approach.

## Pull requests

- Work on a branch from `master` and keep each pull request to one change.
- Run `./gradlew test` and make sure it passes.
- UI changes must work with a remote only (D-pad, OK, Back) on the Google TV emulator; include
  screenshots. See "Definition of done" in AGENTS.md.
- App text goes in `strings.xml`, in British English.
- Never include real playlists, provider URLs, credentials or personal details in code, tests,
  screenshots or logs.
