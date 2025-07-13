package com.werneckpaiva.googlephotosbatch.exception;

public class GooglePhotosServiceException extends Exception{
    public GooglePhotosServiceException(String message, Exception e) {
        super(message, e);
    }
}
