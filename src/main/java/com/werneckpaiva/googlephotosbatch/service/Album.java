package com.werneckpaiva.googlephotosbatch.service;

public record Album(String title, String id, Boolean isWriteable) {
    public Album(String title, String id) {
        this(title, id, false);
    }
}
