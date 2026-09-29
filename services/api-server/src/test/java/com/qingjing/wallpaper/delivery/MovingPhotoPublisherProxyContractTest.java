package com.qingjing.wallpaper.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class MovingPhotoPublisherProxyContractTest {

    @Test
    void transactionalPublishersCanBeSubclassProxied() throws NoSuchMethodException {
        assertTransactionalPublisherCanBeSubclassProxied(MovingPhotoPublisher.class);
        assertTransactionalPublisherCanBeSubclassProxied(LivePhotoPublisher.class);
    }

    private static void assertTransactionalPublisherCanBeSubclassProxied(Class<?> publisher)
            throws NoSuchMethodException {
        assertThat(Modifier.isFinal(publisher.getModifiers())).isFalse();
        assertThat(publisher.getMethod("buildIfSupported", long.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }
}
