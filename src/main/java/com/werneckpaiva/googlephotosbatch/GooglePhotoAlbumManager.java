package com.werneckpaiva.googlephotosbatch;

import com.werneckpaiva.googlephotosbatch.exception.PermissionDeniedToLoadAlbumsException;
import com.werneckpaiva.googlephotosbatch.service.Album;
import com.werneckpaiva.googlephotosbatch.service.GooglePhotosAPI;
import com.werneckpaiva.googlephotosbatch.utils.AlbumUtils;
import com.werneckpaiva.googlephotosbatch.utils.ImageUtils;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.fusesource.jansi.Ansi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Manages Google Photo Albums
 * Keeps Google Service state
 * Uploads one folder a time
 */
public class GooglePhotoAlbumManager {

    private static final Logger logger = LoggerFactory.getLogger(GooglePhotoAlbumManager.class);

    private final GooglePhotosAPI googlePhotosAPI;

    private Map<String, Album> albums = null;

    public static final int MAX_FREE_DIMENSION = 4608;

    private record MediaWithName(String name, File file, String uploadToken) implements Comparable<MediaWithName> {
        public MediaWithName(String name, File resizedFile) {
            this(name, resizedFile, null);
        }

        @Override
        public int compareTo(MediaWithName other) {
            return this.name.compareTo(other.name);
        }
    }

    public GooglePhotoAlbumManager(GooglePhotosAPI googlePhotosAPI) {
        this.googlePhotosAPI = googlePhotosAPI;
    }

    public Map<String, Album> listAllAlbums() throws PermissionDeniedToLoadAlbumsException {
        logger.info("Loading albums ");

        long startTime = System.currentTimeMillis();
        Map<String, Album> allAlbums = new HashMap<>();
//        byte retry = 0;
//        while (retry++ < 100) {
//            try {
//                int i = 1;
//                for (Album album : googlePhotosAPI.getAllAlbums()) {
//                    if (i++ % 100 == 0) {
//                        System.out.print(".");
//                    }
//                    allAlbums.put(album.title(), album);
//                }
//                break;
//            } catch (PermissionDeniedException e) {
//                throw new PermissionDeniedToLoadAlbumsException(e);
//            } catch (RuntimeException e) {
//                e.printStackTrace(System.err);
//                System.out.print("x");
//            }
//        }
//        System.out.printf(" %d albums loaded (%d ms)\n", allAlbums.size(), (System.currentTimeMillis() - startTime));
        return allAlbums;

    }

    public Album getAlbum(String albumName) throws PermissionDeniedToLoadAlbumsException {
        if (this.albums == null) {
            this.albums = listAllAlbums();
        }
        return this.albums.get(albumName);
    }

    public Album createAlbum(String albumName) {
        logger.info("Creating Fake album {}", albumName);
        Album album = new Album(albumName, "123", true); //googlePhotosAPI.createAlbum(albumName);
        this.albums.put(albumName, album);
        return album;
    }

    private record MediaTaskLog(Status status, int workerIndex, MediaWithName media){

        enum Status {
            RESIZE_STARTED,
            RESIZE_COMPLETED,
            RESIZE_NOT_REQUIRED,
            RESIZE_ALL_COMPLETED,
            UPLOAD_STARTED,
            UPLOAD_COMPLETED,
            UPLOAD_ALL_COMPLETED
        }

        MediaTaskLog(Status status, int workerIndex) {
            this(status, workerIndex, null);
        }
    }

    public void batchUploadFiles(Album album, List<File> files) {
        logger.info("Album: {}", album.title());
        final Set<String> albumFileNames = new HashSet<String>(); // googlePhotosAPI.retrieveFilesFromAlbum(album);

        List<MediaWithName> mediasToUpload = this.getMediasToUpload(files, albumFileNames);

        int numberOfMediasToUpload = mediasToUpload.size();
        if (numberOfMediasToUpload == 0) return;

        if (!album.isWriteable()) {
            logger.error("Album is not writable");
            return;
        }

        logger.info("Uploading {} medias", numberOfMediasToUpload);

        int numCores = Runtime.getRuntime().availableProcessors();
        ExecutorService taskExecutor = Executors.newFixedThreadPool(numCores + 1);
        ConcurrentLinkedQueue<MediaWithName> mediasToResizeQueue = new ConcurrentLinkedQueue<>(mediasToUpload);
        List<MediaWithName> mediasUploaded = Collections.synchronizedList(new ArrayList<>(numberOfMediasToUpload));
        BlockingQueue<MediaWithName> mediasToUploadQueue = new LinkedBlockingQueue<>(numberOfMediasToUpload);
        ConcurrentLinkedQueue<MediaTaskLog> progressLog = new ConcurrentLinkedQueue<>();

        IntStream.range(0, numCores - 1)
                .mapToObj(i -> getResizerTask(i, mediasToResizeQueue, mediasToUploadQueue, progressLog))
                .forEach(taskExecutor::submit);

        IntStream.range(0, 1)
                .mapToObj(i -> getUploaderTask(i, numberOfMediasToUpload, mediasToUploadQueue, mediasUploaded, progressLog))
                .forEach(taskExecutor::submit);

        taskExecutor.submit(getWatcherTask(mediasToUploadQueue, mediasToResizeQueue, mediasUploaded, progressLog));

        taskExecutor.shutdown();
        try {
            taskExecutor.awaitTermination(1, TimeUnit.DAYS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        Collections.sort(mediasUploaded);

        if (!mediasUploaded.isEmpty()) {
            logger.info("Saving {} medias to album {}", mediasUploaded.size(), album.title());
            googlePhotosAPI.saveToAlbum(album,
                    mediasUploaded.stream()
                            .map(MediaWithName::uploadToken)
                            .collect(Collectors.toList()));
        }
    }

    private Callable<Void> getResizerTask(int index, ConcurrentLinkedQueue<MediaWithName> mediasToResizeQueue, BlockingQueue<MediaWithName> mediasToUpload, ConcurrentLinkedQueue<MediaTaskLog> progressLog) {
        return () -> {
            while (!mediasToResizeQueue.isEmpty()) {
                MediaWithName mediaToResize = mediasToResizeQueue.poll();
                if (mediaToResize != null) {
                    progressLog.add(new MediaTaskLog(MediaTaskLog.Status.RESIZE_STARTED, index, mediaToResize));
                    if (ImageUtils.isJPEG(mediaToResize.file)) {
                        File resizedFile = ImageUtils.resizeJPGImage(mediaToResize.file, MAX_FREE_DIMENSION);
                        mediaToResize = new MediaWithName(mediaToResize.name, resizedFile);
                        progressLog.add(new MediaTaskLog(MediaTaskLog.Status.RESIZE_COMPLETED, index, mediaToResize));
                    } else {
                        progressLog.add(new MediaTaskLog(MediaTaskLog.Status.RESIZE_NOT_REQUIRED, index, mediaToResize));
                    }
                    try {
                        mediasToUpload.put(mediaToResize);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            progressLog.add(new MediaTaskLog(MediaTaskLog.Status.RESIZE_ALL_COMPLETED, index));
            return null;
        };
    }

    private Callable<Void> getUploaderTask(int index, int totalNumMedias, BlockingQueue<MediaWithName> mediasToUploadQueue, List<MediaWithName> mediasUploaded, ConcurrentLinkedQueue<MediaTaskLog> progressLog) {
        AtomicInteger numMediasToUpload = new AtomicInteger(totalNumMedias);
        return () -> {
            while (numMediasToUpload.getAndDecrement() > 0) {
                try {
                    MediaWithName media = mediasToUploadQueue.take();
                    progressLog.add(new MediaTaskLog(MediaTaskLog.Status.UPLOAD_STARTED, index, media));
                    String newMediaToken = googlePhotosAPI.uploadSingleFile(media.name, media.file);
                    if (newMediaToken != null) {
                        progressLog.add(new MediaTaskLog(MediaTaskLog.Status.UPLOAD_COMPLETED, index, media));
                        mediasUploaded.add(new MediaWithName(media.name, media.file, newMediaToken));
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            progressLog.add(new MediaTaskLog(MediaTaskLog.Status.UPLOAD_ALL_COMPLETED, index));
            return null;
        };
    }

//    private Callable<Void> getWatcherTask(
//            BlockingQueue<MediaWithName> mediasToUploadQueue,
//            ConcurrentLinkedQueue<MediaWithName> mediasToResizeQueue,
//            List<MediaWithName> mediasUploaded,
//            ConcurrentLinkedQueue<MediaTaskLog> progressLog) {
//
//        return () -> {
//            final int BAR_LENGTH = 50;
//            final String progressBar = String.valueOf('-').repeat(BAR_LENGTH);
//            final String progressBarPrefix = "Progress: [";
//            final String progressBarSuffix = "]";
//            Ansi ansi = Ansi.ansi()
//                    .cursorToColumn(0)
//                    .reset()
//                    .a(progressBarPrefix)
//                    .a(progressBar)
//                    .a(progressBarSuffix)
//                    .reset();
//            int numResizingTasks = 0;
//            int numUploadingTasks = 0;
//            int totalMedias = mediasToUploadQueue.size() + mediasToResizeQueue.size() + mediasUploaded.size();
//            long startTime = System.currentTimeMillis(); // Start time for overall process
//
//            while (!mediasToResizeQueue.isEmpty() || !mediasToUploadQueue.isEmpty()) {
//                try {
//                    int uploadedCount = mediasUploaded.size();
//                    long elapsedTime = System.currentTimeMillis() - startTime; // Elapsed time in milliseconds
//
//                    // Calculate estimated time remaining
//                    String etaMessage = "";
//                    if (uploadedCount > 0 && elapsedTime > 0) {
//                        double uploadRate = (double) uploadedCount / elapsedTime; // medias per millisecond
//                        long remainingMedias = totalMedias - uploadedCount;
//                        long estimatedTimeRemaining = (long) (remainingMedias / uploadRate); // milliseconds
//
//                        long hours = TimeUnit.MILLISECONDS.toHours(estimatedTimeRemaining);
//                        long minutes = TimeUnit.MILLISECONDS.toMinutes(estimatedTimeRemaining) % 60;
//                        long seconds = TimeUnit.MILLISECONDS.toSeconds(estimatedTimeRemaining) % 60;
//                        etaMessage = String.format(" ETA: %02d:%02d:%02d", hours, minutes, seconds);
//                    }
//
//                    int progress = (int) ((double) uploadedCount / totalMedias * 100);
//                    int filledLength = (int) ((double) progress / 100 *
//



    private Callable<Void> getWatcherTask(
            BlockingQueue<MediaWithName> mediasToUploadQueue,
            ConcurrentLinkedQueue<MediaWithName> mediasToResizeQueue,
            List<MediaWithName> mediasUploaded,
            ConcurrentLinkedQueue<MediaTaskLog> progressLog) {

        return () -> {
            final int BAR_LENGTH = 50;
            final String progressBar = String.valueOf('-').repeat(BAR_LENGTH);
            final String progressBarPrefix = "Progress: [";
            final String progressBarSuffix = "]";
            Ansi ansi = Ansi.ansi()
                    .cursorToColumn(0)
                    .reset()
                    .a(progressBarPrefix)
                    .a(progressBar)
                    .a(progressBarSuffix)
                    .reset();
            int numResizingTasks = 0;
            int numUploadingTasks = 0;
            int totalMedias = mediasToUploadQueue.size() + mediasToResizeQueue.size() + mediasUploaded.size();
            long startTime = System.currentTimeMillis();
            while (!mediasToResizeQueue.isEmpty() || !mediasToUploadQueue.isEmpty()) {
                try {
                    int uploadedCount = mediasUploaded.size();
                    int progress = (int) ((double) uploadedCount / totalMedias * 100);
                    int filledLength = (int) ((double) progress / 100 * BAR_LENGTH);
                    long elapsedTime = System.currentTimeMillis() - startTime;
                    logger.debug("Progress: uploaded: {}, total: {}", uploadedCount, totalMedias);
                    ansi = ansi
                            .cursorToColumn(progressBarPrefix.length() + filledLength + 1)
                            .fg(Ansi.Color.GREEN).a("=")
                            .reset()
                            .cursorToColumn(progressBarPrefix.length() + progressBar.length() + progressBarSuffix.length() + 1)
                            .a(String.format(" %3d%% (%d/%d)", progress, uploadedCount, totalMedias));
                    if (uploadedCount > 3) {
                        double uploadRate = (double) uploadedCount / elapsedTime; // medias per millisecond
                        long remainingMedias = totalMedias - uploadedCount;
                        long estimatedTimeRemaining = (long) (remainingMedias / uploadRate); // milliseconds

                        long hours = TimeUnit.MILLISECONDS.toHours(estimatedTimeRemaining);
                        long minutes = TimeUnit.MILLISECONDS.toMinutes(estimatedTimeRemaining) % 60;
                        long seconds = TimeUnit.MILLISECONDS.toSeconds(estimatedTimeRemaining) % 60;
//                        ansi = ansi.a(String.format(" ETA: %02d:%02d:%02d", hours, minutes, seconds));
                    }
                    MediaTaskLog mediaTaskLog=progressLog.poll();
                    if (mediaTaskLog != null) {
                        switch (mediaTaskLog.status()){
                            case RESIZE_STARTED:
                                ansi = ansi.cursorDown(mediaTaskLog.workerIndex() + 1)
                                        .cursorToColumn(0)
                                        .eraseLine(Ansi.Erase.ALL)
                                        .a("> RESIZE: " + mediaTaskLog.media().name)
                                        .cursorUp(mediaTaskLog.workerIndex() + 1);
                                numResizingTasks = Math.max(numResizingTasks, mediaTaskLog.workerIndex() + 1);
                                break;
                            case RESIZE_COMPLETED:
                                ansi = ansi.cursorDown(mediaTaskLog.workerIndex() + 1)
                                        .cursorToColumn(0)
                                        .eraseLine(Ansi.Erase.ALL)
                                        .a("> RESIZE: " + mediaTaskLog.media().name + " completed")
                                        .cursorUp(mediaTaskLog.workerIndex() + 1);
                                numResizingTasks = Math.max(numResizingTasks, mediaTaskLog.workerIndex() + 1);
                                break;
                            case RESIZE_NOT_REQUIRED:
                                ansi = ansi.cursorDown(mediaTaskLog.workerIndex() + 1)
                                        .cursorToColumn(0)
                                        .eraseLine(Ansi.Erase.ALL)
                                        .a("> RESIZE: " + mediaTaskLog.media().name + " not required")
                                        .cursorUp(mediaTaskLog.workerIndex() + 1);
                                numResizingTasks = Math.max(numResizingTasks, mediaTaskLog.workerIndex() + 1);
                                break;
                            case RESIZE_ALL_COMPLETED:
                                ansi = ansi.cursorDown(mediaTaskLog.workerIndex() + 1)
                                        .eraseLine(Ansi.Erase.ALL);
                                numResizingTasks--;
                                break;
                            case UPLOAD_STARTED:
                                ansi = ansi.cursorDown(mediaTaskLog.workerIndex() + numResizingTasks + 1)
                                        .cursorToColumn(0)
                                        .eraseLine(Ansi.Erase.ALL)
                                        .a("> UPLOAD: " + mediaTaskLog.media().name)
                                        .cursorUp(mediaTaskLog.workerIndex() + numResizingTasks + 1);
                                numUploadingTasks = Math.max(numUploadingTasks, mediaTaskLog.workerIndex() + 1);
                                break;
                            case UPLOAD_COMPLETED:
                                ansi = ansi.cursorDown(mediaTaskLog.workerIndex() + numResizingTasks + 1)
                                        .cursorToColumn(0)
                                        .eraseLine(Ansi.Erase.ALL)
                                        .a("> UPLOAD " + mediaTaskLog.media().name + " completed")
                                        .cursorUp(mediaTaskLog.workerIndex() + numResizingTasks + 1);
                                numUploadingTasks = Math.max(numUploadingTasks, mediaTaskLog.workerIndex() + 1);
                                break;
                            case UPLOAD_ALL_COMPLETED:
                                ansi = ansi.cursorDown(mediaTaskLog.workerIndex() + numResizingTasks )
                                        .eraseLine(Ansi.Erase.ALL);
                                numUploadingTasks--;
                                break;
                        }
                    }
//                    int newNumTasks = numResizingTasks + numUploadingTasks;
//                    if (numTasks < newNumTasks){
//                        IntStream.of(newNumTasks - numTasks).forEach(System.out::println);
//                        numTasks = newNumTasks;
//                    }
                    System.out.println(ansi);
                    if (mediaTaskLog == null) Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                ansi = Ansi.ansi();
            }
            // Final success message using the same builder pattern
            Ansi completionMessage = Ansi.ansi()
                    .cursorToColumn(1)
                    .eraseLine(Ansi.Erase.ALL)
                    .fg(Ansi.Color.GREEN)
                    .a("🎉 All tasks complete! (" + mediasUploaded.size() + " uploaded) 🎉")
                    .reset();
            System.out.println(completionMessage);
            return null;
        };
    }

    private List<MediaWithName> getMediasToUpload(List<File> files, Set<String> albumFileNames) {
        return files.stream()
                .map(file -> new MediaWithName(AlbumUtils.file2MediaName(file), file))
                .filter(media -> !albumFileNames.contains(media.name))
                .collect(Collectors.toList());
    }

}
