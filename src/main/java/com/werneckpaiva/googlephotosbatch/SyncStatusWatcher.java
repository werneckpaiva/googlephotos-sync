package com.werneckpaiva.googlephotosbatch;

import com.werneckpaiva.googlephotosbatch.service.Album;
import org.fusesource.jansi.Ansi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SyncStatusWatcher {
    private static final int BAR_WIDTH = 40;

    public record MediaTaskLog(Status status, int workerIndex, MediaWithName media, int count) {

        public enum Status {
            RESIZE_STARTED,
            RESIZE_COMPLETED,
            RESIZE_NOT_REQUIRED,
            RESIZE_ALL_COMPLETED,
            UPLOAD_STARTED,
            UPLOAD_COMPLETED,
            UPLOAD_FAILED,
            UPLOAD_ALL_COMPLETED,
            SAVE_STARTED,
            SAVE_COMPLETED
        }

        public MediaTaskLog(Status status, int workerIndex) {
            this(status, workerIndex, null, 0);
        }

        public MediaTaskLog(Status status, int workerIndex, MediaWithName media) {
            this(status, workerIndex, media, 0);
        }

        public static MediaTaskLog forSave(Status status, int count) {
            return new MediaTaskLog(status, 0, null, count);
        }
    }

    private static class SyncProgress {
        int completedUploads = 0;
        final Map<String, String> activeResizeTasks = new LinkedHashMap<>();
        final Map<String, String> activeUploadTasks = new LinkedHashMap<>();
        boolean saveStarted = false;
        boolean saveCompleted = false;
        int saveCount = 0;
        final long startTime = System.currentTimeMillis();
    }

    public static Callable<Void> getWatcherTask(
            Album album,
            BlockingQueue<MediaWithName> mediasToUploadQueue,
            ConcurrentLinkedQueue<MediaWithName> mediasToResizeQueue,
            List<MediaWithName> mediasUploaded,
            ConcurrentLinkedQueue<MediaTaskLog> progressLog,
            int totalMedias) {

        return () -> {
            SyncProgress progress = new SyncProgress();
            int lastDisplayedLines = 0;

            while (!progress.saveCompleted) {
                processLogEntries(progressLog, progress);
                lastDisplayedLines = refreshDisplay(album.title(), progress, totalMedias, lastDisplayedLines);

                if (!progress.saveCompleted) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            long elapsedMs = System.currentTimeMillis() - progress.startTime;
            String elapsedStr = formatTime(elapsedMs);
            String bar = "=".repeat(BAR_WIDTH);
            System.out.println(String.format("Syncing 100%% | %s | %d/%d (%s / %s) - Album %s completed.",
                    bar, totalMedias, totalMedias, elapsedStr, elapsedStr, album.title()));
            System.out.flush();

            return null;
        };
    }

    private static void processLogEntries(ConcurrentLinkedQueue<MediaTaskLog> progressLog, SyncProgress progress) {
        MediaTaskLog log;
        while ((log = progressLog.poll()) != null) {
            String mediaName = (log.media() != null) ? log.media().name() : "";
            String workerKey = String.valueOf(log.workerIndex());

            switch (log.status()) {
                case RESIZE_STARTED:
                    progress.activeResizeTasks.put(workerKey, mediaName);
                    break;
                case RESIZE_COMPLETED:
                case RESIZE_NOT_REQUIRED:
                    progress.activeResizeTasks.remove(workerKey);
                    break;
                case UPLOAD_STARTED:
                    progress.activeUploadTasks.put(workerKey, mediaName);
                    break;
                case UPLOAD_COMPLETED:
                case UPLOAD_FAILED:
                    progress.activeUploadTasks.remove(workerKey);
                    progress.completedUploads++;
                    break;
                case SAVE_STARTED:
                    progress.saveStarted = true;
                    progress.saveCount = log.count();
                    break;
                case SAVE_COMPLETED:
                    progress.saveCompleted = true;
                    break;
                default:
                    break;
            }
        }
    }

    private static int refreshDisplay(String albumName, SyncProgress progress, int totalMedias,
            int lastDisplayedLines) {

        if (progress.saveCompleted) {
            if (lastDisplayedLines > 0) {
                System.out.print(Ansi.ansi().cursorUp(lastDisplayedLines).eraseScreen(Ansi.Erase.FORWARD));
            }
            return 0;
        }

        StringBuilder display = new StringBuilder();

        if (lastDisplayedLines > 0) {
            display.append(Ansi.ansi().cursorUp(lastDisplayedLines).eraseScreen(Ansi.Erase.FORWARD));
        }

        long elapsedMs = System.currentTimeMillis() - progress.startTime;
        String progressLine = buildProgressLine(albumName, progress.completedUploads, totalMedias, elapsedMs);
        display.append(progressLine).append("\n");

        int lineCount = 1;

        if (progress.saveStarted) {
            display.append(String.format("Saving %d photos to album %s...\n", progress.saveCount, albumName));
            lineCount++;
        } else {
            for (String name : progress.activeResizeTasks.values()) {
                display.append(String.format("Resizing: %s\n", name));
                lineCount++;
            }
            for (String name : progress.activeUploadTasks.values()) {
                display.append(String.format("Uploading: %s\n", name));
                lineCount++;
            }
        }

        System.out.print(display.toString());
        System.out.flush();
        return lineCount;
    }

    private static String buildProgressLine(String albumName, int completed, int total, long elapsedMs) {
        int percent = (total > 0) ? (completed * 100 / total) : 0;
        int filled = (total > 0) ? (BAR_WIDTH * completed) / total : 0;

        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < BAR_WIDTH; i++) {
            bar.append(i < filled ? "=" : " ");
        }

        String elapsedStr = formatTime(elapsedMs);
        String totalTimeStr = "??:??";
        if (completed > 0) {
            long estimatedTotalMs = (elapsedMs * total) / completed;
            totalTimeStr = formatTime(estimatedTotalMs);
        }

        return String.format("Syncing %3d%% | %s | %d/%d (%s / %s) - Album %s",
                percent, bar.toString(), completed, total, elapsedStr, totalTimeStr, albumName);
    }

    private static String formatTime(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }
}
