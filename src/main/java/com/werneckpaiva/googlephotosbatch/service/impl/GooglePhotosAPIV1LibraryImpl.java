package com.werneckpaiva.googlephotosbatch.service.impl;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.JsonFactory;

import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.core.ApiFuture;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.rpc.ApiException;
import com.google.auth.Credentials;
import com.google.auth.oauth2.UserCredentials;
import com.google.common.collect.ImmutableList;
import com.google.photos.library.v1.PhotosLibraryClient;
import com.google.photos.library.v1.PhotosLibrarySettings;
import com.google.photos.library.v1.internal.InternalPhotosLibraryClient;
import com.google.photos.library.v1.proto.*;
import com.google.photos.library.v1.upload.UploadMediaItemRequest;
import com.google.photos.library.v1.upload.UploadMediaItemResponse;
import com.google.photos.library.v1.util.NewMediaItemFactory;
import com.google.photos.types.proto.MediaItem;
import com.google.rpc.Code;
import com.google.rpc.Status;
import com.werneckpaiva.googlephotosbatch.service.Album;
import com.werneckpaiva.googlephotosbatch.service.GooglePhotosAPI;
import com.werneckpaiva.googlephotosbatch.exception.GooglePhotosServiceException;

import java.io.*;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.api.client.json.gson.GsonFactory;

public class GooglePhotosAPIV1LibraryImpl implements GooglePhotosAPI {

    private final PhotosLibraryClient photosLibraryClient;

    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    private final int ALBUM_BATCH_SIZE = 10;

    private static final File CREDENTIALS_DATA_FILE = new File("credentials");

    private static final Logger logger = LoggerFactory.getLogger(GooglePhotosAPIV1LibraryImpl.class);

    private static final List<String> REQUIRED_SCOPES =
            ImmutableList.of(
                    "https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata",
                    "https://www.googleapis.com/auth/photoslibrary.appendonly");


    public GooglePhotosAPIV1LibraryImpl(PhotosLibraryClient photosLibraryClient) {
        this.photosLibraryClient = photosLibraryClient;
    }

    public GooglePhotosAPIV1LibraryImpl(URL credentialsURL) throws GooglePhotosServiceException {
        this.photosLibraryClient = GooglePhotosAPIV1LibraryImpl.createPhotosLibraryClient(credentialsURL);
    }

    private static PhotosLibraryClient createPhotosLibraryClient(URL credentialsURL) throws GooglePhotosServiceException {
        PhotosLibrarySettings settings = null;
        try {
            Credentials credentials = GooglePhotosAPIV1LibraryImpl.loadUserCredentials(credentialsURL);
            settings = PhotosLibrarySettings
                    .newBuilder()
                    .setCredentialsProvider(
                            FixedCredentialsProvider.create(credentials))
                    .build();
        } catch (IOException | GeneralSecurityException e) {
            throw new GooglePhotosServiceException("Can't create Google Photos credential", e);
        }
        try {
            return PhotosLibraryClient.initialize(settings);
        } catch (IOException e) {
            throw new GooglePhotosServiceException("Can't initialize Google Photos library", e);
        }
    }

    public void logout() {
        if (CREDENTIALS_DATA_FILE.exists()) {
            deleteFolder(CREDENTIALS_DATA_FILE);
            logger.info("Logged out successfully. Credentials deleted.");
        } else {
            logger.info("No credentials found to delete.");
        }
    }

    private void deleteFolder(File folder) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteFolder(f);
                } else {
                    f.delete();
                }
            }
        }
        folder.delete();
    }


    private static Credentials loadUserCredentials(URL credentialsURL) throws IOException, GeneralSecurityException {
        InputStream credentialsInputStream = credentialsURL.openStream();
        assert credentialsInputStream != null;
        GoogleClientSecrets clientSecrets =
                GoogleClientSecrets.load(JSON_FACTORY,
                        new InputStreamReader(credentialsInputStream));
        String clientId = clientSecrets.getDetails().getClientId();
        String clientSecret = clientSecrets.getDetails().getClientSecret();

        FileDataStoreFactory credentialsDataStore = new FileDataStoreFactory(CREDENTIALS_DATA_FILE);
        GoogleAuthorizationCodeFlow flow =
                new GoogleAuthorizationCodeFlow.Builder(
                        GoogleNetHttpTransport.newTrustedTransport(),
                        JSON_FACTORY,
                        clientSecrets,
                        REQUIRED_SCOPES)
                        .setDataStoreFactory(credentialsDataStore)
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

    public Set<String> retrieveFilesFromAlbum(Album album) {
        byte retry = 0;
        while (retry++ < 100) {
            try {
                InternalPhotosLibraryClient.SearchMediaItemsPagedResponse response = photosLibraryClient.searchMediaItems(album.id());
                return StreamSupport.stream(response.iterateAll().spliterator(), false)
                        .map(MediaItem::getFilename)
                        .collect(Collectors.toSet());
            } catch (RuntimeException e) {
                System.out.println("Error: " + e.getMessage() + " retry " + retry);
            }
        }
        throw new RuntimeException("Couldn't retrieve medias from album " + album.title());
    }

    public String uploadSingleFile(String mediaName, File file) {
        logger.info("Uploading {}", mediaName);
        try {
            UploadMediaItemRequest uploadRequest =
                    UploadMediaItemRequest.newBuilder()
                            .setFileName(mediaName)
                            .setDataFile(new RandomAccessFile(file, "r"))
                            .build();
            UploadMediaItemResponse uploadResponse = photosLibraryClient.uploadMediaItem(uploadRequest);
            if (uploadResponse.getError().isPresent()) {
                UploadMediaItemResponse.Error error = uploadResponse.getError().get();
                throw new IOException(error.getCause());
            }
            String uploadToken = uploadResponse.getUploadToken().get();
            logger.info("Uploaded {}", mediaName);
            return uploadToken;
        } catch (IOException | ApiException e) {
            logger.error("Can't upload file {}", file, e);
            return null;
        }
    }

    public Album createAlbum(String albumName) {
        int retries = 0;
        while (retries++ < 3) {
            try {
                try {
                    com.google.photos.types.proto.Album googleAlbum = photosLibraryClient.createAlbum(albumName)
                            .toBuilder().setIsWriteable(true).build();
                    return googleAlbum2Album(googleAlbum);
                } catch (ApiException e) {
                    logger.error("Error creating album, retrying after 30s...", e);
                    Thread.sleep(30000);
                }
            } catch (InterruptedException ex) {
                System.out.println("Retry waiting interrupted");
                throw new RuntimeException(ex);
            }
        }
        return null;
    }

    public void saveToAlbum(Album album, List<String> uploadedTokens) {
        if (uploadedTokens.isEmpty()) return;
        for (int i = 0; i <= uploadedTokens.size() / ALBUM_BATCH_SIZE; i++) {
            int fromIndex = i * ALBUM_BATCH_SIZE;
            if (fromIndex >= uploadedTokens.size()) return;
            int toIndex = Math.min((i + 1) * ALBUM_BATCH_SIZE, uploadedTokens.size());
            System.out.printf("From %d to %d\n", fromIndex, toIndex);
            saveToAlbumInIdealBatchSize(album, uploadedTokens.subList(fromIndex, toIndex));
        }
    }

    private void saveToAlbumInIdealBatchSize(Album album, List<String> uploadedTokens) {
        int retries = 0;
        List<NewMediaItem> mediasUploaded = uploadedTokens.stream().map(token -> NewMediaItemFactory.createNewMediaItem(token)).collect(Collectors.toList());
        while (retries++ < 3) {
            try {
                try {
                    BatchCreateMediaItemsRequest albumMediaItemsRequest = BatchCreateMediaItemsRequest.newBuilder()
                            .setAlbumId(album.id())
                            .addAllNewMediaItems(mediasUploaded)
                            .build();
                    ApiFuture<BatchCreateMediaItemsResponse> apiFuture = photosLibraryClient.batchCreateMediaItemsCallable()
                            .futureCall(albumMediaItemsRequest);
                    BatchCreateMediaItemsResponse mediasToAlbumResponse = apiFuture.get();
                    List<NewMediaItemResult> newMediaItemResultsList = mediasToAlbumResponse.getNewMediaItemResultsList();
                    for (NewMediaItemResult itemsResponse : newMediaItemResultsList) {
                        Status status = itemsResponse.getStatus();
                        if (status.getCode() != Code.OK_VALUE) {
                            logger.error("Error setting item to album: {} - {}", status.getCode(), status.getMessage());
                        }
                    }
                    break;
                } catch (ExecutionException e) {
                    logger.error("Error saving items to album, retrying after 30s...", e);
                    Thread.sleep(30000);
                }
            } catch (InterruptedException ex) {
                System.out.println("Retry waiting interrupted");
                return;
            }
        }
    }

    public Iterable<Album> getAllAlbums() {
        ListAlbumsRequest listAlbumsRequest = ListAlbumsRequest.newBuilder()
                .setExcludeNonAppCreatedData(false)
                .setPageSize(50)
                .build();
        InternalPhotosLibraryClient.ListAlbumsPagedResponse listAlbumsResponse = photosLibraryClient.listAlbums(listAlbumsRequest);
        Iterable<com.google.photos.types.proto.Album> albumsIterable = listAlbumsResponse.iterateAll();
        return () -> {
            Iterator<com.google.photos.types.proto.Album> iterator = albumsIterable.iterator();

            return new Iterator<Album>() {

                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public Album next() {
                    com.google.photos.types.proto.Album googleAlbum = iterator.next();
                    return googleAlbum2Album(googleAlbum);
                }
            };
        };
    }

    public static Album googleAlbum2Album(com.google.photos.types.proto.Album googleAlbum) {
        return new Album(googleAlbum.getTitle(), googleAlbum.getId(), googleAlbum.getIsWriteable());
    }

}
