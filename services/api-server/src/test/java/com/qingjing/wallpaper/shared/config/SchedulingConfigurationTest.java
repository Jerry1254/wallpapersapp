package com.qingjing.wallpaper.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

class SchedulingConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfiguration.class);

    @Test
    void schedulingIsEnabledByDefault() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    void schedulingCanBeDisabledForTheTrafficServingSlot() {
        contextRunner
                .withPropertyValues("qingjing.scheduling-enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
    }
}
