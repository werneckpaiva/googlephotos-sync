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
import com.google.photos.library.v1.proto.Album;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class PicasaBatch {

    private static final File CREDENTIALS_APP_FILE = new File("credentials.json");
    private static final File CREDENTIALS_DATA_FILE = new File( "credentials");

    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();

    private static final Pattern ALLOWED_FILES_PATTERN = Pattern.compile("\\.(jpg|mp4)$", Pattern.CASE_INSENSITIVE);

    private static final List<String> REQUIRED_SCOPES =
            ImmutableList.of(
                    "https://www.googleapis.com/auth/photoslibrary.readonly",
                    "https://www.googleapis.com/auth/photoslibrary.appendonly");

    private static String baseFolder = null;

    public static void main(String[] args){
        baseFolder = args[0];

        PicasaBatch picasaBatch = new PicasaBatch();
        try {
            picasaBatch.run();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void run() throws Exception {
        try (PhotosLibraryClient photosLibraryClient = createPhotosLibraryClient()) {

            GooglePhotosAlbums googlePhotosAlbums = new GooglePhotosAlbums(photosLibraryClient);

            uploadFoldersRecursively(googlePhotosAlbums, new File(baseFolder));
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
        GoogleClientSecrets clientSecrets =
                GoogleClientSecrets.load(JSON_FACTORY,
                        new InputStreamReader(new FileInputStream(CREDENTIALS_APP_FILE)));
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

    private void uploadFoldersRecursively(GooglePhotosAlbums googlePhotosAlbums, File path) throws Exception {
        List<File> files = new ArrayList<>();
        List<File> dirs = new ArrayList<>();
        for (File file : path.listFiles()){
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
            uploadFoldersRecursively(googlePhotosAlbums, dir);
        }
    }

}
