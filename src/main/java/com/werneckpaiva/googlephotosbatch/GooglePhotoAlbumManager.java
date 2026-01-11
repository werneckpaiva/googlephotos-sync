package com.werneckpaiva.googlephotosbatch;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private File albumsCache = null;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public static final int MAX_FREE_DIMENSION = 4608;

    public GooglePhotoAlbumManager(GooglePhotosAPI googlePhotosAPI) {
        this.googlePhotosAPI = googlePhotosAPI;
    }

    public void setAlbumsCache(File albumsCache) {
        this.albumsCache = albumsCache;
    }

    public Map<String, Album> listAllAlbums() throws PermissionDeniedToLoadAlbumsException {
        if (albumsCache != null && albumsCache.exists()) {
            Map<String, Album> cachedAlbums = loadAlbumsFromCache();
            if (cachedAlbums != null) {
                return cachedAlbums;
            }
        }

        logger.info("Loading albums from Google Photos API");

        long startTime = System.currentTimeMillis();
        Map<String, Album> allAlbums = new HashMap<>();
        byte retry = 0;
        System.out.print("Loading albums ");
        while (retry++ < 100) {
            try {
                int i = 1;
                for (Album album : googlePhotosAPI.getAllAlbums()) {
                    if (i++ % 100 == 0) {
                        System.out.print(".");
                    }
                    allAlbums.put(album.title(), album);
                }
                break;
            } catch (PermissionDeniedException | UnauthenticatedException e) {
                throw new PermissionDeniedToLoadAlbumsException(e);
            } catch (RuntimeException e) {
                if (isAuthError(e)) {
                    throw new PermissionDeniedToLoadAlbumsException(e);
                }
                e.printStackTrace(System.err);
                System.out.print("x");
            }
        }

        if (albumsCache != null) {
            saveAlbumsToCache(allAlbums);
        }

        System.out.printf(" %d albums loaded (%d ms)\n", allAlbums.size(), (System.currentTimeMillis() - startTime));
        return allAlbums;
    }

    private Map<String, Album> loadAlbumsFromCache() {
        logger.info("Loading albums from cache file: {}", albumsCache.getAbsolutePath());
        long startTime = System.currentTimeMillis();
        Map<String, Album> allAlbums = new HashMap<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(albumsCache))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty())
                    continue;
                try {
                    Album album = objectMapper.readValue(line, Album.class);
                    allAlbums.put(album.title(), album);
                } catch (Exception e) {
                    logger.warn("Failed to parse album line from cache: {}", line);
                }
            }
            System.out.printf(" %d albums loaded from cache (%d ms)\n", allAlbums.size(),
                    (System.currentTimeMillis() - startTime));
            return allAlbums;
        } catch (java.io.IOException e) {
            logger.error("Error reading albums from cache", e);
            return null;
        }
    }

    private void saveAlbumsToCache(Map<String, Album> allAlbums) {
        logger.info("Saving albums to cache file: {}", albumsCache.getAbsolutePath());
        try (java.io.FileWriter fw = new java.io.FileWriter(albumsCache)) {
            for (Album album : allAlbums.values()) {
                fw.write(objectMapper.writeValueAsString(album));
                fw.write("\n");
            }
        } catch (java.io.IOException e) {
            logger.error("Error writing albums to cache", e);
        }
    }

    private void appendAlbumToCache(Album album) {
        if (albumsCache == null)
            return;
        logger.info("Appending album {} to cache file: {}", album.title(), albumsCache.getAbsolutePath());
        try (java.io.FileWriter fw = new java.io.FileWriter(albumsCache, true)) {
            fw.write(objectMapper.writeValueAsString(album));
            fw.write("\n");
        } catch (java.io.IOException e) {
            logger.error("Error writing album to cache", e);
        }
    }

    private boolean skipAlbumLoad = false;

    private String albumId = null;

    public void setSkipAlbumLoad(boolean skipAlbumLoad) {
        this.skipAlbumLoad = skipAlbumLoad;
    }

    public void setAlbumId(String albumId) {
        this.albumId = albumId;
    }

    private void ensureAlbumsLoaded() throws PermissionDeniedToLoadAlbumsException {
        if (this.albums == null) {
            if (this.skipAlbumLoad) {
                this.albums = new HashMap<>();
            } else {
                this.albums = listAllAlbums();
            }
        }
    }

    public Album getAlbum(String albumName) throws PermissionDeniedToLoadAlbumsException {
        if (this.albumId != null) {
            try {
                Album album = googlePhotosAPI.getAlbum(this.albumId);
                if (this.albums == null) {
                    this.albums = new HashMap<>();
                }
                if (album != null) {
                    this.albums.put(albumName, album);
                }
                return album;
            } catch (RuntimeException e) {
                if (isAuthError(e)) {
                    throw new PermissionDeniedToLoadAlbumsException(e);
                }
                throw e;
            }
        }
        ensureAlbumsLoaded();
        return this.albums.get(albumName);
    }

    public Album createAlbum(String albumName) throws PermissionDeniedToLoadAlbumsException {
        logger.info("Creating new album {}", albumName);
        ensureAlbumsLoaded();
        try {
            Album album = googlePhotosAPI.createAlbum(albumName);
            this.albums.put(albumName, album);
            appendAlbumToCache(album);
            return album;
        } catch (RuntimeException e) {
            if (isAuthError(e)) {
                throw new PermissionDeniedToLoadAlbumsException(e);
            }
            throw e;
        }
    }

    public void batchUploadFiles(Album album, List<File> files) throws PermissionDeniedToLoadAlbumsException {
        logger.info("Album: {}", album.title());
        Set<String> albumFileNames;
        try {
            albumFileNames = this.skipAlbumLoad ? new HashSet<>()
                    : googlePhotosAPI.retrieveFilesFromAlbum(album).stream()
                            .map(GooglePhotosAPI.MediaItemInfo::filename)
                            .collect(Collectors.toSet());
        } catch (RuntimeException e) {
            if (isAuthError(e)) {
                throw new PermissionDeniedToLoadAlbumsException(e);
            }
            throw e;
        }

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
        ConcurrentLinkedQueue<SyncStatusWatcher.MediaTaskLog> progressLog = new ConcurrentLinkedQueue<>();

        Future<Void> watcherFuture = taskExecutor.submit(SyncStatusWatcher.getWatcherTask(album, mediasToUploadQueue,
                mediasToResizeQueue, mediasUploaded, progressLog,
                numberOfMediasToUpload));

        IntStream.range(0, numCores - 1)
                .mapToObj(i -> getResizerTask(i, mediasToResizeQueue, mediasToUploadQueue, progressLog))
                .forEach(taskExecutor::submit);

        // Run uploader tasks and chain saver task after all uploads complete
        CompletableFuture<?>[] uploaderFutures = IntStream.range(0, 1)
                .mapToObj(i -> CompletableFuture.runAsync(() -> {
                    try {
                        getUploaderTask(i, numberOfMediasToUpload, mediasToUploadQueue, mediasUploaded, progressLog)
                                .call();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, taskExecutor))
                .toArray(CompletableFuture[]::new);

        // Chain saver task to run after all uploaders complete
        CompletableFuture<Void> uploadAndSaveFuture = CompletableFuture.allOf(uploaderFutures)
                .thenRunAsync(() -> {
                    try {
                        getSaverTask(album, mediasUploaded, progressLog).call();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, taskExecutor);

        try {
            // Wait for both the upload/save chain and the watcher task to complete
            uploadAndSaveFuture.get();
            watcherFuture.get();
        } catch (InterruptedException | ExecutionException e) {
            Throwable cause = e.getCause();
            if (isAuthError(cause)) {
                throw new PermissionDeniedToLoadAlbumsException(new RuntimeException(cause));
            }
            throw new RuntimeException("Error waiting for upload tasks", e);
        } finally {
            taskExecutor.shutdown();
            try {
                taskExecutor.awaitTermination(1, TimeUnit.DAYS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private Callable<Void> getResizerTask(int index, ConcurrentLinkedQueue<MediaWithName> mediasToResizeQueue,
            BlockingQueue<MediaWithName> mediasToUpload,
            ConcurrentLinkedQueue<SyncStatusWatcher.MediaTaskLog> progressLog) {
        return () -> {
            while (!mediasToResizeQueue.isEmpty()) {
                MediaWithName mediaToResize = mediasToResizeQueue.poll();
                if (mediaToResize != null) {
                    progressLog.add(new SyncStatusWatcher.MediaTaskLog(
                            SyncStatusWatcher.MediaTaskLog.Status.RESIZE_STARTED, index, mediaToResize));
                    if (ImageUtils.isJPEG(mediaToResize.file())) {
                        File resizedFile = ImageUtils.resizeJPGImage(mediaToResize.file(), MAX_FREE_DIMENSION);
                        mediaToResize = new MediaWithName(mediaToResize.name(), resizedFile);
                        progressLog.add(new SyncStatusWatcher.MediaTaskLog(
                                SyncStatusWatcher.MediaTaskLog.Status.RESIZE_COMPLETED, index, mediaToResize));
                    } else {
                        progressLog
                                .add(new SyncStatusWatcher.MediaTaskLog(
                                        SyncStatusWatcher.MediaTaskLog.Status.RESIZE_NOT_REQUIRED, index,
                                        mediaToResize));
                    }
                    try {
                        mediasToUpload.put(mediaToResize);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            progressLog.add(new SyncStatusWatcher.MediaTaskLog(
                    SyncStatusWatcher.MediaTaskLog.Status.RESIZE_ALL_COMPLETED, index));
            return null;
        };
    }

    private Callable<Void> getUploaderTask(int index, int totalNumMedias,
            BlockingQueue<MediaWithName> mediasToUploadQueue, List<MediaWithName> mediasUploaded,
            ConcurrentLinkedQueue<SyncStatusWatcher.MediaTaskLog> progressLog) {
        AtomicInteger numMediasToUpload = new AtomicInteger(totalNumMedias);
        return () -> {
            while (numMediasToUpload.getAndDecrement() > 0) {
                try {
                    MediaWithName media = mediasToUploadQueue.take();
                    progressLog.add(new SyncStatusWatcher.MediaTaskLog(
                            SyncStatusWatcher.MediaTaskLog.Status.UPLOAD_STARTED, index, media));
                    String newMediaToken = googlePhotosAPI.uploadSingleFile(media.name(), media.file());
                    if (newMediaToken != null) {
                        progressLog.add(new SyncStatusWatcher.MediaTaskLog(
                                SyncStatusWatcher.MediaTaskLog.Status.UPLOAD_COMPLETED, index, media));
                        mediasUploaded.add(new MediaWithName(media.name(), media.file(), newMediaToken));
                    } else {
                        progressLog.add(new SyncStatusWatcher.MediaTaskLog(
                                SyncStatusWatcher.MediaTaskLog.Status.UPLOAD_FAILED, index, media));
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            progressLog.add(new SyncStatusWatcher.MediaTaskLog(
                    SyncStatusWatcher.MediaTaskLog.Status.UPLOAD_ALL_COMPLETED, index));
            return null;
        };
    }

    private Callable<Void> getSaverTask(
            Album album,
            List<MediaWithName> mediasUploaded,
            ConcurrentLinkedQueue<SyncStatusWatcher.MediaTaskLog> progressLog) {

        return () -> {
            Collections.sort(mediasUploaded);

            if (!mediasUploaded.isEmpty()) {
                progressLog.add(SyncStatusWatcher.MediaTaskLog
                        .forSave(SyncStatusWatcher.MediaTaskLog.Status.SAVE_STARTED, mediasUploaded.size()));

                googlePhotosAPI.saveToAlbum(album,
                        mediasUploaded.stream()
                                .map(MediaWithName::uploadToken)
                                .collect(Collectors.toList()));

                progressLog.add(SyncStatusWatcher.MediaTaskLog
                        .forSave(SyncStatusWatcher.MediaTaskLog.Status.SAVE_COMPLETED, mediasUploaded.size()));
            } else {
                // No medias to save, signal completion immediately
                progressLog.add(SyncStatusWatcher.MediaTaskLog
                        .forSave(SyncStatusWatcher.MediaTaskLog.Status.SAVE_COMPLETED, 0));
            }

            return null;
        };
    }

    private List<MediaWithName> getMediasToUpload(List<File> files, Set<String> albumFileNames) {
        return files.stream()
                .map(file -> new MediaWithName(AlbumUtils.file2MediaName(file), file))
                .filter(media -> !albumFileNames.contains(media.name()))
                .collect(Collectors.toList());
    }

    private boolean isAuthError(Throwable e) {
        if (e == null)
            return false;
        String message = e.getMessage();
        if (message != null && (message.contains("UNAUTHENTICATED") || message.contains("invalid_grant"))) {
            return true;
        }
        return isAuthError(e.getCause());
    }

}
