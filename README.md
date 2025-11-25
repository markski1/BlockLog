# BlockLog

Lightweight block interaction logging plugin.

### Download

A download is not yet available as to run this plugin in its current state is not yet advisable.

You may build and run it yourself, no guarantee of support for the current sqlite schema in future versions is guaranteed.

### Motivation

The standard block logging plugins have caused me grief perf-wise and with strange sqlite buffering and transaction errors. Either resulting in degraded perf on the server or failure to reliably log events.

### Objectives

To make a basic and lightweight plugin that doesn't implement an entire suite of stuff undesired.

### Currently supported

- Logging creation and destruction of blocks.
- Viewing them with the `/blk i` command.

### TODO

- Event buffering and graceful transaction recovery in case of SQLite errors.
- Logging events of other types (container interactions, explosion, piston, mobs).
- Rollback functionality, given a playername and a range.
- Test suite.
- MAYBE: Logging of interactions to chests and other containers.

### Contribution

- If you find an issue, create an issue and describe it non-vaguely enough.
- If you have a suggestion, create an issue and describe it non-vaguely enough.
- For either issues or suggestions:
  - If you know how to code and are willing to, write your code and create a pull request.
  - If you know how to code but are not willing to, at least try to describe things in a more technical manner.
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
