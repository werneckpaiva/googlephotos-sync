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
import com.werneckpaiva.googlephotosbatch.service.GooglePhotosServiceException;

import java.io.*;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import com.google.api.client.json.gson.GsonFactory;

public class GooglePhotosAPIV1LibraryImpl implements GooglePhotosAPI {

    private final PhotosLibraryClient photosLibraryClient;

    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    private static final File CREDENTIALS_DATA_FILE = new File( "credentials");

    private static final List<String> REQUIRED_SCOPES =
            ImmutableList.of(
                    "https://www.googleapis.com/auth/photoslibrary.readonly",
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
            Credentials credentials = GooglePhotosAPIV1LibraryImpl.getUserCredentials(credentialsURL);
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

    private static Credentials getUserCredentials(URL credentialsURL) throws IOException, GeneralSecurityException {
        InputStream credentialsInputStream = credentialsURL.openStream();
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
        System.out.println("Uploading " + mediaName);
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
            return uploadToken;
        } catch (IOException | ApiException e) {
            System.out.println(" Can't upload file " + file);
            e.printStackTrace();
            return null;
        }
    }

    public Album createAlbum(String albumName) {
        com.google.photos.types.proto.Album googleAlbum = photosLibraryClient.createAlbum(albumName)
                    .toBuilder().setIsWriteable(true).build();
            return googleAlbum2Album(googleAlbum);
    }

    public void saveToAlbum(Album album, List<String> uploadedTokens) {
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
                            System.out.println("Error setting item to album: " + status.getCode() + " - " + status.getMessage());
                        }
                    }
                    break;
                } catch (ExecutionException e) {
                    e.printStackTrace();
                    System.out.println("Retrying after 30s...");
                    Thread.sleep(30000);
                }
            } catch (InterruptedException ex) {
                System.out.println("Retry waiting interrupted");
                return;
            }
        }
    }

    public Iterable<Album> getAllAlbums(){
        ListAlbumsRequest listAlbumsRequest = ListAlbumsRequest.newBuilder()
                .setExcludeNonAppCreatedData(false)
                .setPageSize(50)
                .build();
        InternalPhotosLibraryClient.ListAlbumsPagedResponse listAlbumsResponse = photosLibraryClient.listAlbums(listAlbumsRequest);
        Iterable<com.google.photos.types.proto.Album> albumsIterable = listAlbumsResponse.iterateAll();
        return new Iterable<Album>() {

            @Override
            public Iterator<Album> iterator() {
                Iterator<com.google.photos.types.proto.Album> iterator = albumsIterable.iterator();

                return new Iterator<Album>(){

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
            }
        };
    }

    public static Album googleAlbum2Album(com.google.photos.types.proto.Album googleAlbum){
        Album album = new Album(googleAlbum.getTitle(), googleAlbum.getId(), googleAlbum.getIsWriteable());
        return album;
    }

}
