# BlockLog

Lightweight block interaction logging plugin.

### Download

A download is not yet available as the plugin remains in experimental state.

You may build and run it yourself, no guarantee of support for the current sqlite schema in future versions is guaranteed.

### Motivation

The standard block logging plugins have caused me grief perf-wise and with strange sqlite buffering and transaction errors. Either resulting in degraded perf on the server or failure to reliably log events.

### Objectives

To make a simple and lightweight plugin that doesn't implement an entire suite of stuff undesired.

### Features

- Logs creation and destruction of blocks, including explosions.
- Logs block transactions.
- Logging of block interaction, ie. opening and closing gates, chests.
- Inspection command with `/blk i`.
- Rollback with `/blk rollback` command. Experimental.
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
- Not known, but `/blk rollback` is experimental.

### Install

- Drag the .jar into your plugins folder.
- Set up permissions if using those (`blocklog.inspect`, `blocklog.rollback`)
- Done

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
