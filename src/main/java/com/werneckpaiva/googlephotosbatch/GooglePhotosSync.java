package com.werneckpaiva.googlephotosbatch;

import com.werneckpaiva.googlephotosbatch.exception.PermissionDeniedToLoadAlbumsException;
import com.werneckpaiva.googlephotosbatch.service.Album;
import com.werneckpaiva.googlephotosbatch.service.GooglePhotosAPI;
import com.werneckpaiva.googlephotosbatch.exception.GooglePhotosServiceException;
import com.werneckpaiva.googlephotosbatch.service.impl.GooglePhotosAPIV1LibraryImpl;
import com.werneckpaiva.googlephotosbatch.utils.AlbumUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.fusesource.jansi.AnsiConsole;
import picocli.CommandLine;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

@CommandLine.Command(name = "googlephotos-sync", mixinStandardHelpOptions = true, version = "1.0", description = "Syncs local folders to Google Photos albums.")
public class GooglePhotosSync implements Callable<Integer> {

    private static final Pattern ALLOWED_FILES_PATTERN = Pattern.compile("\\.(jpe?g|mp4|mov)$",
            Pattern.CASE_INSENSITIVE);

    private static final Logger logger = LoggerFactory.getLogger(GooglePhotosSync.class);
    private static final String CREDENTIALS_JSON = "credentials.json";

    @CommandLine.Parameters(index = "0", description = "Base folder to calculate album names")
    private String baseFolder;

    @CommandLine.Parameters(index = "1..*", description = "Folders to process (optional, defaults to base folder)", defaultValue = "")
    private List<String> foldersToProcess = new ArrayList<>();

    @CommandLine.Option(names = {
            "--skip-load" }, description = "Skip loading existing albums and create new ones if needed")
    private boolean skipLoad = false;

    @CommandLine.Option(names = {
            "--album-id" }, description = "ID of the album to add photos to. Automatically sets --skip-load")
    private String albumId;

    @CommandLine.Option(names = {
            "--albums-cache" }, description = "Path to a file to cache album information (JSON per line)")
    private String albumsCache;

    public static void main(String[] args) {
        System.setProperty("io.netty.noUnsafe", "true");
        System.setProperty("io.grpc.netty.shaded.io.netty.noUnsafe", "true");
        System.setProperty("guava.concurrent.allow_unsafe", "false");
        AnsiConsole.systemInstall();

        int exitCode = new CommandLine(new GooglePhotosSync()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        if (albumId != null) {
            skipLoad = true;
        }
        if (foldersToProcess.isEmpty()) {
            foldersToProcess.add(baseFolder);
        }
        for (String processFolder : foldersToProcess) {
            if (!processFolder.startsWith(baseFolder)) {
                System.err.println("Processing folder must be included in the base folder: " + processFolder);
                return 1;
            }
        }

        try {
            run(baseFolder, foldersToProcess, skipLoad, albumId);
            return 0;
        } catch (Exception e) {
            logger.error("Error running GooglePhotosSync", e);
            return 1;
        }
    }

    public void run(String baseFolder, List<String> foldersToProcess, boolean skipLoad, String albumId)
            throws GooglePhotosServiceException {
        URL credentialsURL = getClass().getClassLoader().getResource(CREDENTIALS_JSON);

        GooglePhotosAPI googlePhotoService = new GooglePhotosAPIV1LibraryImpl(credentialsURL);
        GooglePhotoAlbumManager googlePhotosAlbums = new GooglePhotoAlbumManager(googlePhotoService);
        googlePhotosAlbums.setSkipAlbumLoad(skipLoad);
        if (albumId != null) {
            googlePhotosAlbums.setAlbumId(albumId);
        }
        if (albumsCache != null) {
            googlePhotosAlbums.setAlbumsCache(new File(albumsCache));
        }

        for (String folderToProcess : foldersToProcess) {
            File folderFile = new File(folderToProcess);
            if (!folderFile.exists()) {
                continue;
            }
            int retries = 0;
            while (retries < 2) {
                try {
                    uploadFoldersRecursively(googlePhotosAlbums, baseFolder, folderFile);
                    break;
                } catch (PermissionDeniedToLoadAlbumsException e) {
                    logger.error("Permission denied. New authentication required");
                    googlePhotoService.logout();
                    retries++;
                    if (retries >= 2) {
                        logger.error("Failed to process folder {} after multiple retries. Skipping.", folderToProcess);
                    }
                }
            }
        }

    }

    private void uploadFoldersRecursively(GooglePhotoAlbumManager googlePhotoAlbumManager, String baseFolder, File path)
            throws PermissionDeniedToLoadAlbumsException {
        List<File> files = new ArrayList<>();
        List<File> dirs = new ArrayList<>();
        for (File file : Objects.requireNonNull(path.listFiles())) {
            String fileName = file.getName();
            if (fileName.startsWith("."))
                continue;
            if (file.isDirectory()) {
                dirs.add(file);
            } else if (ALLOWED_FILES_PATTERN.matcher(fileName).find()) {
                files.add(file);
            }
        }
        if (!files.isEmpty()) {
            String albumName = AlbumUtils.file2AlbumName(baseFolder, path);
            Album album = googlePhotoAlbumManager.getAlbum(albumName);
            if (album == null) {
                album = googlePhotoAlbumManager.createAlbum(albumName);
            }
            googlePhotoAlbumManager.batchUploadFiles(album, files);
        }
        for (File dir : dirs) {
            uploadFoldersRecursively(googlePhotoAlbumManager, baseFolder, dir);
        }
    }

}
