package com.werneckpaiva.googlephotosbatch.exception;


public class PermissionDeniedToLoadAlbumsException extends Throwable {
    public PermissionDeniedToLoadAlbumsException(RuntimeException e) {
        super(e);
    }
}
