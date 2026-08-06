# BlockLog

Lightweight block interaction logging plugin.

### Download

A download is not yet available as the plugin remains in experimental state.

You may build and run it yourself, but no guarantee of support for the current sqlite schema in future versions is guaranteed.

### Motivation

The standard block logging plugins have caused me grief perf-wise and with strange sqlite buffering and transaction errors. Either resulting in degraded perf on the server or failure to reliably log events.

### Objectives

To make a simple and lightweight plugin that doesn't implement an entire suite of stuff undesired.

### Features

- Logs creation and destruction of blocks, including explosions.
- Logs block transactions.
- Logging of block interaction, ie. opening and closing gates, chests.
- Inspection command with `/bkl i`.
- Rollback command has preview, before confirmation.
- Lightweight and straightforward. Should cause no performance degradation or blockage of main thread.

### User-facing TODO

- Logging block modifications caused by mods and plugins (ie. WorldEdit, that stuff that cuts down trees automatically, etc)
- Logging events of other types (piston, mobs).

### Dev-facing TODO

- Come up with something to simplify sending messages because color codes suck.
- I feel the use of SQLite is pretty bulletproof but more error checks are always good.
- Find potential hot paths and fix them. Performance and logging reliability are tied for #1
- Find a way to properly stress test this. How do I cause realistic 100+ player load without a server with 100+ players? No idea yet.
- Test suite.

### Known issues

- Container transactions: Currently transactions are related to a block, not a container. So you may have to check both blocks of a double chest, for example.
- Not known, but `/bkl rollback` is experimental.

### Install

- Drag the .jar into your plugins folder.
- Set up permissions if using those (`blocklog.use`, `blocklog.inspect`, `blocklog.rollback`)
- Done

### Rollback

1. Run `/bkl rollback preview <playerName> <hours> <radius>` from the center of the area.
2. Review the event, chunk, and unsupported-block counts.
3. Run `/bkl rollback confirm <token>` within 60 seconds.

Use `/bkl rollback status` for progress and `/bkl rollback cancel` to stop your rollback. Tile entities and multi-block structures are reported and skipped because restoring them without complete state could corrupt or duplicate data. Rollback database queries and chunk loading run asynchronously; world changes are time-budgeted on the server thread.

### Build security

The build uses exact direct dependency versions, rejects dynamic or changing versions, supports Gradle dependency locking, treats Java compiler warnings as errors, produces reproducible jars, and generates a CycloneDX SBOM at `build/reports/bom.cdx.json`. CI runs on Java 25, validates the Gradle wrapper, executes checks, and scans dependencies with OSV. GitHub Actions are pinned to immutable commits, and Dependabot waits at least three days before proposing non-security updates.

Dependency lock and verification metadata must be regenerated through the repository's required safe package wrapper whenever dependencies change:

```text
sfw ./gradlew --write-locks --write-verification-metadata sha256 help
```

### Contribution

If you find issues please create an issue and describe it to your best of your ability.

If you wish to contribute code, write it and open a pull request.

No guarantees of acceptance, so if you wish to implement anything new please open an issue and ask first. I don't want to accept features onboard that I am not interested in keeping down the line, sorry.

### Licence

Licenced under WTFPL 2.0

```
           DO WHAT THE FUCK YOU WANT TO PUBLIC LICENSE
                   Version 2, December 2004

Copyright (C) 2004 Sam Hocevar <sam@hocevar.net>

Everyone is permitted to copy and distribute verbatim or modified
copies of this license document, and changing it is allowed as long
as the name is changed.

           DO WHAT THE FUCK YOU WANT TO PUBLIC LICENSE
  TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION

 0. You just DO WHAT THE FUCK YOU WANT TO.
```
