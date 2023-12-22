package com.werneckpaiva.googlephotosbatch;

import com.werneckpaiva.googlephotosbatch.service.Album;
import com.werneckpaiva.googlephotosbatch.service.GooglePhotosAPI;
import com.werneckpaiva.googlephotosbatch.utils.AlbumUtils;
import com.werneckpaiva.googlephotosbatch.utils.ImageUtils;

import java.io.File;
import java.util.*;
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
        Iterator<MediaWithName> newMediasIterator = mediasToUpload.iterator();
        double totalSizeMb = mediasToUpload.stream()
                .mapToDouble(mediaWithFile -> mediaWithFile.file.length() / 1024.0 / 1024.0).sum();
        double processedSizeMb = 0;
        int remainingFiles = mediasToUpload.size();
        while (newMediasIterator.hasNext()) {
            List<String> uploadedTokens = new ArrayList<>();
            long start = System.currentTimeMillis();
            double batchSizeMb = 0;
            while (uploadedTokens.size() < 10 && newMediasIterator.hasNext()) {
                MediaWithName media = newMediasIterator.next();
                double fileSizeMb = media.file.length() / 1024.0 / 1024.0;
                if (ImageUtils.isJPEG(media.file)) {
                    File resizedFile = ImageUtils.resizeJPGImage(media.file, MAX_FREE_DIMENSION);
                    media = new MediaWithName(media.name, resizedFile);
                }
                String newMediaToken = googlePhotosAPI.uploadSingleFile(media.name, media.file);
                batchSizeMb += fileSizeMb;
                processedSizeMb += fileSizeMb;
                if (newMediaToken != null) {
                    uploadedTokens.add(newMediaToken);
                }
            }
            if (uploadedTokens.size() == 0) continue;
            googlePhotosAPI.saveToAlbum(album, uploadedTokens);
            long elapsedTimeSec = (System.currentTimeMillis() - start) / 1000;
            double remainingSizeMb = (totalSizeMb - processedSizeMb);
            remainingFiles -= uploadedTokens.size();
            double etaMin = ((batchSizeMb / elapsedTimeSec) * remainingSizeMb) / 60.0;
            System.out.println(String.format("%d items (%.1f MB) uploaded in %ds to album: %s", uploadedTokens.size(), batchSizeMb, elapsedTimeSec, album.title()));
            if (remainingFiles > 0) {
                System.out.println(String.format("Remaining %d files (%.1f MB) to upload. ETA: %.1f min", remainingFiles, remainingSizeMb, etaMin));
            }
        }
    }

    private List<MediaWithName> getMediasToUpload(List<File> files, Set<String> albumFileNames) {
        List<MediaWithName> mediasToUpload = files.stream()
                .map(file -> new MediaWithName(AlbumUtils.file2MediaName(file), file))
                .filter(media -> !albumFileNames.contains(media.name))
                .sorted()
                .collect(Collectors.toList());
        return mediasToUpload;
    }

}
