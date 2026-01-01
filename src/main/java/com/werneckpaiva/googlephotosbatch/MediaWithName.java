package com.werneckpaiva.googlephotosbatch;

import java.io.File;

public record MediaWithName(String name, File file, String uploadToken) implements Comparable<MediaWithName> {
    public MediaWithName(String name, File resizedFile) {
        this(name, resizedFile, null);
    }

    @Override
    public int compareTo(MediaWithName other) {
        return this.name.compareTo(other.name);
    }
}
