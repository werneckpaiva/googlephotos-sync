package com.werneckpaiva.googlephotosbatch;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.rpc.ApiException;
import com.google.auth.Credentials;
import com.google.auth.oauth2.UserCredentials;
import com.google.common.collect.ImmutableList;
import com.google.photos.library.v1.PhotosLibraryClient;
import com.google.photos.library.v1.PhotosLibrarySettings;
import com.werneckpaiva.googlephotosbatch.service.Album;
import com.werneckpaiva.googlephotosbatch.service.GooglePhotosAPI;
import com.werneckpaiva.googlephotosbatch.service.impl.GooglePhotosAPIV1LibraryImpl;
import com.werneckpaiva.googlephotosbatch.utils.AlbumUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public class GooglePhotosSync {


    private static final File CREDENTIALS_DATA_FILE = new File( "credentials");

    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();

    private static final Pattern ALLOWED_FILES_PATTERN = Pattern.compile("\\.(jpe?g|mp4|mov)$", Pattern.CASE_INSENSITIVE);

    private static final List<String> REQUIRED_SCOPES =
            ImmutableList.of(
                    "https://www.googleapis.com/auth/photoslibrary.readonly",
                    "https://www.googleapis.com/auth/photoslibrary.appendonly");

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

    public void run(String baseFolder, List<String> foldersToProcess) throws Exception {
        try (PhotosLibraryClient photosLibraryClient = createPhotosLibraryClient()) {

            GooglePhotosAPI googlePhotoService = new GooglePhotosAPIV1LibraryImpl(photosLibraryClient);
            GooglePhotoAlbumManager googlePhotosAlbums = new GooglePhotoAlbumManager(googlePhotoService);

            for(String folderToProcess : foldersToProcess){
                File folderFile = new File(folderToProcess);
                if (!folderFile.exists()) {
                    continue;
                }
                uploadFoldersRecursively(googlePhotosAlbums, baseFolder, folderFile);
            }

        } catch (ApiException e) {
            e.printStackTrace();
        }
    }

    private static PhotosLibraryClient createPhotosLibraryClient() throws IOException, GeneralSecurityException {
        Credentials credentials = getUserCredentials();
        PhotosLibrarySettings settings = PhotosLibrarySettings
                .newBuilder()
                .setCredentialsProvider(
                        FixedCredentialsProvider.create(credentials))
                .build();
        return PhotosLibraryClient.initialize(settings);
    }

    private static Credentials getUserCredentials() throws IOException, GeneralSecurityException {
        InputStream credentialsInputStream = GooglePhotosSync.class
                .getClassLoader().getResourceAsStream("credentials.json");
        assert credentialsInputStream != null;
        GoogleClientSecrets clientSecrets =
                GoogleClientSecrets.load(JSON_FACTORY,
                        new InputStreamReader(credentialsInputStream));
        String clientId = clientSecrets.getDetails().getClientId();
        String clientSecret = clientSecrets.getDetails().getClientSecret();

        GoogleAuthorizationCodeFlow flow =
                new GoogleAuthorizationCodeFlow.Builder(
                        GoogleNetHttpTransport.newTrustedTransport(),
                        JSON_FACTORY,
                        clientSecrets,
                        REQUIRED_SCOPES)
                        .setDataStoreFactory(new FileDataStoreFactory(CREDENTIALS_DATA_FILE))
                        .setAccessType("offline")
                        .build();
        LocalServerReceiver receiver =
                new LocalServerReceiver.Builder().build();
        Credential credential = new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
        return UserCredentials.newBuilder()
                .setClientId(clientId)
                .setClientSecret(clientSecret)
                .setRefreshToken(credential.getRefreshToken())
                .build();
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