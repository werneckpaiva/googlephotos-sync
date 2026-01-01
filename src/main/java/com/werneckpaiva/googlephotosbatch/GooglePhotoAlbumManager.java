package com.werneckpaiva.googlephotosbatch;

import com.google.api.gax.rpc.PermissionDeniedException;
import com.google.api.gax.rpc.UnauthenticatedException;
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

import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import me.tongfei.progressbar.ProgressBarStyle;

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
        byte retry = 0;
        try (java.io.FileWriter fw = new java.io.FileWriter("/tmp/all_google_photo_albums.txt")) {
            while (retry++ < 100) {
                try {
                    int i = 1;
                    for (Album album : googlePhotosAPI.getAllAlbums()) {

                        fw.write("{\"title\": \"");
                        fw.write(album.title());
                        fw.write("\", \"id\": \"");
                        fw.write(album.id());
                        fw.write("\", \"isWritable\": ");
                        fw.write(album.isWriteable() ? "true" : "false");
                        fw.write("}\n");

                        if (i++ % 100 == 0) {
                            System.out.print(".");
                        }
                        allAlbums.put(album.title(), album);
                    }
                    break;
                } catch (PermissionDeniedException | UnauthenticatedException e) {
                    throw new PermissionDeniedToLoadAlbumsException(e);
                } catch (RuntimeException e) {
                    e.printStackTrace(System.err);
                    System.out.print("x");
                }
            }
        } catch (java.io.IOException e) {
            logger.error("Error writing album names to file", e);
        }

        System.out.printf(" %d albums loaded (%d ms)\n", allAlbums.size(), (System.currentTimeMillis() - startTime));
        return allAlbums;

    }

    private boolean skipAlbumLoad = false;

    private String albumId = null;

    public void setSkipAlbumLoad(boolean skipAlbumLoad) {
        this.skipAlbumLoad = skipAlbumLoad;
    }

    public void setAlbumId(String albumId) {
        this.albumId = albumId;
    }

    public Album getAlbum(String albumName) throws PermissionDeniedToLoadAlbumsException {
        if (this.albumId != null) {
            Album album = googlePhotosAPI.getAlbum(this.albumId);
            if (this.albums == null) {
                this.albums = new HashMap<>();
            }
            if (album != null) {
                this.albums.put(albumName, album);
            }
            return album;
        }
        if (this.albums == null) {
            if (this.skipAlbumLoad) {
                this.albums = new HashMap<>();
            } else {
                this.albums = listAllAlbums();
            }
        }
        return this.albums.get(albumName);
    }

    public Album createAlbum(String albumName) {
        logger.info("Creating new album {}", albumName);
        Album album = googlePhotosAPI.createAlbum(albumName);
        this.albums.put(albumName, album);
        return album;
    }

    private record MediaTaskLog(Status status, int workerIndex, MediaWithName media) {

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
        final Set<String> albumFileNames = this.skipAlbumLoad ? new HashSet<>()
                : googlePhotosAPI.retrieveFilesFromAlbum(album);

        List<MediaWithName> mediasToUpload = this.getMediasToUpload(files, albumFileNames);

        int numberOfMediasToUpload = mediasToUpload.size();
        if (numberOfMediasToUpload == 0)
            return;

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
                .mapToObj(i -> getUploaderTask(i, numberOfMediasToUpload, mediasToUploadQueue, mediasUploaded,
                        progressLog))
                .forEach(taskExecutor::submit);

        taskExecutor.submit(getWatcherTask(mediasToUploadQueue, mediasToResizeQueue, mediasUploaded, progressLog,
                numberOfMediasToUpload));

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

    private Callable<Void> getResizerTask(int index, ConcurrentLinkedQueue<MediaWithName> mediasToResizeQueue,
            BlockingQueue<MediaWithName> mediasToUpload, ConcurrentLinkedQueue<MediaTaskLog> progressLog) {
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
                        progressLog
                                .add(new MediaTaskLog(MediaTaskLog.Status.RESIZE_NOT_REQUIRED, index, mediaToResize));
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

    private Callable<Void> getUploaderTask(int index, int totalNumMedias,
            BlockingQueue<MediaWithName> mediasToUploadQueue, List<MediaWithName> mediasUploaded,
            ConcurrentLinkedQueue<MediaTaskLog> progressLog) {
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

    private Callable<Void> getWatcherTask(
            BlockingQueue<MediaWithName> mediasToUploadQueue,
            ConcurrentLinkedQueue<MediaWithName> mediasToResizeQueue,
            List<MediaWithName> mediasUploaded,
            ConcurrentLinkedQueue<MediaTaskLog> progressLog,
            int totalMedias) {

        return () -> {
            try (ProgressBar pb = new ProgressBarBuilder()
                    .setTaskName("Syncing")
                    .setInitialMax(totalMedias)
                    .setStyle(ProgressBarStyle.UNICODE_BLOCK)
                    .setUpdateIntervalMillis(100)
                    .build()) {

                Map<String, ProgressBar> workerBars = new LinkedHashMap<>();
                int completedUploads = 0;

                while (completedUploads < totalMedias) {
                    MediaTaskLog log = progressLog.poll();
                    if (log != null) {
                        String mediaName = (log.media() != null) ? log.media().name : "";
                        String workerKey = (log.status().name().startsWith("RESIZE") ? "Resizing" : "Uploading")
                                + log.workerIndex();

                        switch (log.status()) {
                            case RESIZE_STARTED:
                            case UPLOAD_STARTED:
                                String taskLabel = (log.status() == MediaTaskLog.Status.RESIZE_STARTED) ? "Resizing"
                                        : "Uploading";
                                ProgressBar subBar = new ProgressBarBuilder()
                                        .setTaskName(String.format("%-10s: %s", taskLabel, mediaName))
                                        .setInitialMax(1)
                                        .setStyle(ProgressBarStyle.UNICODE_BLOCK)
                                        .build();
                                workerBars.put(workerKey, subBar);
                                break;

                            case RESIZE_COMPLETED:
                            case RESIZE_NOT_REQUIRED:
                                ProgressBar rb = workerBars.remove(workerKey);
                                if (rb != null) {
                                    rb.stepTo(1);
                                    rb.close();
                                }
                                break;

                            case UPLOAD_COMPLETED:
                                ProgressBar ub = workerBars.remove(workerKey);
                                if (ub != null) {
                                    ub.stepTo(1);
                                    ub.close();
                                }
                                completedUploads++;
                                pb.step();
                                break;

                            default:
                                break;
                        }
                    } else {
                        Thread.sleep(50);
                    }
                }
                pb.setExtraMessage("Completed!");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
