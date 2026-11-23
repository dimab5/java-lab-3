package ru.mephi.shortener.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface Store {
    Snapshot load() throws IOException;

    void save(Snapshot snapshot) throws IOException;

    record Snapshot(long sequence, Set<UUID> owners, Map<String, Link> links, List<Notice> notices) {
        public static Snapshot empty() {
            return new Snapshot(0, Set.of(), Map.of(), List.of());
        }

        Snapshot copy(long nextSequence) {
            return new Snapshot(nextSequence, new HashSet<>(owners), new HashMap<>(links), new ArrayList<>(notices));
        }
    }
}
