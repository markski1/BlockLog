package org.markski.blocklog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

final class Messages {
    private Messages() {}

    static Component error(String message) {
        return Component.text(message, NamedTextColor.RED);
    }

    static Component info(String message) {
        return Component.text(message, NamedTextColor.YELLOW);
    }

    static Component muted(String message) {
        return Component.text(message, NamedTextColor.GRAY);
    }

    static Component success(String message) {
        return Component.text(message, NamedTextColor.GREEN);
    }
}
