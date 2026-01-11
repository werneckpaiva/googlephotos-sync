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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "googlephotos-orphan-finder", mixinStandardHelpOptions = true, version = "1.0", description = "Identifies files in Google Photos that are not in any album with resume support.", subcommands = {
        GooglePhotosOrphanFinder.DownloadCommand.class, GooglePhotosOrphanFinder.ReportCommand.class,
        GooglePhotosOrphanFinder.DownloadMediaCommand.class, GooglePhotosOrphanFinder.AddToAlbumCommand.class,
        GooglePhotosOrphanFinder.ListAlbumCommand.class, GooglePhotosOrphanFinder.SetDescriptionCommand.class })
public class GooglePhotosOrphanFinder {

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

    @CommandLine.Command(name = "download", description = "Download albums and library info", mixinStandardHelpOptions = true)
    public static class DownloadCommand implements Callable<Integer> {

        @CommandLine.ParentCommand
        private GooglePhotosOrphanFinder parent;

        @Override
        public Integer call() {
            try {
                URL credentialsURL = parent.getClass().getClassLoader().getResource(CREDENTIALS_JSON);
                if (credentialsURL == null) {
                    System.err.println("Required credentials file not found: " + CREDENTIALS_JSON);
                    return 1;
                }

                GooglePhotosAPI googlePhotoService = new GooglePhotosAPIV1LibraryImpl(credentialsURL);
                GooglePhotoAlbumManager albumManager = new GooglePhotoAlbumManager(googlePhotoService);
                albumManager.setAlbumsCache(new File(parent.albumsCacheFile));

                parent.processAlbums(googlePhotoService, albumManager);
                parent.processLibrary(googlePhotoService);

                return 0;
            } catch (Throwable e) {
                logger.error("Error running download", e);
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "report", description = "Report orphans from downloaded files", mixinStandardHelpOptions = true)
    public static class ReportCommand implements Callable<Integer> {

        @CommandLine.ParentCommand
        private GooglePhotosOrphanFinder parent;

        @Override
        public Integer call() {
            try {
                parent.reportOrphans();
                return 0;
            } catch (Throwable e) {
                logger.error("Error running report", e);
                return 1;
            }
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

    public record OrphanReportItem(String id, String filename) {
    }

    public record ReportOutput(int totalAlbums, int totalLibraryItems, int totalUniqueItemsInAlbums, int orphanCount,
            List<OrphanReportItem> orphans) {
    }

    private void reportOrphans() throws IOException {
        System.err.println("Identifying orphaned files...");
        Set<String> idsInAlbums = new HashSet<>();
        int albumCount = 0;
        Path albumPath = Paths.get(albumMediasFile);
        if (Files.exists(albumPath)) {
            try (BufferedReader reader = Files.newBufferedReader(albumPath)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty())
                        continue;
                    try {
                        AlbumMedias album = objectMapper.readValue(line, AlbumMedias.class);
                        albumCount++;
                        for (GooglePhotosAPI.MediaItemInfo item : album.files()) {
                            idsInAlbums.add(item.id());
                        }
                    } catch (Exception e) {
                        // Skip malformed lines
                    }
                }
            }
        }

        List<OrphanReportItem> orphans = new java.util.ArrayList<>();
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
                    orphans.add(new OrphanReportItem(item.id(), item.filename()));
                }
            }
        }
        System.err.println("Total albums considered: " + albumCount);
        System.err.println("Total library items: " + totalLibraryItems);
        System.err.println("Total unique items in albums: " + idsInAlbums.size());
        System.err.println("Found " + orphanCount + " orphaned files.");

        ReportOutput report = new ReportOutput(albumCount, totalLibraryItems, idsInAlbums.size(), orphanCount, orphans);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(System.out, report);
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

    @CommandLine.Command(name = "download-media", description = "Download a single media item by ID", mixinStandardHelpOptions = true)
    public static class DownloadMediaCommand implements Callable<Integer> {

        @CommandLine.ParentCommand
        private GooglePhotosOrphanFinder parent;

        @CommandLine.Option(names = { "--id",
                "-i" }, required = true, description = "The ID of the media item to download")
        private String mediaId;

        @Override
        public Integer call() {
            try {
                URL credentialsURL = parent.getClass().getClassLoader().getResource(CREDENTIALS_JSON);
                if (credentialsURL == null) {
                    System.err.println("Required credentials file not found: " + CREDENTIALS_JSON);
                    return 1;
                }

                GooglePhotosAPI googlePhotoService = new GooglePhotosAPIV1LibraryImpl(credentialsURL);

                System.out.println("Fetching media item info for ID: " + mediaId);
                GooglePhotosAPI.MediaItemInfo mediaItem = googlePhotoService.getMediaItem(mediaId);
                System.out.println("Found media item: " + mediaItem.filename());

                String downloadUrl = mediaItem.baseUrl() + "=d";
                System.out.println("Downloading from: " + downloadUrl);

                File outputFile = new File(mediaItem.filename());
                try (InputStream in = new URL(downloadUrl).openStream()) {
                    Files.copy(in, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }

                System.out.println("Downloaded to: " + outputFile.getAbsolutePath());

                return 0;
            } catch (Throwable e) {
                logger.error("Error downloading media item", e);
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "add-to-album", description = "Add media items to an album", mixinStandardHelpOptions = true)
    public static class AddToAlbumCommand implements Callable<Integer> {

        @CommandLine.ParentCommand
        private GooglePhotosOrphanFinder parent;

        @CommandLine.Option(names = { "--album-id", "-a" }, required = true, description = "The ID of the target album")
        private String albumId;

        @CommandLine.Parameters(description = "The IDs of the media items to add")
        private List<String> mediaItemIds;

        @Override
        public Integer call() {
            try {
                URL credentialsURL = parent.getClass().getClassLoader().getResource(CREDENTIALS_JSON);
                if (credentialsURL == null) {
                    System.err.println("Required credentials file not found: " + CREDENTIALS_JSON);
                    return 1;
                }

                GooglePhotosAPI googlePhotoService = new GooglePhotosAPIV1LibraryImpl(credentialsURL);

                List<String> idsToAdd = new ArrayList<>();
                if (mediaItemIds != null && !mediaItemIds.isEmpty()) {
                    idsToAdd.addAll(mediaItemIds);
                } else {
                    // Read from stdin
                    try (Scanner scanner = new Scanner(System.in)) {
                        while (scanner.hasNextLine()) {
                            String line = scanner.nextLine().trim();
                            if (!line.isEmpty()) {
                                idsToAdd.add(line);
                            }
                        }
                    }
                }

                if (idsToAdd.isEmpty()) {
                    System.err.println("No media IDs provided.");
                    return 1;
                }

                System.out.println("Adding " + idsToAdd.size() + " items to album " + albumId);
                googlePhotoService.batchAddMediaItems(albumId, idsToAdd);
                System.out.println("Items added successfully.");

                return 0;
            } catch (Throwable e) {
                logger.error("Error adding items to album", e);
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "list-album", description = "List files in an album as JSON", mixinStandardHelpOptions = true)
    public static class ListAlbumCommand implements Callable<Integer> {

        @CommandLine.ParentCommand
        private GooglePhotosOrphanFinder parent;

        @CommandLine.Option(names = { "--album-id",
                "-a" }, required = true, description = "The ID of the album to list")
        private String albumId;

        @Override
        public Integer call() {
            try {
                URL credentialsURL = parent.getClass().getClassLoader().getResource(CREDENTIALS_JSON);
                if (credentialsURL == null) {
                    System.err.println("Required credentials file not found: " + CREDENTIALS_JSON);
                    return 1;
                }

                GooglePhotosAPI googlePhotoService = new GooglePhotosAPIV1LibraryImpl(credentialsURL);

                Album album = googlePhotoService.getAlbum(albumId);
                if (album == null) {
                    System.err.println("Album not found: " + albumId);
                    return 1;
                }

                Set<GooglePhotosAPI.MediaItemInfo> albumFiles = googlePhotoService.retrieveFilesFromAlbum(album);
                AlbumMedias entry = new AlbumMedias(album.title(), album.id(), new java.util.ArrayList<>(albumFiles));

                System.out.println(parent.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(entry));

                return 0;
            } catch (Throwable e) {
                logger.error("Error listing album", e);
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "set-description", description = "Update the description of a media item", mixinStandardHelpOptions = true)
    public static class SetDescriptionCommand implements Callable<Integer> {

        @CommandLine.ParentCommand
        private GooglePhotosOrphanFinder parent;

        @CommandLine.Option(names = { "--media-id",
                "-i" }, required = true, description = "The ID of the media item to update")
        private String mediaId;

        @CommandLine.Option(names = { "--description", "-d" }, required = true, description = "The new description")
        private String description;

        @Override
        public Integer call() {
            try {
                URL credentialsURL = parent.getClass().getClassLoader().getResource(CREDENTIALS_JSON);
                if (credentialsURL == null) {
                    System.err.println("Required credentials file not found: " + CREDENTIALS_JSON);
                    return 1;
                }

                GooglePhotosAPI googlePhotoService = new GooglePhotosAPIV1LibraryImpl(credentialsURL);

                System.out.println("Updating description for media item: " + mediaId);
                googlePhotoService.updateMediaItemDescription(mediaId, description);
                System.out.println("Description updated successfully.");

                return 0;
            } catch (Throwable e) {
                logger.error("Error updating media item description", e);
                return 1;
            }
        }
    }
}
