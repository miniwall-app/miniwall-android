# Contributing to Miniwall for Android

Thanks for helping. This repository is one of the open-source Miniwall SDKs (MIT). Miniwall renders screens (paywalls, upgrade prompts, onboarding, surveys) that you design in the Miniwall dashboard; this SDK shows them in your app.

## What we welcome

- Bug reports with a way to reproduce them.
- Fixes for bugs and small improvements, with a test.
- Documentation and typo fixes.

For anything larger (a new option, a new API, a change to behavior), **open an issue first** and wait for a reply. Large pull requests that arrive without a discussion are likely to be closed, however good the code is.

## What we do not take

- Changes to how screens look or are laid out. The drawing rules are shared by every Miniwall SDK and are decided by the maintainers; open an issue describing the difference you see.
- Support for payments or products. Miniwall only renders; your own checkout stays yours.

## Who reviews

The maintainer reviews every pull request. We aim to reply within 7 days; this is a goal, not a promise. Until the repositories become the only source of truth, accepted changes may be applied from an internal copy, so your commit can land rewritten (we keep you as author).

## Before you open a pull request

1. Fork the repository and branch from `main`.
2. Run the tests (`./gradlew :core:test`). They must pass.
3. Add or update a test for the behavior you change.
4. Keep the pull request to one goal. Do not mix refactors with fixes.
5. **Sign off every commit** (`git commit -s`). That adds `Signed-off-by: Your Name <you@example.com>` and states you wrote the change or have the right to submit it under the [Developer Certificate of Origin](https://developercertificate.org/). A check fails the pull request without it.
6. Write commit messages as `type(scope): what changed` (for example `fix(core): ignore an empty placement`).

## Public API

The public API of the SDK is stable on purpose. A change that breaks it needs a clear reason, a note in `CHANGELOG`, and the maintainer's agreement before you write it.

## Conduct and security

Be kind: see [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md). Report security problems privately: see [SECURITY.md](SECURITY.md). Never put a secret or an API key in an issue or a pull request.
