package ru.mephi.shortener.infra;

import ru.mephi.shortener.core.Link;
import ru.mephi.shortener.core.Notice;
import ru.mephi.shortener.core.Store;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class FileStore implements Store, AutoCloseable {
    private static final String FORMAT = "shortener-v1";

    private final Path file;
    private final FileChannel lockChannel;

    public FileStore(Path path) throws IOException {
        file = path.toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
        lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = lockChannel.tryLock();
            if (lock == null) {
                throw new IOException("Хранилище уже открыто в другом экземпляре приложения");
            }
        } catch (OverlappingFileLockException ex) {
            lockChannel.close();
            throw new IOException("Хранилище уже открыто в другом экземпляре приложения", ex);
        } catch (IOException | RuntimeException ex) {
            lockChannel.close();
            throw ex;
        }
    }

    @Override
    public Snapshot load() throws IOException {
        if (Files.notExists(file)) {
            return Snapshot.empty();
        }
        try (var reader = Files.newBufferedReader(file)) {
            String header = reader.readLine();
            if (header == null || !header.startsWith(FORMAT + "\t")) {
                throw new IllegalArgumentException("Неизвестный формат");
            }
            long sequence = Long.parseLong(header.substring(FORMAT.length() + 1));
            Set<UUID> owners = new HashSet<>();
            Map<String, Link> links = new HashMap<>();
            List<Notice> notices = new ArrayList<>();
            for (String line; (line = reader.readLine()) != null; ) {
                String[] fields = line.split("\t", -1);
                switch (fields[0]) {
                    case "owner" -> owners.add(UUID.fromString(fields[1]));
                    case "link" -> {
                        Link link = new Link(fields[1], UUID.fromString(fields[2]), URI.create(fields[3]),
                                Instant.parse(fields[4]), Integer.parseInt(fields[5]), Integer.parseInt(fields[6]));
                        links.put(link.code(), link);
                    }
                    case "notice" -> notices.add(new Notice(UUID.fromString(fields[1]), fields[2],
                            Notice.Reason.valueOf(fields[3]), Instant.parse(fields[4])));
                    default -> throw new IllegalArgumentException("Неизвестная запись");
                }
            }
            return new Snapshot(sequence, owners, links, notices);
        } catch (RuntimeException ex) {
            throw new IOException("Повреждён файл данных " + file, ex);
        }
    }

    @Override
    public void save(Snapshot snapshot) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(FORMAT + "\t" + snapshot.sequence());
        for (UUID owner : snapshot.owners()) {
            lines.add("owner\t" + owner);
        }
        for (Link link : snapshot.links().values()) {
            lines.add(String.join("\t", "link", link.code(), link.owner().toString(), link.destination().toString(),
                    link.expiresAt().toString(), Integer.toString(link.limit()), Integer.toString(link.clicks())));
        }
        for (Notice notice : snapshot.notices()) {
            lines.add(String.join("\t", "notice", notice.owner().toString(), notice.code(), notice.reason().name(),
                    notice.at().toString()));
        }
        Path temporary = Files.createTempFile(file.getParent(), "shortener-", ".tmp");
        try {
            Files.write(temporary, lines);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void close() throws IOException {
        lockChannel.close();
    }
}
