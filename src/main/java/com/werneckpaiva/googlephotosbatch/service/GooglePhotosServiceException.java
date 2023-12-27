package com.werneckpaiva.googlephotosbatch.service;

public class GooglePhotosServiceException extends Exception{
    public GooglePhotosServiceException(String message, Exception e) {
        super(message, e);
    }
}
