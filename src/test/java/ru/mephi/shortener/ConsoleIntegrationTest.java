package ru.mephi.shortener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mephi.shortener.core.Link;
import ru.mephi.shortener.core.Notice;
import ru.mephi.shortener.core.Settings;
import ru.mephi.shortener.core.Shortener;
import ru.mephi.shortener.infra.Config;
import ru.mephi.shortener.infra.FileStore;
import ru.mephi.shortener.testutil.MutableClock;
import ru.mephi.shortener.ui.ConsoleApp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleIntegrationTest {
    private static final String URL = "https://www.baeldung.com/java-9-http-client";
    private final MutableClock clock = new MutableClock();
    private final Settings settings = new Settings(Duration.ofHours(1), 4, 10, Duration.ofMillis(50));

    @TempDir
    Path tempDir;

    @Test
    void cliSupportsUsersEditingAndOpeningWithBareLinks() throws IOException {
        try (FileStore store = new FileStore(tempDir.resolve("links.tsv"))) {
            Shortener service = new Shortener(store, settings, clock);
            StringWriter output = new StringWriter();
            List<URI> opened = new ArrayList<>();
            ConsoleApp app = new ConsoleApp(service, opened::add, new PrintWriter(output));
            run(app, "shorten " + URL + " 1", "whoami");
            Link first = store.load().links().get("000001");
            assertTrue(output.toString().contains("UUID: " + first.owner()));
            run(app, "new", "shorten " + URL, "limit 000001 3", "delete 000001");
            Link second = store.load().links().get("000002");
            assertNotEquals(first.owner(), second.owner());
            assertEquals(first, store.load().links().get("000001"));
            run(app, "https://short.example/000001", "000001");
            assertFalse(output.toString().contains("Уведомление:"));
            run(app, "use " + first.owner(), "limit 000001 2", "url 000001 https://example.org",
                    "open 000001", "000001", "delete 000001", "list", "exit");
            assertEquals(List.of(URI.create(URL), URI.create("https://example.org")), opened);
            assertTrue(output.toString().contains("Управлять ссылкой может только её владелец"));
            assertTrue(output.toString().contains("Уведомление: https://short.example/000001"));
            assertTrue(output.toString().contains("У вас пока нет ссылок"));
            assertEquals(second, store.load().links().get("000002"));
        }
    }

    @Test
    void activeOwnerReceivesExpiryFromBackgroundMaintenance() throws IOException {
        try (FileStore store = new FileStore(tempDir.resolve("links.tsv"))) {
            StringWriter output = new StringWriter();
            ConsoleApp app = new ConsoleApp(new Shortener(store, settings, clock), uri -> { },
                    new PrintWriter(output));
            run(app, "shorten " + URL);
            clock.advance(Duration.ofHours(1));
            app.tick();
            assertTrue(store.load().links().isEmpty());
            assertTrue(output.toString().contains("срок действия истёк, ссылка удалена"));
            assertTrue(store.load().notices().isEmpty());
        }
    }

    @Test
    void restartRestoresOwnersClicksAndPendingNotifications() throws IOException {
        Path file = tempDir.resolve("links.tsv");
        Link first;
        Link second;
        try (FileStore store = new FileStore(file)) {
            Shortener service = new Shortener(store, settings, clock);
            first = service.create(null, URL, 1);
            second = service.create(null, URL, 4);
            service.open(first.code(), uri -> { });
        }
        try (FileStore store = new FileStore(file)) {
            Shortener service = new Shortener(store, settings, clock);
            assertEquals(1, service.list(first.owner()).get(0).clicks());
            assertThrows(IllegalArgumentException.class, () -> service.open(first.code(), uri -> { }));
            assertEquals(List.of(second), service.list(second.owner()));
        }
        clock.advance(Duration.ofHours(1));
        try (FileStore store = new FileStore(file)) {
            Shortener service = new Shortener(store, settings, clock);
            assertTrue(store.load().links().isEmpty());
            assertEquals(List.of(Notice.Reason.LIMIT_REACHED, Notice.Reason.EXPIRED),
                    service.takeNotices(first.owner()).stream().map(Notice::reason).toList());
            assertTrue(service.hasOwner(first.owner()));
            Link next = service.create(first.owner(), URL, 2);
            assertEquals("000003", next.code());
        }
    }

    @Test
    void storagePreventsSimultaneousWriters() throws IOException {
        Path file = tempDir.resolve("links.tsv");
        try (FileStore first = new FileStore(file)) {
            assertThrows(IOException.class, () -> new FileStore(file).close());
            assertTrue(first.load().links().isEmpty());
        }
        try (FileStore reopened = new FileStore(file)) {
            assertTrue(reopened.load().links().isEmpty());
        }
    }

    @Test
    void readsAndValidatesStandaloneConfiguration() throws IOException {
        Path file = tempDir.resolve("custom.properties");
        String content = """
                link.ttl=PT24H
                link.default-click-limit=7
                link.max-click-limit=70
                cleanup.interval=PT0.5S
                storage.file=store/links.tsv
                """;
        Files.writeString(file, content);
        Config config = Config.load(file);
        assertEquals(new Settings(Duration.ofDays(1), 7, 70, Duration.ofMillis(500)), config.settings());
        assertEquals(tempDir.resolve("store/links.tsv"), config.storageFile());

        Files.writeString(file, content.replace("PT0.5S", "PT25H"));
        assertThrows(IllegalArgumentException.class, () -> Config.load(file));
    }

    private static void run(ConsoleApp app, String... commands) throws IOException {
        app.run(new BufferedReader(new StringReader(String.join("\n", commands))));
    }
}
