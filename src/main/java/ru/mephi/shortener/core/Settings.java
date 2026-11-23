package ru.mephi.shortener.core;

import java.time.Duration;

public record Settings(Duration ttl, int defaultLimit, int maxLimit, Duration cleanupInterval) {
    public Settings {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("link.ttl должен быть положительным");
        }
        if (defaultLimit < 1 || defaultLimit > maxLimit) {
            throw new IllegalArgumentException("Требуется 1 <= link.default-click-limit <= link.max-click-limit");
        }
        if (cleanupInterval.compareTo(Duration.ofMillis(1)) < 0 || cleanupInterval.compareTo(ttl) > 0) {
            throw new IllegalArgumentException("cleanup.interval должен быть от 1 мс до link.ttl");
        }
        try {
            ttl.toMillis();
            cleanupInterval.toMillis();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Слишком большая длительность в конфигурации", ex);
        }
    }
}
