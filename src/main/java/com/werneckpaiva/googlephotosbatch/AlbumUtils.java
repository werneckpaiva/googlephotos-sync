package com.werneckpaiva.googlephotosbatch;

import java.io.File;

public class AlbumUtils {

    public static String file2AlbumName(String baseFolder, File fullPath){
        String path = fullPath.getAbsolutePath()
                .replace(baseFolder, "")
                .replace('_', ' ')
                .replaceAll("/ *$", "")
                .replaceAll("^/ *", "")
                .replace("/", " / ");
        return path;
    }

}
