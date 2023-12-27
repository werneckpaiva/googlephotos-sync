package com.werneckpaiva.googlephotosbatch;

import com.werneckpaiva.googlephotosbatch.service.Album;
import com.werneckpaiva.googlephotosbatch.service.GooglePhotosAPI;
import com.werneckpaiva.googlephotosbatch.service.GooglePhotosServiceException;
import com.werneckpaiva.googlephotosbatch.service.impl.GooglePhotosAPIV1LibraryImpl;
import com.werneckpaiva.googlephotosbatch.utils.AlbumUtils;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public class GooglePhotosSync {

    private static final Pattern ALLOWED_FILES_PATTERN = Pattern.compile("\\.(jpe?g|mp4|mov)$", Pattern.CASE_INSENSITIVE);
    private static final String CREDENTIALS_JSON = "credentials.json";

    public static void main(String[] args){

        if (args.length == 0){
            System.out.println("Usage: <base_folder> [folders...]");
            System.exit(1);
        }
        String baseFolder = args[0];
        List<String> foldersToProcess = new ArrayList<>();
        if (args.length >= 2){
            for (int i=1; i<args.length; i++){
                String processFolder = args[i];
                if (!processFolder.startsWith(baseFolder)){
                    System.err.println("Processing folder must be included in the base folder");
                    System.exit(1);
                }
                foldersToProcess.add(processFolder);
            }
        } else {
            foldersToProcess.add(baseFolder);
        }

        GooglePhotosSync googlePhotosSync = new GooglePhotosSync();
        try {
            googlePhotosSync.run(baseFolder, foldersToProcess);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void run(String baseFolder, List<String> foldersToProcess) throws GooglePhotosServiceException {
        URL credentialsURL = getClass().getClassLoader().getResource(CREDENTIALS_JSON);
        GooglePhotosAPI googlePhotoService = new GooglePhotosAPIV1LibraryImpl(credentialsURL);
        GooglePhotoAlbumManager googlePhotosAlbums = new GooglePhotoAlbumManager(googlePhotoService);

        for(String folderToProcess : foldersToProcess){
            File folderFile = new File(folderToProcess);
            if (!folderFile.exists()) {
                continue;
            }
            uploadFoldersRecursively(googlePhotosAlbums, baseFolder, folderFile);
        }

    }

    private void uploadFoldersRecursively(GooglePhotoAlbumManager googlePhotosAlbums, String baseFolder, File path) {
        List<File> files = new ArrayList<>();
        List<File> dirs = new ArrayList<>();
        for (File file : Objects.requireNonNull(path.listFiles())){
            String fileName = file.getName();
            if (fileName.startsWith(".")) continue;
            if (file.isDirectory()){
                dirs.add(file);
            } else if (ALLOWED_FILES_PATTERN.matcher(fileName).find()){
                files.add(file);
            }
        }
        if (!files.isEmpty()){
            String albumName = AlbumUtils.file2AlbumName(baseFolder, path);
            Album album = googlePhotosAlbums.getAlbum(albumName);
            if (album == null){
                album = googlePhotosAlbums.createAlbum(albumName);
            }
            googlePhotosAlbums.batchUploadFiles(album, files);
        }
        for (File dir : dirs){
            uploadFoldersRecursively(googlePhotosAlbums, baseFolder, dir);
        }
    }

}
