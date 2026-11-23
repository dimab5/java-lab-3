package ru.mephi.shortener.core;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record Link(String code, UUID owner, URI destination, Instant expiresAt, int limit, int clicks) {
    public boolean exhausted() {
        return clicks >= limit;
    }

    Link withClicks(int newClicks) {
        return new Link(code, owner, destination, expiresAt, limit, newClicks);
    }

    Link withLimit(int newLimit) {
        return new Link(code, owner, destination, expiresAt, newLimit, clicks);
    }

    Link withDestination(URI newDestination) {
        return new Link(code, owner, newDestination, expiresAt, limit, clicks);
    }
}
