# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately through
[GitHub's private vulnerability reporting](https://github.com/wadackel/limenty/security/advisories/new),
not in a public issue or discussion.

A useful report says what an attacker can do, under which conditions (a malicious SSH server, a
hostile network, another app on the device), and how to reproduce it.

Limenty is maintained by one person in their spare time, so there is no guaranteed response time.
Reports are read, and a confirmed issue is fixed on `main`.

## Supported versions

Only the latest commit on `main` is supported. There are no release branches and no prebuilt APKs.

## What the app is designed to protect

[`docs/security.md`](docs/security.md) describes how credentials are stored, how host keys are
checked, how output from the remote is handled, and what is never logged. A report showing that the
app does not do what that document says is in scope.
