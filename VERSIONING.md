# Versioning and support

The Android SDK is `app.miniwall:sdk` (and `app.miniwall:sdk-core`) on the Miniwall Maven repository (`https://maven.miniwall.app`); the version is the Maven version.

## Version numbers

This package follows [semantic versioning](https://semver.org/).

- **`0.x` (now):** the API may still change. A *minor* bump (`0.1` to `0.2`) can break; a *patch* bump (`0.1.0` to `0.1.1`) never does. Every breaking change is written in `CHANGELOG.md` under the version that makes it.
- **`1.0` and later:** breaking changes only in a major version, announced in `CHANGELOG.md` at least one minor version before, with the old way kept working and marked deprecated.

## What stays compatible

- **The delivery API (`/v1/sdk/...`).** An SDK that is already installed keeps working when the API changes: the API only adds, and keeps answering what old SDKs ask for. A change that cannot be made that way gets a new version of the API (`/v2/...`) and the old one stays up for the time stated below.
- **The screen format (`PaywallData`).** Screens saved today render in later SDKs. An SDK ignores what it does not know instead of failing (a component it cannot draw shows its `fallback`, or nothing).
- **The standard.** Each SDK is tested against a pinned version of [`miniwall-spec`](https://github.com/miniwall-app/miniwall-spec); `COMPATIBILITY.md` there lists which SDK version was tested with which.

## What we support

| | Support |
|---|---|
| Latest release | Fixes for bugs and security problems |
| Previous minor (from `1.0`) | Security fixes for 6 months after the next minor is out |
| `0.x` | Latest release only |
| An API version (`/v1`) | At least 12 months after a newer one is announced |

These are goals, not a contract: this project has one maintainer, and a security problem is always looked at first. See `SECURITY.md`.

## Upgrading

Read `CHANGELOG.md` between your version and the new one. Pin the version you ship; do not float on `latest` in production. If a release breaks you, open an issue with the old and new version numbers.
