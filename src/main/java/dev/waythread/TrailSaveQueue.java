package dev.waythread;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;

final class TrailSaveQueue implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<Path, WriteRequest> latest = new LinkedHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Waythread route writer");
        thread.setDaemon(true);
        return thread;
    });
    private boolean draining;
    private boolean closed;

    synchronized void submit(Path file, TrailSave.Snapshot snapshot) {
        if (closed) {
            throw new IllegalStateException("Waythread save queue is closed");
        }
        latest.put(file, new WriteRequest(file, snapshot));
        startWriter();
    }

    TrailSave read(Path file) throws IOException {
        WriteRequest request;
        synchronized (this) {
            request = latest.get(file);
        }
        if (request == null) {
            return TrailSave.read(file);
        }
        // The latest write or deletion also applies when a world is immediately reopened.
        return request.snapshot == null ? null : request.snapshot.read();
    }

    synchronized void retryFailed() {
        if (closed) {
            return;
        }
        boolean retry = false;
        for (WriteRequest request : latest.values()) {
            if (request.state == WriteState.FAILED) {
                request.state = WriteState.READY;
                retry = true;
            }
        }
        if (retry) {
            startWriter();
        }
    }

    private void startWriter() {
        if (!draining) {
            draining = true;
            writer.execute(this::drain);
        }
    }

    private void drain() {
        for (;;) {
            WriteRequest request;
            boolean startedDuringShutdown;
            synchronized (this) {
                request = latest.values().stream().filter(item -> item.state == WriteState.READY).findFirst().orElse(null);
                if (request == null) {
                    draining = false;
                    return;
                }
                request.state = WriteState.WRITING;
                startedDuringShutdown = closed;
            }

            boolean written = false;
            try {
                if (request.snapshot == null) {
                    Files.deleteIfExists(request.file);
                } else {
                    request.snapshot.write(request.file);
                }
                written = true;
            } catch (IOException | RuntimeException error) {
                LOGGER.warn("Could not save the local Waythread route", error);
            }
            synchronized (this) {
                if (written) {
                    latest.remove(request.file, request);
                } else {
                    request.state = closed && !startedDuringShutdown ? WriteState.READY : WriteState.FAILED;
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            retryFailed();
            closed = true;
        }
        writer.close();
    }

    private enum WriteState {
        READY, WRITING, FAILED
    }

    private static final class WriteRequest {
        private final Path file;
        private final TrailSave.Snapshot snapshot;
        private WriteState state = WriteState.READY;

        private WriteRequest(Path file, TrailSave.Snapshot snapshot) {
            this.file = file;
            this.snapshot = snapshot;
        }
    }
}
