package com.wishconnect.global.config;

import java.net.URI;
import java.util.Arrays;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** staging 설정 실수로 운영 프로필·기본 DB·자동 배치를 사용하는 기동을 거부한다. */
@Component
@Profile("staging")
public class StagingIsolationGuard implements BeanFactoryPostProcessor, EnvironmentAware {
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        if (Arrays.stream(environment.getActiveProfiles()).anyMatch(p -> p.equals("prod") || p.equals("local"))) {
            throw new IllegalStateException("staging은 prod/local 프로필과 함께 실행할 수 없습니다.");
        }
        String url = environment.getRequiredProperty("spring.datasource.url");
        String database = URI.create(url.replaceFirst("^jdbc:", "")).getPath();
        if (database == null || !database.matches("/[A-Za-z0-9_]+_staging")) {
            throw new IllegalStateException("staging DB 이름은 _staging으로 끝나야 합니다.");
        }
        if (environment.getProperty("spring.data.redis.database", Integer.class, 0) <= 0) {
            throw new IllegalStateException("staging은 Redis 기본 DB 0을 사용할 수 없습니다.");
        }
        for (String property : new String[]{"scholarship.sync.scheduled", "notification.scheduled"}) {
            if (environment.getProperty(property, Boolean.class, true)) {
                throw new IllegalStateException("staging 자동 배치는 비활성화해야 합니다: " + property);
            }
        }
    }
}
