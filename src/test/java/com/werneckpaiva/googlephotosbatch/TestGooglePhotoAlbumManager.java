package com.werneckpaiva.googlephotosbatch;


import com.google.api.core.ApiFuture;
import com.google.photos.library.v1.PhotosLibraryClient;

import static org.mockito.Mockito.*;

import com.google.photos.library.v1.internal.InternalPhotosLibraryClient;
import com.google.photos.library.v1.proto.BatchCreateMediaItemsRequest;
import com.google.photos.library.v1.proto.BatchCreateMediaItemsResponse;
import com.google.photos.library.v1.proto.ListAlbumsRequest;
import com.google.photos.library.v1.upload.UploadMediaItemRequest;
import com.google.photos.library.v1.upload.UploadMediaItemResponse;
import com.werneckpaiva.googlephotosbatch.exception.PermissionDeniedToLoadAlbumsException;
import com.werneckpaiva.googlephotosbatch.service.Album;
import com.werneckpaiva.googlephotosbatch.service.GooglePhotosAPI;
import com.werneckpaiva.googlephotosbatch.service.impl.GooglePhotosAPIV1LibraryImpl;
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


public class TestGooglePhotoAlbumManager {

    @Test
    public void testListAlbumsEmptyList() throws PermissionDeniedToLoadAlbumsException {
        // Setup
        InternalPhotosLibraryClient.ListAlbumsPagedResponse listAlbumsResponse = mock(InternalPhotosLibraryClient.ListAlbumsPagedResponse.class);
        PhotosLibraryClient photosLibraryClient = mock(PhotosLibraryClient.class);
        when(photosLibraryClient.listAlbums(any(ListAlbumsRequest.class))).thenReturn(listAlbumsResponse);
        GooglePhotosAPI googlePhotoService = new GooglePhotosAPIV1LibraryImpl(photosLibraryClient);

        // Execute
        GooglePhotoAlbumManager googlePhotoAlbumManager = new GooglePhotoAlbumManager(googlePhotoService);
        Map<String, Album> albumMap = googlePhotoAlbumManager.listAllAlbums();
        Assertions.assertTrue(albumMap.isEmpty());
    }

    @Test
    public void testListAlbumsNonEmpty() throws PermissionDeniedToLoadAlbumsException {
        // Setup
        InternalPhotosLibraryClient.ListAlbumsPagedResponse listAlbumsResponse = mock(InternalPhotosLibraryClient.ListAlbumsPagedResponse.class);
        List<com.google.photos.types.proto.Album> albums = Arrays.asList(
                mock(com.google.photos.types.proto.Album.class),
                mock(com.google.photos.types.proto.Album.class),
                mock(com.google.photos.types.proto.Album.class));
        when(albums.get(0).getTitle()).thenReturn("Album 1");
        when(albums.get(1).getTitle()).thenReturn("Album 2");
        when(albums.get(2).getTitle()).thenReturn("Album 3");
        when(listAlbumsResponse.iterateAll()).thenReturn(albums);

        PhotosLibraryClient photosLibraryClient = mock(PhotosLibraryClient.class);
        when(photosLibraryClient.listAlbums(any(ListAlbumsRequest.class))).thenReturn(listAlbumsResponse);
        GooglePhotosAPI googlePhotoService = new GooglePhotosAPIV1LibraryImpl(photosLibraryClient);

        // Execute
        GooglePhotoAlbumManager googlePhotoAlbumManager = new GooglePhotoAlbumManager(googlePhotoService);
        Map<String, Album> albumMap = googlePhotoAlbumManager.listAllAlbums();
        Assertions.assertEquals(3, albumMap.size());
    }

    @Test
    public void testBatchUpload1SmallFile() throws ExecutionException, InterruptedException {
        // Setup
        String albumId = "123";
        PhotosLibraryClient photosLibraryClient = mockPhotosLibraryClient(albumId);
        GooglePhotosAPI googlePhotoService = new GooglePhotosAPIV1LibraryImpl(photosLibraryClient);

        Album album = new Album("My Album", albumId, true);

        List<File> files = Arrays.asList(
                getImageFile("photo_portrait_small.JPG")
        );

        // Execute
        GooglePhotoAlbumManager googlePhotoAlbumManager = new GooglePhotoAlbumManager(googlePhotoService);
        googlePhotoAlbumManager.batchUploadFiles(album, files);
        verify(photosLibraryClient, times(1)).uploadMediaItem(any());
    }

    @Test
    public void testBatchUploadMultipleFile() throws ExecutionException, InterruptedException {
        // Setup
        String albumId = "123";
        PhotosLibraryClient photosLibraryClient = mockPhotosLibraryClient(albumId);
        GooglePhotosAPI googlePhotoService = new GooglePhotosAPIV1LibraryImpl(photosLibraryClient);
        Album album = new Album("My Album", albumId, true);

        List<File> files = Arrays.asList(
                getImageFile("photo_landscape_big.JPG"),
                getImageFile("photo_portrait_big.JPG"),
                getImageFile("photo_portrait_small.JPG")
        );

        // Execute
        GooglePhotoAlbumManager googlePhotoAlbumManager = new GooglePhotoAlbumManager(googlePhotoService);
        googlePhotoAlbumManager.batchUploadFiles(album, files);

        verify(photosLibraryClient, times(3)).uploadMediaItem(any());
    }

    private static PhotosLibraryClient mockPhotosLibraryClient(String albumId) throws InterruptedException, ExecutionException {
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
        return photosLibraryClient;
    }

    private File getImageFile(String imageName) {
        URL resourceURL = getClass().getClassLoader().getResource(imageName);
        File imageFile = new File(resourceURL.getPath());
        return imageFile;
    }

}
