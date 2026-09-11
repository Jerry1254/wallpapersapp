package com.qingjing.wallpaper.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.qingjing.wallpaper.asset.application.FileStorage;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class FileStorageBoundaryTest {

    @Test
    void businessStoragePortDoesNotExposeFilesystemPaths() {
        Stream<Class<?>> methodTypes = Arrays.stream(FileStorage.class.getDeclaredMethods())
                .flatMap(method -> Stream.concat(
                        Stream.of(method.getReturnType()),
                        Arrays.stream(method.getParameterTypes())));

        assertThat(methodTypes).noneMatch(Path.class::isAssignableFrom);
    }
}
