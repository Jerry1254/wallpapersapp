package com.qingjing.wallpaper.asset.application;

import java.io.InputStream;

/**
 * Binary storage boundary used by the asset module. Implementations own all path handling;
 * callers only exchange opaque staging tokens and relative storage keys.
 */
public interface FileStorage {

    StagedObject stage(InputStream source, long maximumBytes);

    StoredContent openStaged(StagedObject stagedObject);

    StoredObject commit(StagedObject stagedObject, String fileExtension);

    StoredContent open(StorageKey storageKey);

    void discard(StagedObject stagedObject);
}
