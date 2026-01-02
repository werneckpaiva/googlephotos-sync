package com.werneckpaiva.googlephotosbatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.werneckpaiva.googlephotosbatch.service.Album;
import com.werneckpaiva.googlephotosbatch.service.GooglePhotosAPI;
import com.werneckpaiva.googlephotosbatch.service.impl.GooglePhotosAPIV1LibraryImpl;
import org.fusesource.jansi.AnsiConsole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "googlephotos-orphan-finder", mixinStandardHelpOptions = true, version = "1.0", description = "Identifies files in Google Photos that are not in any album with resume support.")
public class GooglePhotosOrphanFinder implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(GooglePhotosOrphanFinder.class);
    private static final String CREDENTIALS_JSON = "credentials.json";

    @CommandLine.Option(names = {
            "--album-medias" }, description = "File to store medias found in albums (JSON Lines)", defaultValue = "album_medias.json")
    private String albumMediasFile;

    @CommandLine.Option(names = {
            "--library-medias" }, description = "File to store medias found in library (JSON Lines)", defaultValue = "library_medias.json")
    private String libraryMediasFile;

    @CommandLine.Option(names = {
            "--albums-cache" }, description = "Path to a file to cache album information (JSON per line)", defaultValue = "albums_cache.json")
    private String albumsCacheFile;

    @CommandLine.Option(names = {
            "--library-token" }, description = "File to store library page token", defaultValue = "library_page_token.txt")
    private String libraryTokenFile;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public record AlbumMedias(String albumName, String albumId, List<GooglePhotosAPI.MediaItemInfo> files) {
    }

    public static void main(String[] args) {
        System.setProperty("io.netty.noUnsafe", "true");
        System.setProperty("io.grpc.netty.shaded.io.netty.noUnsafe", "true");
        System.setProperty("guava.concurrent.allow_unsafe", "false");
        AnsiConsole.systemInstall();

        int exitCode = new CommandLine(new GooglePhotosOrphanFinder()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        try {
            URL credentialsURL = getClass().getClassLoader().getResource(CREDENTIALS_JSON);
            if (credentialsURL == null) {
                System.err.println("Required credentials file not found: " + CREDENTIALS_JSON);
                return 1;
            }

            GooglePhotosAPI googlePhotoService = new GooglePhotosAPIV1LibraryImpl(credentialsURL);
            GooglePhotoAlbumManager albumManager = new GooglePhotoAlbumManager(googlePhotoService);
            albumManager.setAlbumsCache(new File(albumsCacheFile));

            processAlbums(googlePhotoService, albumManager);
            processLibrary(googlePhotoService);
            reportOrphans();

            return 0;
        } catch (Throwable e) {
            logger.error("Error running GooglePhotosOrphanFinder", e);
            return 1;
        }
    }

    private void processAlbums(GooglePhotosAPI api, GooglePhotoAlbumManager albumManager) throws Throwable {
        System.out.println("Loading albums...");
        Map<String, Album> allAlbums = albumManager.listAllAlbums();

        Set<String> completedAlbumIds = loadCompletedAlbumIds();
        System.out.println("Processing albums (resuming from " + completedAlbumIds.size() + " already processed)...");

        int albumCount = 0;
        for (Album album : allAlbums.values()) {
            albumCount++;
            if (completedAlbumIds.contains(album.id())) {
                continue;
            }

            System.out.print("\rProcessing album " + albumCount + "/" + allAlbums.size() + ": " + album.title());
            try {
                Set<GooglePhotosAPI.MediaItemInfo> albumFiles = api.retrieveFilesFromAlbum(album);
                AlbumMedias entry = new AlbumMedias(album.title(), album.id(),
                        new java.util.ArrayList<>(albumFiles));
                appendJsonObject(albumMediasFile, entry);
            } catch (Exception e) {
                System.err.println("\nError retrieving files from album " + album.title() + ": " + e.getMessage());
            }
        }
        System.out.println("\nFinished processing albums.");
    }

    private Set<String> loadCompletedAlbumIds() throws IOException {
        Set<String> ids = new HashSet<>();
        Path path = Paths.get(albumMediasFile);
        if (!Files.exists(path)) {
            return ids;
        }
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty())
                    continue;
                try {
                    AlbumMedias entry = objectMapper.readValue(line, AlbumMedias.class);
                    ids.add(entry.albumId());
                } catch (Exception e) {
                    // Ignore malformed lines
                }
            }
        }
        return ids;
    }

    private void processLibrary(GooglePhotosAPI api) throws IOException {
        String pageToken = loadToken(libraryTokenFile);
        System.out.println(
                "Processing library items (resuming with token: " + (pageToken == null ? "START" : pageToken) + ")...");

        int totalItems = 0;
        do {
            GooglePhotosAPI.MediaItemsResult result = api.listMediaItems(pageToken);
            List<GooglePhotosAPI.MediaItemInfo> pageItems = new java.util.ArrayList<>();
            for (GooglePhotosAPI.MediaItemInfo item : result.items()) {
                pageItems.add(item);
                totalItems++;
            }
            appendJsonObjects(libraryMediasFile, pageItems);

            pageToken = result.nextPageToken();
            saveToken(libraryTokenFile, pageToken);

            if (totalItems % 100 == 0 || pageToken == null) {
                System.out.print("\rProcessed " + totalItems + " library items...");
            }
        } while (pageToken != null && !pageToken.isEmpty());

        System.out.println("\nFinished processing library items.");
    }

    private void reportOrphans() throws IOException {
        System.out.println("Identifying orphaned files...");
        Set<String> idsInAlbums = new HashSet<>();
        Path albumPath = Paths.get(albumMediasFile);
        if (Files.exists(albumPath)) {
            try (BufferedReader reader = Files.newBufferedReader(albumPath)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty())
                        continue;
                    try {
                        AlbumMedias album = objectMapper.readValue(line, AlbumMedias.class);
                        for (GooglePhotosAPI.MediaItemInfo item : album.files()) {
                            idsInAlbums.add(item.id());
                        }
                    } catch (Exception e) {
                        // Skip malformed lines
                    }
                }
            }
        }

        int orphanCount = 0;
        int totalLibraryItems = 0;
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(libraryMediasFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty())
                    continue;
                totalLibraryItems++;
                GooglePhotosAPI.MediaItemInfo item = objectMapper.readValue(line, GooglePhotosAPI.MediaItemInfo.class);

                if (!idsInAlbums.contains(item.id())) {
                    orphanCount++;
                    System.out.println("[ORPHAN] " + item.filename() + " (ID: " + item.id() + ")");
                }
            }
        }
        System.out.println("Total library items: " + totalLibraryItems);
        System.out.println("Total unique items in albums: " + idsInAlbums.size());
        System.out.println("Found " + orphanCount + " orphaned files.");
    }

    private void appendJsonObject(String fileName, Object obj) throws IOException {
        String line = objectMapper.writeValueAsString(obj);
        Files.write(Paths.get(fileName), java.util.Collections.singletonList(line), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void appendJsonObjects(String fileName, List<GooglePhotosAPI.MediaItemInfo> items) throws IOException {
        if (items.isEmpty())
            return;
        List<String> lines = new java.util.ArrayList<>();
        for (GooglePhotosAPI.MediaItemInfo item : items) {
            lines.add(objectMapper.writeValueAsString(item));
        }
        Files.write(Paths.get(fileName), lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private String loadToken(String fileName) throws IOException {
        Path path = Paths.get(fileName);
        if (!Files.exists(path))
            return null;
        String token = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
        return token.isEmpty() ? null : token;
    }

    private void saveToken(String fileName, String token) throws IOException {
        if (token == null) {
            Files.deleteIfExists(Paths.get(fileName));
        } else {
            Files.write(Paths.get(fileName), token.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }
}
