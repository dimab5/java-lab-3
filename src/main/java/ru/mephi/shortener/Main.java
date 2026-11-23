package ru.mephi.shortener;

import ru.mephi.shortener.core.Shortener;
import ru.mephi.shortener.infra.Config;
import ru.mephi.shortener.infra.DesktopBrowser;
import ru.mephi.shortener.infra.FileStore;
import ru.mephi.shortener.ui.ConsoleApp;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        if (args.length > 1) {
            System.err.println("Запуск: java -jar url-shortener-1.0.0.jar [путь к application.properties]");
            System.exit(1);
        }
        try {
            Config config = Config.load(Path.of(args.length == 1 ? args[0] : "application.properties"));
            run(config);
        } catch (IOException | IllegalArgumentException ex) {
            System.err.println("Ошибка: " + ex.getMessage());
            System.exit(1);
        }
    }

    private static void run(Config config) throws IOException {
        try (FileStore store = new FileStore(config.storageFile())) {
            Shortener shortener = new Shortener(store, config.settings(), Clock.systemUTC());
            Console terminal = System.console();
            BufferedReader input = new BufferedReader(
                    terminal == null ? new InputStreamReader(System.in) : terminal.reader());
            PrintWriter output = terminal == null ? new PrintWriter(System.out, true) : terminal.writer();
            ConsoleApp app = new ConsoleApp(shortener, new DesktopBrowser(), output);
            var cleanup = Executors.newSingleThreadScheduledExecutor();
            long interval = config.settings().cleanupInterval().toMillis();
            cleanup.scheduleWithFixedDelay(app::tick, interval, interval, TimeUnit.MILLISECONDS);
            try {
                app.run(input);
            } finally {
                cleanup.shutdown();
                try {
                    while (!cleanup.awaitTermination(1, TimeUnit.SECONDS)) {
                        // Let any running cleanup finish before closing storage.
                    }
                } catch (InterruptedException ex) {
                    cleanup.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
