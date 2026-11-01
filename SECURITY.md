# Security

Books stores reading data and, optionally, passwords or API keys for your own Komga or OPDS servers. Security problems are taken seriously.

## Reporting a vulnerability

Please **don't open a public issue**. Report it privately through GitHub's [private vulnerability reporting](https://github.com/Dishan18/Books/security/advisories/new) for this repository, with:

- what the problem is and what it lets someone do,
- steps to reproduce it,
- the app version (Progress tab or the APK name) and your Android version.

You'll get a reply as soon as possible. Once a fix is released you'll be credited, if you'd like.

## Supported versions

Only the [latest release](https://github.com/Dishan18/Books/releases/latest) gets fixes. Updating is safe: every release installs over the previous one and keeps your data.

## What's in scope

- Credentials leaking: stored outside the Android Keystore, logged, or sent to the wrong host.
- Files or data from one source reaching another, or the app reading or writing outside its own storage.
- Malicious catalog or source responses (XML, JSON, archives, images) that crash the app, escape the app's storage, or run code.
- Anything that contradicts the privacy promises in the [README](README.md).
