package com.werneckpaiva.googlephotosbatch.exception;

import com.google.api.gax.rpc.PermissionDeniedException;

public class PermissionDeniedToLoadAlbumsException extends Throwable {
    public PermissionDeniedToLoadAlbumsException(PermissionDeniedException e) {
        super(e);
    }
}
