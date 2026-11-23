package ru.mephi.shortener.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.mephi.shortener.testutil.MutableClock;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortenerTest {
    private static final String URL = "https://example.org/long/path?topic=java#section";
    private final MutableClock clock = new MutableClock();
    private final MemoryStore store = new MemoryStore();
    private Shortener shortener;

    @BeforeEach
    void setUp() throws IOException {
        shortener = new Shortener(store, new Settings(Duration.ofDays(1), 5, 100, Duration.ofSeconds(1)), clock);
    }

    @Test
    void sameDestinationGetsDistinctCodesForDifferentOwners() throws IOException {
        Link first = shortener.create(null, URL, null);
        Link second = shortener.create(null, URL, null);
        assertNotEquals(first.owner(), second.owner());
        assertNotEquals(first.code(), second.code());
        assertEquals(first.destination(), second.destination());
    }

    @Test
    void repeatedCreationAndListingAreScopedToOwner() throws IOException {
        Link first = shortener.create(null, URL, null);
        Link second = shortener.create(first.owner(), URL, 20);
        shortener.create(null, URL, 1);
        assertNotEquals(first.code(), second.code());
        assertEquals(List.of(first, second), shortener.list(first.owner()));
        assertEquals(5, first.limit());
    }

    @Test
    void neverReusesDeletedCode() throws IOException {
        Link first = shortener.create(null, URL, null);
        shortener.delete(first.owner(), first.code());
        assertNotEquals(first.code(), shortener.create(first.owner(), URL, null).code());
    }

    @Test
    void allowsExactlyTheConfiguredNumberOfOpensAndNotifiesOnce() throws IOException {
        Link link = shortener.create(null, URL, 2);
        List<URI> opened = new ArrayList<>();
        shortener.open(ShortCode.PREFIX + link.code(), opened::add);
        assertTrue(shortener.takeNotices(link.owner()).isEmpty());
        shortener.open(link.code(), opened::add);
        assertThrows(IllegalArgumentException.class, () -> shortener.open(link.code(), opened::add));
        assertEquals(List.of(URI.create(URL), URI.create(URL)), opened);
        assertEquals(List.of(Notice.Reason.LIMIT_REACHED), reasons(link.owner()));
        assertTrue(shortener.takeNotices(link.owner()).isEmpty());
    }

    @Test
    void browserFailureRestoresClickAndNotification() throws IOException {
        Link link = shortener.create(null, URL, 1);
        assertThrows(IOException.class, () -> shortener.open(link.code(), uri -> {
            throw new IOException("Desktop unavailable");
        }));
        assertEquals(0, shortener.list(link.owner()).get(0).clicks());
        assertTrue(shortener.takeNotices(link.owner()).isEmpty());
        shortener.open(link.code(), uri -> { });
        assertEquals(List.of(Notice.Reason.LIMIT_REACHED), reasons(link.owner()));
    }

    @Test
    void storageFailurePreventsBrowserLaunch() throws IOException {
        Link link = shortener.create(null, URL, 1);
        List<URI> opened = new ArrayList<>();
        store.failSave = true;
        assertThrows(IOException.class, () -> shortener.open(link.code(), opened::add));
        assertTrue(opened.isEmpty());
        assertEquals(0, shortener.list(link.owner()).get(0).clicks());
    }

    @Test
    void concurrentOpensCannotExceedLimit() throws Exception {
        Link link = shortener.create(null, URL, 7);
        AtomicInteger opened = new AtomicInteger();
        Callable<Boolean> attempt = () -> {
            try {
                shortener.open(link.code(), uri -> opened.incrementAndGet());
                return true;
            } catch (IllegalArgumentException ex) {
                return false;
            }
        };
        var workers = Executors.newFixedThreadPool(8);
        try {
            var futures = workers.invokeAll(IntStream.range(0, 30).mapToObj(index -> attempt).toList());
            int successful = 0;
            for (var future : futures) {
                if (future.get()) {
                    successful++;
                }
            }
            assertEquals(7, successful);
            assertEquals(7, opened.get());
        } finally {
            workers.shutdownNow();
        }
        assertEquals(List.of(Notice.Reason.LIMIT_REACHED), reasons(link.owner()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT1H", "PT24H"})
    void scheduledCleanupDeletesAtTheTtlBoundary(String duration) throws IOException {
        Duration ttl = Duration.parse(duration);
        Shortener configured = new Shortener(store, new Settings(ttl, 5, 100, Duration.ofSeconds(1)), clock);
        Link link = configured.create(null, URL, null);
        clock.advance(ttl.minusNanos(1));
        configured.expire();
        assertTrue(store.load().links().containsKey(link.code()));
        clock.advance(Duration.ofNanos(1));
        configured.expire();
        assertFalse(store.load().links().containsKey(link.code()));
        assertEquals(List.of(Notice.Reason.EXPIRED), reasonsFrom(configured, link.owner()));
        assertTrue(configured.list(link.owner()).isEmpty());
    }

    @Test
    void openingAnExpiredLinkCannotRaceCleanup() throws IOException {
        Link link = shortener.create(null, URL, 1);
        clock.advance(Duration.ofDays(1));
        List<URI> opened = new ArrayList<>();
        assertThrows(IllegalArgumentException.class, () -> shortener.open(link.code(), opened::add));
        assertTrue(opened.isEmpty());
        assertEquals(List.of(Notice.Reason.EXPIRED), reasons(link.owner()));
    }

    @Test
    void onlyOwnerCanChangeOrDelete() throws IOException {
        Link link = shortener.create(null, URL, null);
        UUID outsider = shortener.create(null, URL, null).owner();
        assertThrows(IllegalArgumentException.class, () -> shortener.setLimit(outsider, link.code(), 10));
        assertThrows(IllegalArgumentException.class, () -> shortener.setDestination(outsider, link.code(), URL));
        assertThrows(IllegalArgumentException.class, () -> shortener.delete(outsider, link.code()));
        assertThrows(IllegalArgumentException.class, () -> shortener.delete(null, link.code()));
        assertEquals(List.of(link), shortener.list(link.owner()));
        shortener.delete(link.owner(), link.code());
        assertTrue(shortener.list(link.owner()).isEmpty());
    }

    @Test
    void raisingLimitReactivatesExhaustedLinkAndKeepsPreviousClicks() throws IOException {
        Link link = shortener.create(null, URL, 1);
        shortener.open(link.code(), uri -> { });
        Link edited = shortener.setLimit(link.owner(), link.code(), 2);
        assertFalse(edited.exhausted());
        assertEquals(1, edited.clicks());
        assertEquals(link.expiresAt(), edited.expiresAt());
        shortener.open(link.code(), uri -> { });
        assertThrows(IllegalArgumentException.class, () -> shortener.setLimit(link.owner(), link.code(), 1));
        assertEquals(List.of(Notice.Reason.LIMIT_REACHED, Notice.Reason.LIMIT_REACHED), reasons(link.owner()));
    }

    @Test
    void loweringLimitToCountBlocksImmediately() throws IOException {
        Link link = shortener.create(null, URL, 5);
        shortener.open(link.code(), uri -> { });
        assertTrue(shortener.setLimit(link.owner(), link.code(), 1).exhausted());
        assertEquals(List.of(Notice.Reason.LIMIT_REACHED), reasons(link.owner()));
    }

    @Test
    void editingDestinationKeepsCodeQuotaAndExpiry() throws IOException {
        Link link = shortener.create(null, URL, 3);
        shortener.open(link.code(), uri -> { });
        String replacement = "http://example.com:8080/new";
        Link edited = shortener.setDestination(link.owner(), link.code(), replacement);
        assertEquals(new Link(link.code(), link.owner(), URI.create(replacement), link.expiresAt(), 3, 1), edited);
        List<URI> opened = new ArrayList<>();
        shortener.open(link.code(), opened::add);
        assertEquals(List.of(URI.create(replacement)), opened);
    }

    @Test
    void outsidersCannotReadOwnersNotifications() throws IOException {
        Link link = shortener.create(null, URL, 1);
        UUID outsider = shortener.create(null, URL, 1).owner();
        shortener.open(link.code(), uri -> { });
        assertTrue(shortener.takeNotices(outsider).isEmpty());
        assertEquals(List.of(Notice.Reason.LIMIT_REACHED), reasons(link.owner()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.org/path", "ftp://example.org", "https:///path", "https://bad host",
        "https://example.org:70000", "https://user:secret@example.org"})
    void rejectsMalformedOrUnsupportedUrls(String url) {
        assertThrows(IllegalArgumentException.class, () -> shortener.create(null, url, null));
        assertTrue(store.load().owners().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 101})
    void rejectsOutOfRangeLimits(int limit) {
        assertThrows(IllegalArgumentException.class, () -> shortener.create(null, URL, limit));
    }

    private List<Notice.Reason> reasons(UUID owner) throws IOException {
        return reasonsFrom(shortener, owner);
    }

    private static List<Notice.Reason> reasonsFrom(Shortener service, UUID owner) throws IOException {
        return service.takeNotices(owner).stream().map(Notice::reason).toList();
    }

    private static final class MemoryStore implements Store {
        private Snapshot snapshot = Snapshot.empty();
        private boolean failSave;

        @Override
        public Snapshot load() {
            return snapshot;
        }

        @Override
        public void save(Snapshot value) throws IOException {
            if (failSave) {
                throw new IOException("Disk unavailable");
            }
            snapshot = value;
        }
    }
}
