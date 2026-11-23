package ru.mephi.shortener.core;

import java.time.Instant;
import java.util.UUID;

public record Notice(UUID owner, String code, Reason reason, Instant at) {
    public enum Reason {
        LIMIT_REACHED, EXPIRED
    }
}
