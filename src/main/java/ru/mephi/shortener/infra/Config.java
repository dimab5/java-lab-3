package ru.mephi.shortener.infra;

import ru.mephi.shortener.core.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Properties;

public record Config(Settings settings, Path storageFile) {
    public static Config load(Path path) throws IOException {
        Path file = path.toAbsolutePath().normalize();
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        Settings settings = new Settings(duration(properties, "link.ttl"),
                integer(properties, "link.default-click-limit"), integer(properties, "link.max-click-limit"),
                duration(properties, "cleanup.interval"));
        Path storage = file.getParent().resolve(required(properties, "storage.file")).normalize();
        if (storage.equals(file)) {
            throw new IllegalArgumentException("Файл данных и файл конфигурации должны иметь разные пути");
        }
        return new Config(settings, storage);
    }

    private static Duration duration(Properties properties, String key) {
        try {
            return Duration.parse(required(properties, key));
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(key + " должен содержать длительность в формате ISO-8601", ex);
        }
    }

    private static int integer(Properties properties, String key) {
        try {
            return Integer.parseInt(required(properties, key));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(key + " должен содержать целое число", ex);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key, "").strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Отсутствует параметр " + key);
        }
        return value;
    }
}
