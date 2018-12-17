package com.werneckpaiva.googlephotosbatch;

import com.google.photos.library.v1.PhotosLibraryClient;
import com.google.photos.library.v1.internal.InternalPhotosLibraryClient;
import com.google.photos.library.v1.proto.Album;
import com.google.photos.library.v1.proto.ListAlbumsRequest;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GooglePhotosAlbums {

    private final PhotosLibraryClient photosLibraryClient;
    private Map<String, Album> albums = null;

    public GooglePhotosAlbums(PhotosLibraryClient photosLibraryClient){
        this.photosLibraryClient = photosLibraryClient;
    }

    public Map<String, Album> listAllAlbuns(){
        System.out.println("Loading albums... ");
        ListAlbumsRequest listAlbumsRequest = ListAlbumsRequest.newBuilder()
                .setExcludeNonAppCreatedData(false)
                .setPageSize(50)
                .build();

        long startTime = System.currentTimeMillis();
        InternalPhotosLibraryClient.ListAlbumsPagedResponse listAlbumsResponse = photosLibraryClient.listAlbums(listAlbumsRequest);
        Map<String, Album> allAlbums = new HashMap<>();
        for (Album album : listAlbumsResponse.iterateAll()){
            allAlbums.put(album.getTitle(), album);
        }
        System.out.println(allAlbums.size()+ " albuns loaded (" + (System.currentTimeMillis() - startTime) + " ms)");
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

    }
}
