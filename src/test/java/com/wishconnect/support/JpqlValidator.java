package com.wishconnect.support;

import jakarta.persistence.Converter;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * DB 없이 JPQL 을 Hibernate 메타모델에 대고 검사한다.
 *
 * <p>이 프로젝트의 테스트는 EntityManager 를 목으로 두기 때문에 JPQL 문자열이 틀려도 통과한다.
 * 2026-08-20 에 {@code update ScholarshipEvent e set e.scholarship.id ...} (없는 연관 경로)가 들어가
 * 운영의 모든 병합 승인이 500 으로 실패했는데, 목 테스트는 오히려 그 잘못된 쿼리를 요구하고 있었다.
 *
 * <p>Hibernate 6 은 {@code createQuery} 시점에 경로·속성을 해석(semantic analysis)하므로, JDBC 연결
 * 없이도 "없는 속성" 오류를 잡을 수 있다. {@code allow_jdbc_metadata_access=false} 로 부팅 중 DB 접속을
 * 막는다. SQL 실행까지는 하지 않으므로 컬럼·제약 불일치는 여기서 잡지 못한다(실제 DB 검증이 따로 필요).
 */
public final class JpqlValidator implements AutoCloseable {

	private static final String BASE_PACKAGE = "com.wishconnect";

	private final StandardServiceRegistry registry;
	private final SessionFactory sessionFactory;

	public JpqlValidator() {
		this.registry = new StandardServiceRegistryBuilder()
				.applySetting(AvailableSettings.DIALECT, "org.hibernate.dialect.PostgreSQLDialect")
				.applySetting("hibernate.boot.allow_jdbc_metadata_access", "false")
				.applySetting(AvailableSettings.HBM2DDL_AUTO, "none")
				.build();
		MetadataSources sources = new MetadataSources(registry);
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
		scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
		scanner.addIncludeFilter(new AnnotationTypeFilter(Embeddable.class));
		scanner.addIncludeFilter(new AnnotationTypeFilter(MappedSuperclass.class));
		scanner.addIncludeFilter(new AnnotationTypeFilter(Converter.class));
		for (BeanDefinition candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
			try {
				sources.addAnnotatedClass(Class.forName(candidate.getBeanClassName()));
			} catch (ClassNotFoundException e) {
				throw new IllegalStateException(e);
			}
		}
		this.sessionFactory = sources.buildMetadata().buildSessionFactory();
	}

	/** 해석에 실패하면 Hibernate 예외(예: PathElementException)를 그대로 던진다. */
	public void validate(String jpql) {
		try (EntityManager entityManager = sessionFactory.createEntityManager()) {
			entityManager.createQuery(jpql);
		}
	}

	@Override
	public void close() {
		sessionFactory.close();
		StandardServiceRegistryBuilder.destroy(registry);
	}
}
