package com.werneckpaiva.googlephotosbatch;

import com.werneckpaiva.googlephotosbatch.exception.PermissionDeniedToLoadAlbumsException;
import com.werneckpaiva.googlephotosbatch.service.Album;
import com.werneckpaiva.googlephotosbatch.service.GooglePhotosAPI;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import static org.mockito.Mockito.*;

public class TestGooglePhotoAlbumManagerCache {

    @TempDir
    Path tempDir;

    @Test
    public void testListAllAlbumsWithCacheFound() throws PermissionDeniedToLoadAlbumsException, IOException {
        // Setup
        File cacheFile = tempDir.resolve("albums_cache.json").toFile();
        try (FileWriter writer = new FileWriter(cacheFile)) {
            writer.write("{\"title\": \"Cached Album\", \"id\": \"cached-id\", \"isWriteable\": true}\n");
        }

        GooglePhotosAPI googlePhotosAPI = mock(GooglePhotosAPI.class);
        GooglePhotoAlbumManager manager = new GooglePhotoAlbumManager(googlePhotosAPI);
        manager.setAlbumsCache(cacheFile);

        // Execute
        Map<String, Album> albums = manager.listAllAlbums();

        // Verify
        Assertions.assertEquals(1, albums.size());
        Assertions.assertTrue(albums.containsKey("Cached Album"));
        Assertions.assertEquals("cached-id", albums.get("Cached Album").id());
        verify(googlePhotosAPI, never()).getAllAlbums();
    }

    @Test
    public void testListAllAlbumsWithCacheNotFound() throws PermissionDeniedToLoadAlbumsException {
        // Setup
        File cacheFile = tempDir.resolve("non_existent_cache.json").toFile();
        Album apiAlbum = new Album("API Album", "api-id", true);

        GooglePhotosAPI googlePhotosAPI = mock(GooglePhotosAPI.class);
        when(googlePhotosAPI.getAllAlbums()).thenReturn(Collections.singletonList(apiAlbum));

        GooglePhotoAlbumManager manager = new GooglePhotoAlbumManager(googlePhotosAPI);
        manager.setAlbumsCache(cacheFile);

        // Execute
        Map<String, Album> albums = manager.listAllAlbums();

        // Verify
        Assertions.assertEquals(1, albums.size());
        Assertions.assertTrue(albums.containsKey("API Album"));
        verify(googlePhotosAPI, times(1)).getAllAlbums();
        Assertions.assertTrue(cacheFile.exists(), "Cache file should have been created");
    }

    @Test
    public void testListAllAlbumsWithInvalidCache() throws PermissionDeniedToLoadAlbumsException, IOException {
        // Setup
        File cacheFile = tempDir.resolve("invalid_cache.json").toFile();
        try (FileWriter writer = new FileWriter(cacheFile)) {
            writer.write("invalid json line\n");
            writer.write("{\"title\": \"Valid Album\", \"id\": \"valid-id\", \"isWriteable\": true}\n");
        }

        GooglePhotosAPI googlePhotosAPI = mock(GooglePhotosAPI.class);
        GooglePhotoAlbumManager manager = new GooglePhotoAlbumManager(googlePhotosAPI);
        manager.setAlbumsCache(cacheFile);

        // Execute
        Map<String, Album> albums = manager.listAllAlbums();

        // Verify
        Assertions.assertEquals(1, albums.size());
        Assertions.assertTrue(albums.containsKey("Valid Album"));
        verify(googlePhotosAPI, never()).getAllAlbums();
    }
}
