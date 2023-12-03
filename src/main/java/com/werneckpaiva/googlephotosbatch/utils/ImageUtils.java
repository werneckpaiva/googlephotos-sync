package com.werneckpaiva.googlephotosbatch.utils;

import org.apache.commons.imaging.ImageReadException;
import org.apache.commons.imaging.ImageWriteException;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.common.ImageMetadata;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.io.IOUtils;
import org.imgscalr.Scalr;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;

public class ImageUtils {

    public static File resizeJPGImage(File inputFile, int maxDimension) {
        try{
            FileInputStream fileInputStream = new FileInputStream(inputFile);

            byte[] imageData = IOUtils.toByteArray(fileInputStream);
            fileInputStream.close();

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageData));

            // Resize the image if necessary
            int inputWidth = image.getWidth();
            int inputHeight = image.getHeight();
            if (inputWidth <= maxDimension && inputHeight <= maxDimension) {
                return inputFile;
            }

            System.out.println("Resizing " + inputFile.getName() +
                    " (" + inputWidth + ", " + inputHeight + ") to (" + maxDimension + ")");

            // Save existing metadata, if any
            TiffImageMetadata metadata = readExifMetadata(imageData);

            // resize
            image = Scalr.resize(image, Scalr.Method.ULTRA_QUALITY, maxDimension);
            image.flush();

            // rewrite resized image as byte[]
            byte[] resizedData = writeJPEG(image);

            // Re-code resizedData + metadata to imageData
            if (metadata != null) {
                imageData = writeExifMetadata(metadata, resizedData);
            } else {
                imageData = resizedData;
            }
            File outputFile = File.createTempFile("resized", ".jpg");
            FileOutputStream fileOutputStream = new FileOutputStream(outputFile);
            IOUtils.write(imageData,  fileOutputStream);
            fileOutputStream.close();
            return outputFile;
        } catch (IOException|ImageWriteException|ImageReadException e) {
            System.out.println("Couldn't resize "+inputFile);
            e.printStackTrace();
            return inputFile;
        }
    }

    private static TiffImageMetadata readExifMetadata(byte[] jpegData) throws ImageReadException, IOException {
        ImageMetadata imageMetadata = Imaging.getMetadata(jpegData);
        if (imageMetadata == null) {
            return null;
        }
        JpegImageMetadata jpegMetadata = (JpegImageMetadata)imageMetadata;
        TiffImageMetadata exif = jpegMetadata.getExif();
        return exif;
    }

    private static byte[] writeExifMetadata(TiffImageMetadata metadata, byte[] jpegData)
            throws ImageReadException, ImageWriteException, IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExifRewriter exifRewriter = new ExifRewriter();
        exifRewriter.updateExifMetadataLossless(jpegData, out, metadata.getOutputSet());
        out.close();
        return out.toByteArray();
    }

    private static byte[] writeJPEG(BufferedImage image) throws IOException {
        ByteArrayOutputStream jpegOut = new ByteArrayOutputStream();
        ImageIO.write(image, "JPEG", jpegOut);
        jpegOut.close();
        return jpegOut.toByteArray();
    }

}
