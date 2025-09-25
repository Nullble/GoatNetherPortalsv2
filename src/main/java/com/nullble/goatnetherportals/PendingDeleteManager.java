package com.nullble.goatnetherportals;

import java.util.HashMap;
import java.util.UUID;

public class PendingDeleteManager {
    private final HashMap<UUID, String> pending = new HashMap<>();

    public void queue(UUID uuid, String target) {
        pending.put(uuid, target);
    }

    public String confirm(UUID uuid) {
        return pending.remove(uuid);
    }

    public boolean isPending(UUID uuid) {
        return pending.containsKey(uuid);
    }
}
