package com.werneckpaiva.googlephotosbatch;

import com.werneckpaiva.googlephotosbatch.service.Album;
import com.werneckpaiva.googlephotosbatch.service.GooglePhotosAPI;
import com.werneckpaiva.googlephotosbatch.utils.AlbumUtils;
import com.werneckpaiva.googlephotosbatch.utils.ImageUtils;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


public class GooglePhotoAlbumManager {

    private final GooglePhotosAPI googlePhotosAPI;

    private Map<String, Album> albums = null;

    public static final int MAX_FREE_DIMENSION = 2048;

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

    public Map<String, Album> listAllAlbums() {
        System.out.print("Loading albums ");

        long startTime = System.currentTimeMillis();
        Map<String, Album> allAlbums = new HashMap<>();
        byte retry = 0;
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
            } catch (RuntimeException e) {
                System.out.print("x");
            }
        }
        System.out.printf(" %d albums loaded (%d ms)\n", allAlbums.size(), (System.currentTimeMillis() - startTime));
        return allAlbums;

    }

    public Album getAlbum(String albumName) {
        if (this.albums == null) {
            this.albums = listAllAlbums();
        }
        return this.albums.get(albumName);
    }

    public Album createAlbum(String albumName) {
        System.out.printf("Creating album %s\n", albumName);
        Album album = googlePhotosAPI.createAlbum(albumName);
        this.albums.put(albumName, album);
        return album;
    }

    public void batchUploadFiles(Album album, List<File> files) {
        System.out.printf("Album: %s\n", album.title());
        final Set<String> albumFileNames = googlePhotosAPI.retrieveFilesFromAlbum(album);

        List<MediaWithName> mediasToUpload = this.getMediasToUpload(files, albumFileNames);

        if (mediasToUpload.isEmpty()) return;

        if (!album.isWriteable()) {
            System.out.println("ERROR: album is not writable");
            return;
        }

        System.out.printf("Uploading %d medias\n", mediasToUpload.size());

        int numCores = Runtime.getRuntime().availableProcessors() - 1;
        ExecutorService taskExecutor = Executors.newFixedThreadPool(numCores + 1);
        ConcurrentLinkedQueue<MediaWithName> mediasToResizeQueue = new ConcurrentLinkedQueue<>(mediasToUpload);
        List<MediaWithName> mediasUploaded = Collections.synchronizedList(new ArrayList<>(mediasToUpload.size()));
        BlockingQueue<MediaWithName> mediasToUploadQueue = new LinkedBlockingQueue<>();
        for (int i = 0; i < numCores; i++) {
            taskExecutor.submit(getResizerTask(i, mediasToResizeQueue, mediasToUploadQueue));
        }
        taskExecutor.submit(getUploaderTask(mediasToUpload.size(), mediasToUploadQueue, mediasUploaded));
        //TODO: Implement stats
        taskExecutor.shutdown();
        try {
            taskExecutor.awaitTermination(1, TimeUnit.DAYS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        Collections.sort(mediasUploaded);

        if (!mediasUploaded.isEmpty()) {
            System.out.printf("Saving %d medias to album %s\n", mediasUploaded.size(), album.title());
            googlePhotosAPI.saveToAlbum(album,
                    mediasUploaded.stream()
                            .map(MediaWithName::uploadToken)
                            .collect(Collectors.toList()));
        }

//        Collections.sort(mediasToUploadSync);

//        double totalSizeMb = resizedMediasToUpload.stream()
//                .mapToDouble(mediaWithFile -> mediaWithFile.file.length() / 1024.0 / 1024.0).sum();
//        double processedSizeMb = 0;
//        int remainingFiles = resizedMediasToUpload.size();
//        Iterator<MediaWithName> newMediasIterator = resizedMediasToUpload.iterator();
//        while (newMediasIterator.hasNext()) {
//            List<String> uploadedTokens = new ArrayList<>();
//            long start = System.currentTimeMillis();
//            double batchSizeMb = 0;
//            while (uploadedTokens.size() < 10 && newMediasIterator.hasNext()) {
//                MediaWithName media = newMediasIterator.next();
//                double fileSizeMb = media.file.length() / 1024.0 / 1024.0;
//                String newMediaToken = googlePhotosAPI.uploadSingleFile(media.name, media.file);
//                batchSizeMb += fileSizeMb;
//                processedSizeMb += fileSizeMb;
//                if (newMediaToken != null) {
//                    uploadedTokens.add(newMediaToken);
//                }
//            }
//            if (uploadedTokens.isEmpty()) continue;
//            googlePhotosAPI.saveToAlbum(album, uploadedTokens);
//            long elapsedTimeSec = (System.currentTimeMillis() - start) / 1000;
//            double remainingSizeMb = (totalSizeMb - processedSizeMb);
//            remainingFiles -= uploadedTokens.size();
//            double etaMin = ((batchSizeMb / elapsedTimeSec) * remainingSizeMb) / 60.0;
//            System.out.printf("%d items (%.1f MB) uploaded in %ds to album: %s%n", uploadedTokens.size(), batchSizeMb, elapsedTimeSec, album.title());
//            if (remainingFiles > 0) {
//                System.out.printf("Remaining %d files (%.1f MB) to upload. ETA: %.1f min%n", remainingFiles, remainingSizeMb, etaMin);
//            }
//        }
    }

    private Runnable getUploaderTask(int totalNumMedias, BlockingQueue<MediaWithName> mediasToUploadQueue, List<MediaWithName> mediasUploaded) {
        AtomicInteger numMediasToUpload = new AtomicInteger(totalNumMedias);
        return () -> {
            Integer currentMediasToUpload;
            while ((currentMediasToUpload = numMediasToUpload.getAndDecrement()) > 0) {
                System.out.printf("Remaining medias to upload: %d\n", currentMediasToUpload);
                try {
                    MediaWithName media = mediasToUploadQueue.take();
                    String newMediaToken = googlePhotosAPI.uploadSingleFile(media.name, media.file);
                    if (newMediaToken != null) {
                        mediasUploaded.add(new MediaWithName(media.name, media.file, newMediaToken));
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    private static Runnable getResizerTask(int index, ConcurrentLinkedQueue<MediaWithName> mediasToResizeQueue, BlockingQueue<MediaWithName> mediasToUpload) {
        return () -> {
            while (!mediasToResizeQueue.isEmpty()) {
                System.out.printf("Medias to resize: %d\n", mediasToResizeQueue.size());
                MediaWithName mediaToResize = mediasToResizeQueue.poll();
                if (mediaToResize != null) {
                    if (ImageUtils.isJPEG(mediaToResize.file)) {
                        File resizedFile = ImageUtils.resizeJPGImage(mediaToResize.file, MAX_FREE_DIMENSION);
                        mediaToResize = new MediaWithName(mediaToResize.name, resizedFile);
                    }
                    try {
                        mediasToUpload.put(mediaToResize);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            System.out.printf("Finalizing task %d\n", index);
        };
    }

    private List<MediaWithName> getMediasToUpload(List<File> files, Set<String> albumFileNames) {
        return files.stream()
                .map(file -> new MediaWithName(AlbumUtils.file2MediaName(file), file))
                .filter(media -> !albumFileNames.contains(media.name))
                .collect(Collectors.toList());
    }

}
