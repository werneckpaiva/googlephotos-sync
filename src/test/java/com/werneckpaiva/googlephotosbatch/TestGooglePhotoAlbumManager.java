package com.werneckpaiva.googlephotosbatch;

import static org.mockito.Mockito.*;

import com.werneckpaiva.googlephotosbatch.exception.PermissionDeniedToLoadAlbumsException;
import com.werneckpaiva.googlephotosbatch.service.Album;
import com.werneckpaiva.googlephotosbatch.service.GooglePhotosAPI;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class TestGooglePhotoAlbumManager {

    @Test
    public void testListAlbumsEmptyList() throws PermissionDeniedToLoadAlbumsException {
        // Setup
        GooglePhotosAPI googlePhotoService = mock(GooglePhotosAPI.class);
        when(googlePhotoService.getAllAlbums()).thenReturn(Collections.emptyList());

        // Execute
        GooglePhotoAlbumManager googlePhotoAlbumManager = new GooglePhotoAlbumManager(googlePhotoService);
        Map<String, Album> albumMap = googlePhotoAlbumManager.listAllAlbums();
        Assertions.assertTrue(albumMap.isEmpty());
    }

    @Test
    public void testGetAlbumWithAlbumId() throws PermissionDeniedToLoadAlbumsException {
        // Setup
        GooglePhotosAPI googlePhotoService = mock(GooglePhotosAPI.class);
        Album expectedAlbum = new Album("My Album", "123", true);
        when(googlePhotoService.getAlbum("123")).thenReturn(expectedAlbum);

        GooglePhotoAlbumManager googlePhotoAlbumManager = new GooglePhotoAlbumManager(googlePhotoService);
        googlePhotoAlbumManager.setAlbumId("123");

        // Execute
        Album album = googlePhotoAlbumManager.getAlbum("Any Album");

        // Verify
        Assertions.assertEquals(expectedAlbum, album);
        verify(googlePhotoService, times(1)).getAlbum("123");
        verify(googlePhotoService, never()).getAllAlbums();
    }

    @Test
    public void testGetAlbumWithSkipLoad() throws PermissionDeniedToLoadAlbumsException {
        // Setup
        GooglePhotosAPI googlePhotoService = mock(GooglePhotosAPI.class);
        GooglePhotoAlbumManager googlePhotoAlbumManager = new GooglePhotoAlbumManager(googlePhotoService);
        googlePhotoAlbumManager.setSkipAlbumLoad(true);

        // Execute
        Album album = googlePhotoAlbumManager.getAlbum("Any Album");

        // Verify
        Assertions.assertNull(album);
        verify(googlePhotoService, never()).getAllAlbums();
    }

    @Test
    public void testBatchUploadWithSkipLoad() throws PermissionDeniedToLoadAlbumsException {
        // Setup
        GooglePhotosAPI googlePhotoService = mock(GooglePhotosAPI.class);
        when(googlePhotoService.uploadSingleFile(anyString(), any())).thenReturn("some-token");
        GooglePhotoAlbumManager googlePhotoAlbumManager = new GooglePhotoAlbumManager(googlePhotoService);
        googlePhotoAlbumManager.setSkipAlbumLoad(true);
        Album album = new Album("My Album", "123", true);
        List<File> files = Arrays.asList(getImageFile("photo_portrait_small.JPG"));

        // Execute
        googlePhotoAlbumManager.batchUploadFiles(album, files);

        // Verify
        verify(googlePhotoService, never()).retrieveFilesFromAlbum(any());
        verify(googlePhotoService, times(1)).uploadSingleFile(anyString(), any());
    }

    @Test
    public void testListAlbumsNonEmpty() throws PermissionDeniedToLoadAlbumsException {
        // Setup
        GooglePhotosAPI googlePhotoService = mock(GooglePhotosAPI.class);
        List<Album> albums = Arrays.asList(
                new Album("Album 1", "id1", true),
                new Album("Album 2", "id2", true),
                new Album("Album 3", "id3", true));
        when(googlePhotoService.getAllAlbums()).thenReturn(albums);

        // Execute
        GooglePhotoAlbumManager googlePhotoAlbumManager = new GooglePhotoAlbumManager(googlePhotoService);
        Map<String, Album> albumMap = googlePhotoAlbumManager.listAllAlbums();

        // Verify
        Assertions.assertEquals(3, albumMap.size());
        Assertions.assertTrue(albumMap.containsKey("Album 1"));
        Assertions.assertTrue(albumMap.containsKey("Album 2"));
        Assertions.assertTrue(albumMap.containsKey("Album 3"));
    }

    @Test
    public void testBatchUpload1SmallFile() throws Exception {
        // Setup
        GooglePhotosAPI googlePhotoService = mock(GooglePhotosAPI.class);
        when(googlePhotoService.uploadSingleFile(anyString(), any())).thenReturn("some-token");
        when(googlePhotoService.retrieveFilesFromAlbum(any())).thenReturn(new HashSet<>());

        Album album = new Album("My Album", "123", true);
        List<File> files = Arrays.asList(getImageFile("photo_portrait_small.JPG"));

        // Execute
        GooglePhotoAlbumManager googlePhotoAlbumManager = new GooglePhotoAlbumManager(googlePhotoService);
        googlePhotoAlbumManager.batchUploadFiles(album, files);

        // Verify
        verify(googlePhotoService, times(1)).uploadSingleFile(anyString(), any());
        verify(googlePhotoService, times(1)).saveToAlbum(eq(album), anyList());
    }

    @Test
    public void testBatchUploadMultipleFile() throws Exception {
        // Setup
        GooglePhotosAPI googlePhotoService = mock(GooglePhotosAPI.class);
        when(googlePhotoService.uploadSingleFile(anyString(), any())).thenReturn("some-token");
        when(googlePhotoService.retrieveFilesFromAlbum(any())).thenReturn(new HashSet<>());

        Album album = new Album("My Album", "123", true);
        List<File> files = Arrays.asList(
                getImageFile("photo_landscape_big.JPG"),
                getImageFile("photo_portrait_big.JPG"),
                getImageFile("photo_portrait_small.JPG"));

        // Execute
        GooglePhotoAlbumManager googlePhotoAlbumManager = new GooglePhotoAlbumManager(googlePhotoService);
        googlePhotoAlbumManager.batchUploadFiles(album, files);

        // Verify
        verify(googlePhotoService, times(3)).uploadSingleFile(anyString(), any());
        verify(googlePhotoService, times(1)).saveToAlbum(eq(album), anyList());
    }

    private File getImageFile(String imageName) {
        URL resourceURL = getClass().getClassLoader().getResource(imageName);
        File imageFile = new File(resourceURL.getPath());
        return imageFile;
    }

}
