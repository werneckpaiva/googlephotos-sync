package com.werneckpaiva.googlephotosbatch;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class ImageUtils {

    public static File resizeJPGImage(File file, int maxDimension) {
        try {
            final ImageReader jpegImageReader = ImageIO.getImageReadersByFormatName("jpeg").next();
            jpegImageReader.setInput(ImageIO.createImageInputStream(file));
            BufferedImage inputImage = jpegImageReader.read(0);

            int inputWidth = inputImage.getWidth();
            int inputHeight = inputImage.getHeight();
            if (inputWidth <= maxDimension && inputHeight <= maxDimension) {
                return file;
            }
            float ratio = Float.valueOf(inputWidth) / Float.valueOf(inputHeight);
            int outputWidth = 0, outputHeight = 0;
            if (ratio > 0) {
                outputWidth = maxDimension;
                outputHeight = Math.round(maxDimension / ratio);
            } else {
                outputHeight = maxDimension;
                outputWidth = Math.round(maxDimension * ratio);
            }

            System.out.println("Resizing " + file.getName() +
                    " (" + inputWidth + ", " + inputHeight + ") to (" + outputWidth + ", " + outputHeight + ")");

            IIOMetadata imageMetadata = null;
            try {
                imageMetadata = jpegImageReader.getImageMetadata(0);
            } catch (javax.imageio.IIOException e) {
                System.out.println("Couldn't read image exif");
            }
            File outputFile = File.createTempFile("resized", ".jpg");

            BufferedImage outputBufferedImage = new BufferedImage(outputWidth, outputHeight, inputImage.getType());
            Graphics2D g2d = outputBufferedImage.createGraphics();
            g2d.drawImage(inputImage, 0, 0, outputWidth, outputHeight, null);
            g2d.dispose();

            final ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();

            ImageOutputStream imageOutputStream = ImageIO.createImageOutputStream(outputFile);
            writer.setOutput(imageOutputStream);

            JPEGImageWriteParam imageWriteParam = (JPEGImageWriteParam) writer.getDefaultWriteParam();
            imageWriteParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            imageWriteParam.setCompressionQuality(.9f);
            imageWriteParam.setOptimizeHuffmanTables(true);

            writer.write(null, new IIOImage(outputBufferedImage, null, imageMetadata), imageWriteParam);
            writer.dispose();

            ImageIO.write(outputBufferedImage, "jpg", imageOutputStream);

            return outputFile;
        } catch(IOException e) {
            return file;
        }
    }

}
