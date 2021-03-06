package com.werneckpaiva.googlephotosbatch;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;

public class TestAlbumUtils {

    @Test
    public void testAlbumName(){
        File file = new File("/fotos/Diversas/2018/Casa_Natal/");
        Assert.assertEquals("Diversas / 2018 / Casa Natal", AlbumUtils.file2AlbumName("/fotos/", file));
    }


    @Test
    public void testAlbumNameWithoutSlashOnBaseFolder(){
        File file = new File("/fotos/Diversas/2018/Casa_Natal/");
        Assert.assertEquals("Diversas / 2018 / Casa Natal", AlbumUtils.file2AlbumName("/fotos", file));
    }

}
