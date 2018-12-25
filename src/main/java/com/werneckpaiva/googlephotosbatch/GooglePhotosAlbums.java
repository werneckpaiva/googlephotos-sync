package com.werneckpaiva.googlephotosbatch;

import com.google.api.core.ApiFuture;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.InvalidArgumentException;
import com.google.photos.library.v1.PhotosLibraryClient;
import com.google.photos.library.v1.internal.InternalPhotosLibraryClient;
import com.google.photos.library.v1.proto.*;
import com.google.photos.library.v1.upload.UploadMediaItemRequest;
import com.google.photos.library.v1.upload.UploadMediaItemResponse;
import com.google.photos.library.v1.util.NewMediaItemFactory;
import com.google.rpc.Code;
import com.google.rpc.Status;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;


public class GooglePhotosAlbums {

    private final PhotosLibraryClient photosLibraryClient;
    private Map<String, Album> albums = null;

    public static final int MAX_FREE_DIMENSION = 2048;

    private static final Pattern JPEG_PATTERN = Pattern.compile("\\.jpg$", Pattern.CASE_INSENSITIVE);

    private static class MediaWithName{
        public final String name;
        public final File file;
        public MediaWithName(String name, File file){
            this.file = file;
            this.name = name;
        }
    }

    private static Comparator<NewMediaItem> MEDIA_COMPARATOR = new Comparator<NewMediaItem>() {
        @Override
        public int compare(NewMediaItem media1, NewMediaItem media2) {
            return media1.getDescription().compareTo(media2.getDescription());
        }
    };

    public GooglePhotosAlbums(PhotosLibraryClient photosLibraryClient){
        this.photosLibraryClient = photosLibraryClient;
    }

    public Map<String, Album> listAllAlbuns(){
        System.out.print("Loading albums ");
        ListAlbumsRequest listAlbumsRequest = ListAlbumsRequest.newBuilder()
                .setExcludeNonAppCreatedData(false)
                .setPageSize(50)
                .build();

        long startTime = System.currentTimeMillis();
        InternalPhotosLibraryClient.ListAlbumsPagedResponse listAlbumsResponse = photosLibraryClient.listAlbums(listAlbumsRequest);
        Map<String, Album> allAlbums = new HashMap<>();
        int i=1;
        for (Album album : listAlbumsResponse.iterateAll()){
            if (i++ % 100 == 0) System.out.print(".");
            allAlbums.put(album.getTitle(), album);
        }
        System.out.println(" " + allAlbums.size()+ " albuns loaded (" + (System.currentTimeMillis() - startTime) + " ms)");
        return allAlbums;
    }

    public Album getAlbum(String albumName){
        if (this.albums == null){
            this.albums = listAllAlbuns();
        }
        return this.albums.get(albumName);
    }

    public Album createAlbum(String albumName) {
        System.out.println("Creating album " + albumName);
        Album album = photosLibraryClient.createAlbum(albumName);
        this.albums.put(albumName, album);
        return album;
    }

    public void batchUploadFiles(Album album, List<File> files) {
        try {
            System.out.println("Album: " + album.getTitle());
            final Set<String> albumFileNames = retrieveFilesFromAlbum(album);

            List<MediaWithName> mediasToUpload = files.stream()
                    .map(file -> new MediaWithName(AlbumUtils.file2MediaName(file), file))
                    .filter(media -> !albumFileNames.contains(media.name))
                    .collect(Collectors.toList());

            if (mediasToUpload.size() == 0) return;

            if (!album.getIsWriteable()){
                System.out.println("ERROR: album is not writable");
                return;
            }

            ForkJoinPool customThreadPool = new ForkJoinPool(4);
            List<NewMediaItem> newMediaItems =
                    customThreadPool.submit(() -> mediasToUpload.parallelStream()
                    .map(media ->
                            (JPEG_PATTERN.matcher(media.file.getName()).find()) ?
                                    new MediaWithName(media.name, ImageUtils.resizeJPGImage(media.file, MAX_FREE_DIMENSION)) :
                                    media
                    )
                    .map(media -> uploadSingleFile(media.name, media.file))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList())).get();

            newMediaItems.sort(MEDIA_COMPARATOR);

            if (!newMediaItems.isEmpty()) {
                System.out.println("Adding " + newMediaItems.size() + " medias to album: " + album.getTitle());
                Iterator<NewMediaItem> newMediasIterator = newMediaItems.iterator();
                while (newMediasIterator.hasNext()) {
                    List<NewMediaItem> mediasToSubmit = new ArrayList<>();
                    while (mediasToSubmit.size() < 50 && newMediasIterator.hasNext())
                        mediasToSubmit.add(newMediasIterator.next());

                    BatchCreateMediaItemsRequest albumMediaItemsRequest = BatchCreateMediaItemsRequest.newBuilder()
                            .setAlbumId(album.getId())
                            .addAllNewMediaItems(mediasToSubmit)
                            .build();
                    ApiFuture<BatchCreateMediaItemsResponse> apiFuture = photosLibraryClient.batchCreateMediaItemsCallable()
                            .futureCall(albumMediaItemsRequest);
                    BatchCreateMediaItemsResponse mediasToAlbumResponse = apiFuture.get();
                    for (NewMediaItemResult itemsResponse : mediasToAlbumResponse.getNewMediaItemResultsList()) {
                        Status status = itemsResponse.getStatus();
                        if (status.getCode() != Code.OK_VALUE) {
                            System.out.println("Error setting item to album: " + status.getCode() + " - " + status.getMessage());
                        }
                    }

                }
            }
        } catch(ExecutionException | InterruptedException e){
            System.out.println("Error adding medias to album");
            e.printStackTrace();
        }
    }

    private NewMediaItem uploadSingleFile(String mediaName, File file) {
        System.out.println("Uploading " + mediaName);
        try {
            UploadMediaItemRequest uploadRequest =
                    UploadMediaItemRequest.newBuilder()
                            .setFileName(mediaName)
                            .setDataFile(new RandomAccessFile(file, "r"))
                            .build();
            UploadMediaItemResponse uploadResponse = photosLibraryClient.uploadMediaItem(uploadRequest);
            if (uploadResponse.getError().isPresent()) {
                UploadMediaItemResponse.Error error = uploadResponse.getError().get();
                throw new IOException(error.getCause());
            }
            String uploadToken = uploadResponse.getUploadToken().get();
            return NewMediaItemFactory.createNewMediaItem(uploadToken, mediaName);
        } catch (IOException | ApiException e) {
            System.out.println(" Can't upload file " + file);
            e.printStackTrace();
            return null;
        }
    }

    private Set<String> retrieveFilesFromAlbum(Album album) {
        InternalPhotosLibraryClient.SearchMediaItemsPagedResponse response = photosLibraryClient.searchMediaItems(album.getId());
        return StreamSupport.stream(response.iterateAll().spliterator(), false)
                .map(MediaItem::getFilename)
                .collect(Collectors.toSet());
    }
}
