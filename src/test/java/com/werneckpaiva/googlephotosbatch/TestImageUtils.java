package com.werneckpaiva.googlephotosbatch;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;

public class TestImageUtils {
    @Test
    public void testImageNoResize(){
        String imageName = "photo_portrait_small.JPG";
        URL resourceURL = getClass().getClassLoader().getResource(imageName);
        File imageFile = new File(resourceURL.getPath());

        File resizedImage = ImageUtils.resizeJPGImage(imageFile, 1500);

        Assertions.assertEquals(resizedImage, imageFile);
    }

    @Test
    public void testImageResizePortrait(){
        String imageName = "photo_portrait_big.JPG";
        URL resourceURL = getClass().getClassLoader().getResource(imageName);
        File imageFile = new File(resourceURL.getPath());

        File resizedImage = ImageUtils.resizeJPGImage(imageFile, 1500);

        Assertions.assertNotEquals(resizedImage, imageFile);

        Assertions.assertTrue(resizedImage.length() < imageFile.length());
    }
}
