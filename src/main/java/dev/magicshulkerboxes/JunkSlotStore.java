package dev.magicshulkerboxes;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;

/** Server-world persistence for player-owned slot roles, separate from global option defaults. */
public final class JunkSlotStore {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final int MAX_FILE_BYTES = 4096;
    private final Path directory;
    private final Map<UUID, Snapshot> cached = new HashMap<>();
    private final Map<UUID, IOException> failures = new HashMap<>();
    public record Snapshot(long mask, int revision) {}
    public static final class Changed extends IOException {
        private static final long serialVersionUID = 1L;
        Changed() { super("Junk slot selection changed"); }
    }

    public JunkSlotStore(Path directory) { this.directory = directory; }

    public Snapshot read(UUID player) throws IOException {
        if (failures.containsKey(player)) throw failures.get(player);
        if (!cached.containsKey(player)) {
            try { cached.put(player, new Snapshot(readDisk(path(player)), 0)); }
            catch (IOException exception) { failures.put(player, exception); throw exception; }
        }
        return cached.get(player);
    }

    /** Server-thread compare-and-set; publish the new state only after the atomic file replacement succeeds. */
    public Snapshot update(UUID player, int expectedRevision, long mask) throws IOException {
        JunkSlots.validate(mask);
        var current = read(player);
        long disk = readDisk(path(player));
        if (disk != current.mask()) {
            cached.put(player, new Snapshot(disk, nextRevision(current)));
            throw new Changed();
        }
        if (expectedRevision != current.revision()) throw new Changed();
        if (mask == current.mask()) return current;
        var replacement = new Snapshot(mask, nextRevision(current));
        var document = new JsonObject(); document.addProperty("schemaVersion", 1);
        var slots = new JsonArray();
        for (int slot = 0; slot < JunkSlots.SLOT_COUNT; slot++) if (JunkSlots.selected(mask, slot)) slots.add(slot);
        document.add("slots", slots);
        ConfigFile.write(path(player), document);
        cached.put(player, replacement);
        return replacement;
    }

    public void clearCache() { cached.clear(); failures.clear(); }

    private Path path(UUID player) { return directory.resolve(player + ".json"); }

    private static int nextRevision(Snapshot current) throws IOException {
        if (current.revision() == Integer.MAX_VALUE) throw new IOException("Junk slot revision exhausted");
        return current.revision() + 1;
    }

    private static long readDisk(Path path) throws IOException {
        if (Files.notExists(path)) return 0;
        if (Files.size(path) > MAX_FILE_BYTES) throw new IOException("Junk slot file is too large");
        final JsonObject document;
        try {
            var parsed = JSON.fromJson(Files.readString(path), JsonElement.class);
            if (parsed == null || !parsed.isJsonObject()) throw new IOException("Junk slot file must be an object");
            document = parsed.getAsJsonObject();
        } catch (JsonParseException exception) { throw new IOException("Invalid junk slot JSON", exception); }
        if (!document.keySet().equals(Set.of("schemaVersion", "slots"))
                || !document.get("schemaVersion").isJsonPrimitive()
                || !document.get("schemaVersion").getAsJsonPrimitive().isNumber()
                || !document.get("schemaVersion").getAsString().equals("1")
                || !document.get("slots").isJsonArray()) throw new IOException("Unsupported junk slot document");
        long mask = 0;
        for (var value : document.getAsJsonArray("slots")) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                    || !value.getAsString().matches("0|[1-9][0-9]?")) throw new IOException("Invalid junk slot index");
            int slot = value.getAsInt();
            if (slot >= JunkSlots.SLOT_COUNT || JunkSlots.selected(mask, slot)) throw new IOException("Invalid or duplicate junk slot");
            mask |= 1L << slot;
        }
        return mask;
    }
}
