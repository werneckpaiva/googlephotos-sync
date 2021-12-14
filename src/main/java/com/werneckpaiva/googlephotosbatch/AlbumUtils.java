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

    public static String file2MediaName(File fullPath){
        return fullPath.getName()
                // Replace extension, even if has 4 chars (e.g. .jpeg)
                .replaceAll("\\.[a-zA-Z0-9]{3,4}", "")
                .replace('_', ' ');
    }

}
