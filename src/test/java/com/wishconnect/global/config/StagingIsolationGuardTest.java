package com.wishconnect.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class StagingIsolationGuardTest {
    private MockEnvironment valid() {
        var env = new MockEnvironment().withProperty("spring.datasource.url",
                "jdbc:postgresql://localhost:15439/wishconnect_staging")
                .withProperty("spring.data.redis.database", "1")
                .withProperty("scholarship.sync.scheduled", "false")
                .withProperty("notification.scheduled", "false");
        env.setActiveProfiles("staging");
        return env;
    }

    private StagingIsolationGuard guard(MockEnvironment env) {
        var guard = new StagingIsolationGuard();
        guard.setEnvironment(env);
        return guard;
    }

    @Test
    void startsAsEarlySpringPostProcessorWithoutAutowiredConstructor() {
        new ApplicationContextRunner().withUserConfiguration(StagingIsolationGuard.class)
                .withPropertyValues("spring.profiles.active=staging",
                        "spring.datasource.url=jdbc:postgresql://localhost/wishconnect_staging",
                        "spring.data.redis.database=1", "scholarship.sync.scheduled=false",
                        "notification.scheduled=false")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(StagingIsolationGuard.class));
    }

    @Test
    void acceptsIsolatedStaging() {
        assertThatCode(() -> guard(valid()).postProcessBeanFactory(null))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsProductionDatabaseEvenWithStagingProfile() {
        var env = valid().withProperty("spring.datasource.url", "jdbc:postgresql://localhost/wishconnect");
        assertThatThrownBy(() -> guard(env).postProcessBeanFactory(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMixedProfilesAndDefaultRedisAndScheduledJobs() {
        var mixed = valid();
        mixed.setActiveProfiles("prod", "staging");
        assertThatThrownBy(() -> guard(mixed).postProcessBeanFactory(null))
                .isInstanceOf(IllegalStateException.class);
        var redis = valid().withProperty("spring.data.redis.database", "0");
        assertThatThrownBy(() -> guard(redis).postProcessBeanFactory(null))
                .isInstanceOf(IllegalStateException.class);
        var jobs = valid().withProperty("scholarship.sync.scheduled", "true");
        assertThatThrownBy(() -> guard(jobs).postProcessBeanFactory(null))
                .isInstanceOf(IllegalStateException.class);
    }
}
