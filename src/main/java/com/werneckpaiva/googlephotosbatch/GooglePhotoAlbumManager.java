package com.werneckpaiva.googlephotosbatch;

import com.werneckpaiva.googlephotosbatch.service.Album;
import com.werneckpaiva.googlephotosbatch.service.GooglePhotosAPI;
import com.werneckpaiva.googlephotosbatch.utils.AlbumUtils;
import com.werneckpaiva.googlephotosbatch.utils.ImageUtils;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;


public class GooglePhotoAlbumManager {

    private final GooglePhotosAPI googlePhotosAPI;

    private Map<String, Album> albums = null;

    public static final int MAX_FREE_DIMENSION = 2048;

    private record MediaWithName(String name, File file) implements Comparable<MediaWithName> {
        @Override
        public int compareTo(MediaWithName other) {
            return this.name.compareTo(other.name);
        }
    }

    public GooglePhotoAlbumManager(GooglePhotosAPI googlePhotosAPI) {
        this.googlePhotosAPI = googlePhotosAPI;
    }

    public Map<String, Album> listAllAlbums() {
        System.out.print("Loading albums ");

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
//            } catch (RuntimeException e) {
//                System.out.print("x");
//            }
//        }
        System.out.println(" " + allAlbums.size() + " albums loaded (" + (System.currentTimeMillis() - startTime) + " ms)");
        return allAlbums;

    }

    public Album getAlbum(String albumName) {
        if (this.albums == null) {
            this.albums = listAllAlbums();
        }
        return this.albums.get(albumName);
    }

    public Album createAlbum(String albumName) {
        System.out.println("Creating album " + albumName);
        Album album = googlePhotosAPI.createAlbum(albumName);
        this.albums.put(albumName, album);
        return album;
    }

    public void batchUploadFiles(Album album, List<File> files) {
        System.out.println("Album: " + album.title());
        final Set<String> albumFileNames = googlePhotosAPI.retrieveFilesFromAlbum(album);

        List<MediaWithName> mediasToUpload = this.getMediasToUpload(files, albumFileNames);

        if (mediasToUpload.isEmpty()) return;

        if (!album.isWriteable()) {
            System.out.println("ERROR: album is not writable");
            return;
        }

        System.out.println("Uploading " + mediasToUpload.size() + " medias to album: " + album.title());

        List<MediaWithName> resizedMediasToUpload = resizeMediasToUpload(mediasToUpload);

        double totalSizeMb = resizedMediasToUpload.stream()
                .mapToDouble(mediaWithFile -> mediaWithFile.file.length() / 1024.0 / 1024.0).sum();
        double processedSizeMb = 0;
        int remainingFiles = resizedMediasToUpload.size();
        Iterator<MediaWithName> newMediasIterator = resizedMediasToUpload.iterator();
        while (newMediasIterator.hasNext()) {
            List<String> uploadedTokens = new ArrayList<>();
            long start = System.currentTimeMillis();
            double batchSizeMb = 0;
            while (uploadedTokens.size() < 10 && newMediasIterator.hasNext()) {
                MediaWithName media = newMediasIterator.next();
                double fileSizeMb = media.file.length() / 1024.0 / 1024.0;
                String newMediaToken = googlePhotosAPI.uploadSingleFile(media.name, media.file);
                batchSizeMb += fileSizeMb;
                processedSizeMb += fileSizeMb;
                if (newMediaToken != null) {
                    uploadedTokens.add(newMediaToken);
                }
            }
            if (uploadedTokens.isEmpty()) continue;
            googlePhotosAPI.saveToAlbum(album, uploadedTokens);
            long elapsedTimeSec = (System.currentTimeMillis() - start) / 1000;
            double remainingSizeMb = (totalSizeMb - processedSizeMb);
            remainingFiles -= uploadedTokens.size();
            double etaMin = ((batchSizeMb / elapsedTimeSec) * remainingSizeMb) / 60.0;
            System.out.printf("%d items (%.1f MB) uploaded in %ds to album: %s%n", uploadedTokens.size(), batchSizeMb, elapsedTimeSec, album.title());
            if (remainingFiles > 0) {
                System.out.printf("Remaining %d files (%.1f MB) to upload. ETA: %.1f min%n", remainingFiles, remainingSizeMb, etaMin);
            }
        }
    }

    private static List<MediaWithName> resizeMediasToUpload(List<MediaWithName> mediasToUpload) {
        int numCores = Runtime.getRuntime().availableProcessors();
        ExecutorService taskExecutor = Executors.newFixedThreadPool(numCores);
        ConcurrentLinkedQueue<MediaWithName> mediasToResizeQueue = new ConcurrentLinkedQueue<>(mediasToUpload);
        List<MediaWithName> mediasToUploadSync = Collections.synchronizedList(new ArrayList<>(mediasToUpload.size()));
        for (int i = 0; i< numCores; i++) {
            taskExecutor.submit(getResizerTask(i, mediasToResizeQueue, mediasToUploadSync));
        }
        taskExecutor.shutdown();
        try {
            taskExecutor.awaitTermination(1, TimeUnit.DAYS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        Collections.sort(mediasToUploadSync);
        return mediasToUploadSync;
    }

//    private Runnable getUploaderTask(Album album, List<MediaWithName> mediasToUpload, BlockingQueue<MediaWithName> mediasToUploadQueue) {
//        AtomicInteger numMediasToUpload = new AtomicInteger(mediasToUpload.size());
//        Runnable uploaderTask = () -> {
//            List<String> uploadedTokens = new ArrayList<>();
//            while(numMediasToUpload.getAndDecrement() > 0){
//                MediaWithName media = mediasToUploadQueue.take();
//                System.out.println("Uploading " + media.name);
//                String newMediaToken = googlePhotosAPI.uploadSingleFile(media.name, media.file);
//                if (newMediaToken != null) {
//                    uploadedTokens.add(newMediaToken);
//                }
//                if (uploadedTokens.size() >= 5 || numMediasToUpload.get() == 0){
//                    System.out.println("Saving to album " + album.title());
//                    googlePhotosAPI.saveToAlbum(album, uploadedTokens);
//                    uploadedTokens.clear();
//                }
//            }
//        };
//        return uploaderTask;
//    }

    private static Runnable getResizerTask(int index, ConcurrentLinkedQueue<MediaWithName> mediasToResizeQueue, List<MediaWithName> mediasToUpload) {
        return () -> {
            while (!mediasToResizeQueue.isEmpty()) {
                MediaWithName mediaToResize = mediasToResizeQueue.poll();
                if (mediaToResize != null) {
                    if (ImageUtils.isJPEG(mediaToResize.file)) {
                        System.out.println(index + " resizing "+mediaToResize.name);
                        File resizedFile = ImageUtils.resizeJPGImage(mediaToResize.file, MAX_FREE_DIMENSION);
                        mediaToResize = new MediaWithName(mediaToResize.name, resizedFile);
                    }
                    mediasToUpload.add(mediaToResize);
                }
            }
        };
    }

    private List<MediaWithName> getMediasToUpload(List<File> files, Set<String> albumFileNames) {
        return files.stream()
                .map(file -> new MediaWithName(AlbumUtils.file2MediaName(file), file))
                .filter(media -> !albumFileNames.contains(media.name))
                .collect(Collectors.toList());
    }

}
