package com.werneckpaiva.googlephotosbatch.utils;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;

public class TestAlbumUtils {

    @Test
    public void testAlbumName(){
        File file = new File("/fotos/Diversas/2018/Casa_Natal/");
        Assertions.assertEquals("Diversas / 2018 / Casa Natal", AlbumUtils.file2AlbumName("/fotos/", file));
    }


    @Test
    public void testAlbumNameWithoutSlashOnBaseFolder(){
        File file = new File("/fotos/Diversas/2018/Casa_Natal/");
        Assertions.assertEquals("Diversas / 2018 / Casa Natal", AlbumUtils.file2AlbumName("/fotos", file));
    }

}
