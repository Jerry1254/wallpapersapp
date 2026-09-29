package com.qingjing.wallpaper.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class MovingPhotoPublisherProxyContractTest {

    @Test
    void transactionalPublisherCanBeSubclassProxied() throws NoSuchMethodException {
        assertThat(Modifier.isFinal(MovingPhotoPublisher.class.getModifiers())).isFalse();
        assertThat(MovingPhotoPublisher.class.getMethod("buildIfSupported", long.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }
}
