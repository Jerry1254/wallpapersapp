package com.qingjing.wallpaper.asset.application;

/** Produces the bounded WebP derivative stored for a wallpaper list cover. */
@FunctionalInterface
public interface CoverImageOptimizer {

    StagedObject optimize(StagedObject source);
}
