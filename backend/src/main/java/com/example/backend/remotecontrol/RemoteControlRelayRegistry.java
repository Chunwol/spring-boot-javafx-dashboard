package com.example.backend.remotecontrol;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RemoteControlRelayRegistry {

    private final Map<UUID, Runnable> closers = new ConcurrentHashMap<>();

    public void register(UUID sessionId, Runnable closer) {
        Runnable previous = closers.put(sessionId, closer);
        if (previous != null && previous != closer) previous.run();
    }

    public void unregister(UUID sessionId, Runnable closer) {
        closers.remove(sessionId, closer);
    }

    public void close(UUID sessionId) {
        Runnable closer = closers.remove(sessionId);
        if (closer != null) closer.run();
    }
}
