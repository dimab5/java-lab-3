package ru.mephi.shortener.core;

import ru.mephi.shortener.core.Store.Snapshot;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class Shortener {
    @FunctionalInterface
    public interface Browser {
        void browse(URI uri) throws IOException;
    }

    private final Store store;
    private final Settings settings;
    private final Clock clock;
    private Snapshot state;

    public Shortener(Store store, Settings settings, Clock clock) throws IOException {
        this.store = store;
        this.settings = settings;
        this.clock = clock;
        state = store.load();
        expire();
    }

    public synchronized boolean hasOwner(UUID owner) {
        return state.owners().contains(owner);
    }

    public synchronized Link create(UUID owner, String url, Integer limit) throws IOException {
        URI destination = destination(url);
        int clickLimit = limit == null ? settings.defaultLimit() : limit;
        validateLimit(clickLimit);
        Instant now = clock.instant();
        expireAt(now);
        long sequence = Math.incrementExact(state.sequence());
        Snapshot next = state.copy(sequence);
        UUID creator = owner;
        if (creator == null) {
            do {
                creator = UUID.randomUUID();
            } while (!next.owners().add(creator));
        } else {
            requireOwner(creator);
        }
        Link link = new Link(ShortCode.encode(sequence), creator, destination, now.plus(settings.ttl()), clickLimit, 0);
        next.links().put(link.code(), link);
        commit(next);
        return link;
    }

    public synchronized void open(String code, Browser browser) throws IOException {
        Instant now = clock.instant();
        Link link = lookup(code, now);
        if (link.exhausted()) {
            throw new IllegalArgumentException("Ссылка недоступна: лимит переходов исчерпан");
        }
        Snapshot before = state;
        saveChange(link, link.withClicks(link.clicks() + 1), now);
        try {
            browser.browse(link.destination());
        } catch (IOException ex) {
            try {
                commit(before);
            } catch (IOException rollback) {
                rollback.addSuppressed(ex);
                throw new IOException("Браузер не открылся, а отмена учёта перехода не удалась", rollback);
            }
            throw ex;
        }
    }

    public synchronized List<Link> list(UUID owner) throws IOException {
        requireOwner(owner);
        expire();
        return state.links().values().stream()
                .filter(link -> link.owner().equals(owner))
                .sorted(Comparator.comparing(Link::code))
                .toList();
    }

    public synchronized Link setLimit(UUID owner, String code, int limit) throws IOException {
        validateLimit(limit);
        Instant now = clock.instant();
        Link original = ownedLink(owner, code, now);
        if (limit < original.clicks()) {
            throw new IllegalArgumentException("Лимит не может быть меньше числа совершённых переходов");
        }
        return saveChange(original, original.withLimit(limit), now);
    }

    public synchronized Link setDestination(UUID owner, String code, String url) throws IOException {
        URI destination = destination(url);
        Instant now = clock.instant();
        Link original = ownedLink(owner, code, now);
        return saveChange(original, original.withDestination(destination), now);
    }

    public synchronized void delete(UUID owner, String code) throws IOException {
        Link link = ownedLink(owner, code, clock.instant());
        Snapshot next = state.copy(state.sequence());
        next.links().remove(link.code());
        commit(next);
    }

    public synchronized void expire() throws IOException {
        expireAt(clock.instant());
    }

    public synchronized List<Notice> takeNotices(UUID owner) throws IOException {
        requireOwner(owner);
        List<Notice> pending = state.notices().stream().filter(notice -> notice.owner().equals(owner)).toList();
        if (!pending.isEmpty()) {
            Snapshot next = state.copy(state.sequence());
            next.notices().removeIf(notice -> notice.owner().equals(owner));
            commit(next);
        }
        return pending;
    }

    private void expireAt(Instant now) throws IOException {
        List<Link> expired = state.links().values().stream()
                .filter(link -> !link.expiresAt().isAfter(now))
                .sorted(Comparator.comparing(Link::expiresAt).thenComparing(Link::code))
                .toList();
        if (expired.isEmpty()) {
            return;
        }
        Snapshot next = state.copy(state.sequence());
        for (Link link : expired) {
            next.links().remove(link.code());
            next.notices().add(new Notice(link.owner(), link.code(), Notice.Reason.EXPIRED, link.expiresAt()));
        }
        commit(next);
    }

    private Link lookup(String value, Instant now) throws IOException {
        String code = ShortCode.parse(value);
        expireAt(now);
        Link link = state.links().get(code);
        if (link == null) {
            throw new IllegalArgumentException("Ссылка недоступна: она удалена, истекла или её код неизвестен");
        }
        return link;
    }

    private Link ownedLink(UUID owner, String code, Instant now) throws IOException {
        requireOwner(owner);
        Link link = lookup(code, now);
        if (!link.owner().equals(owner)) {
            throw new IllegalArgumentException("Управлять ссылкой может только её владелец");
        }
        return link;
    }

    private void requireOwner(UUID owner) {
        if (owner == null || !hasOwner(owner)) {
            throw new IllegalArgumentException("Укажите свой UUID командой use или создайте первую ссылку");
        }
    }

    private Link saveChange(Link original, Link changed, Instant now) throws IOException {
        Snapshot next = state.copy(state.sequence());
        next.links().put(changed.code(), changed);
        if (!original.exhausted() && changed.exhausted()) {
            next.notices().add(new Notice(changed.owner(), changed.code(), Notice.Reason.LIMIT_REACHED, now));
        }
        commit(next);
        return changed;
    }

    private void commit(Snapshot next) throws IOException {
        store.save(next);
        state = next;
    }

    private void validateLimit(int limit) {
        if (limit < 1 || limit > settings.maxLimit()) {
            throw new IllegalArgumentException("Лимит должен быть от 1 до " + settings.maxLimit());
        }
    }

    private static URI destination(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && uri.getRawUserInfo() == null && uri.getPort() <= 65535
                    && uri.getPort() != 0) {
                return uri;
            }
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("Некорректный URL. Пример: https://example.org/page", ex);
        }
        throw new IllegalArgumentException("Укажите полный URL HTTP(S) без логина и пароля");
    }
}
