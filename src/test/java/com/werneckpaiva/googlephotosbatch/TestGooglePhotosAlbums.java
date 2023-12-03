package com.werneckpaiva.googlephotosbatch;


import com.google.api.core.ApiFuture;
import com.google.photos.library.v1.PhotosLibraryClient;

import static org.mockito.Mockito.*;

import com.google.photos.library.v1.internal.InternalPhotosLibraryClient;
import com.google.photos.library.v1.proto.Album;
import com.google.photos.library.v1.proto.BatchCreateMediaItemsRequest;
import com.google.photos.library.v1.proto.BatchCreateMediaItemsResponse;
import com.google.photos.library.v1.proto.ListAlbumsRequest;
import com.google.photos.library.v1.upload.UploadMediaItemRequest;
import com.google.photos.library.v1.upload.UploadMediaItemResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import com.google.api.gax.rpc.UnaryCallable;


public class TestGooglePhotosAlbums {

    @Test
    public void testListAlbumsEmptyList() {
        // Setup
        InternalPhotosLibraryClient.ListAlbumsPagedResponse listAlbumsResponse = mock(InternalPhotosLibraryClient.ListAlbumsPagedResponse.class);
        PhotosLibraryClient photosLibraryClient = mock(PhotosLibraryClient.class);
        when(photosLibraryClient.listAlbums(any(ListAlbumsRequest.class))).thenReturn(listAlbumsResponse);

        // Execute
        GooglePhotosAlbums googlePhotosAlbums = new GooglePhotosAlbums(photosLibraryClient);
        Map<String, Album> albumMap = googlePhotosAlbums.listAllAlbuns();
        Assertions.assertTrue(albumMap.isEmpty());
    }

    @Test
    public void testListAlbumsNonEmpty() {
        // Setup
        InternalPhotosLibraryClient.ListAlbumsPagedResponse listAlbumsResponse = mock(InternalPhotosLibraryClient.ListAlbumsPagedResponse.class);
        List<Album> albums = Arrays.asList(mock(Album.class), mock(Album.class), mock(Album.class));
        when(albums.get(0).getTitle()).thenReturn("Album 1");
        when(albums.get(1).getTitle()).thenReturn("Album 2");
        when(albums.get(2).getTitle()).thenReturn("Album 3");
        when(listAlbumsResponse.iterateAll()).thenReturn(albums);

        PhotosLibraryClient photosLibraryClient = mock(PhotosLibraryClient.class);
        when(photosLibraryClient.listAlbums(any(ListAlbumsRequest.class))).thenReturn(listAlbumsResponse);

        // Execute
        GooglePhotosAlbums googlePhotosAlbums = new GooglePhotosAlbums(photosLibraryClient);
        Map<String, Album> albumMap = googlePhotosAlbums.listAllAlbuns();
        Assertions.assertEquals(3, albumMap.size());
    }

    @Test
    public void testBatchUpload1SmallFile() throws ExecutionException, InterruptedException {
        // Setup
        String albumId = "123";
        InternalPhotosLibraryClient.SearchMediaItemsPagedResponse responseEmptyAlbum = mock(InternalPhotosLibraryClient.SearchMediaItemsPagedResponse.class);
        UploadMediaItemResponse uploadResponse = mock(UploadMediaItemResponse.class);
        when(uploadResponse.getUploadToken()).thenReturn(Optional.of("token"));

        BatchCreateMediaItemsResponse mediaItemsResponse = mock(BatchCreateMediaItemsResponse.class);
        ApiFuture<BatchCreateMediaItemsResponse> apiFuture = mock(ApiFuture.class);
        when(apiFuture.get()).thenReturn(mediaItemsResponse);
        UnaryCallable mediaCallable = mock(UnaryCallable.class);
        when(mediaCallable.futureCall(any(BatchCreateMediaItemsRequest.class))).thenReturn(apiFuture);

        PhotosLibraryClient photosLibraryClient = mock(PhotosLibraryClient.class);
        when(photosLibraryClient.searchMediaItems(albumId)).thenReturn(responseEmptyAlbum);
        when(photosLibraryClient.uploadMediaItem(any(UploadMediaItemRequest.class))).thenReturn(uploadResponse);
        when(photosLibraryClient.batchCreateMediaItemsCallable()).thenReturn(mediaCallable);

        Album album = mock(Album.class);
        when(album.getId()).thenReturn(albumId);
        when(album.getTitle()).thenReturn("My Album");
        when(album.getIsWriteable()).thenReturn(true);


        String imageName = "photo_portrait_small.JPG";
        URL resourceURL = getClass().getClassLoader().getResource(imageName);
        File imageFile = new File(resourceURL.getPath());

        List<File> files = Arrays.asList(imageFile);

        // Execute
        GooglePhotosAlbums googlePhotosAlbums = new GooglePhotosAlbums(photosLibraryClient);
        googlePhotosAlbums.batchUploadFiles(album, files);
    }
}
