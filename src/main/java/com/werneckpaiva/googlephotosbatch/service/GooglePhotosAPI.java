package com.werneckpaiva.googlephotosbatch.service;

import java.io.File;
import java.util.List;
import java.util.Set;

public interface GooglePhotosAPI {
    Set<String> retrieveFilesFromAlbum(Album album);

    String uploadSingleFile(String name, File file);

    void saveToAlbum(Album album, List<String> mediasUploaded);

    Album createAlbum(String albumName);

    Iterable<Album> getAllAlbums();
}
