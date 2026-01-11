package com.werneckpaiva.googlephotosbatch.exception;

public class PermissionDeniedToLoadAlbumsException extends Exception {
    public PermissionDeniedToLoadAlbumsException(RuntimeException e) {
        super(e);
    }
}
