package com.github.analyticshub.service;

import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.database.project.ProjectSchemaMigrator;
import com.github.analyticshub.dto.ActorIdentityLinkRequest;
import com.github.analyticshub.dto.ActorIdentityLinkResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class ActorIdentityLinkServicePostgresIT {

    private static final String PROJECT_ID = "actor_link_project";
    private static final String PREFIX = "actor_";
    private static final AtomicInteger SCHEMA_SEQUENCE = new AtomicInteger();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("actor_link_test")
            .withUsername("actor_link_test")
            .withPassword("actor_link_password");

    private ActorIdentityLinkService service;
    private JdbcTemplate jdbcTemplate;
    private String linkTable;
    private String suppressionTable;

    @BeforeEach
    void setUp() {
        String schema = "actor_link_it_" + SCHEMA_SEQUENCE.incrementAndGet();
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema + ",public");
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        new ProjectSchemaMigrator().migrate(dataSource, schema, PREFIX);

        linkTable = quoted(PREFIX + "actor_identity_links");
        suppressionTable = quoted(PREFIX + "actor_suppressions");
        MultiDataSourceManager manager = manager(dataSource, schema);
        service = new ActorIdentityLinkService(manager, new ProjectTransactionExecutor());
    }

    @Test
    void oneCloudActorCanOwnSeveralAnonymousPhases() {
        UUID cloud = UUID.randomUUID();
        UUID firstAnonymous = UUID.randomUUID();
        UUID secondAnonymous = UUID.randomUUID();

        ActorIdentityLinkResponse first = service.link(PROJECT_ID, request(firstAnonymous, cloud));
        ActorIdentityLinkResponse second = service.link(PROJECT_ID, request(secondAnonymous, cloud));

        assertThat(first.status()).isEqualTo("created");
        assertThat(second.status()).isEqualTo("created");
        Integer linkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + linkTable + " WHERE project_id = ? AND canonical_actor_id = ?::uuid",
                Integer.class,
                PROJECT_ID,
                cloud.toString()
        );
        assertThat(linkCount).isEqualTo(2);
    }

    @Test
    void retriesAreIdempotentButConflictingCloudAccountsAreRejected() {
        UUID binding = UUID.randomUUID();
        UUID anonymous = UUID.randomUUID();
        UUID cloud = UUID.randomUUID();
        ActorIdentityLinkRequest request = new ActorIdentityLinkRequest(
                binding,
                anonymous,
                cloud,
                Instant.parse("2026-01-01T00:00:00Z")
        );

        assertThat(service.link(PROJECT_ID, request).status()).isEqualTo("created");
        assertThat(service.link(PROJECT_ID, request).status()).isEqualTo("existing");
        assertThatThrownBy(() -> service.link(PROJECT_ID, new ActorIdentityLinkRequest(
                UUID.randomUUID(),
                anonymous,
                UUID.randomUUID(),
                Instant.parse("2026-01-02T00:00:00Z")
        ))).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("ACTOR_LINK_SOURCE_CONFLICT")
        );
    }

    @Test
    void aliasChainsAndBindingIdReuseAreRejected() {
        UUID firstAnonymous = UUID.randomUUID();
        UUID cloud = UUID.randomUUID();
        UUID binding = UUID.randomUUID();
        service.link(PROJECT_ID, new ActorIdentityLinkRequest(
                binding,
                firstAnonymous,
                cloud,
                Instant.now()
        ));

        assertThatThrownBy(() -> service.link(PROJECT_ID, new ActorIdentityLinkRequest(
                UUID.randomUUID(),
                cloud,
                UUID.randomUUID(),
                Instant.now()
        ))).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("ACTOR_LINK_CHAIN_NOT_ALLOWED")
        );
        assertThatThrownBy(() -> service.link(PROJECT_ID, new ActorIdentityLinkRequest(
                binding,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now()
        ))).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("ACTOR_LINK_IDEMPOTENCY_CONFLICT")
        );
        assertThatThrownBy(() -> service.link(PROJECT_ID, new ActorIdentityLinkRequest(
                binding,
                firstAnonymous,
                cloud,
                Instant.parse("2026-01-02T00:00:00Z")
        ))).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("ACTOR_LINK_IDEMPOTENCY_CONFLICT")
        );
    }

    @Test
    void suppressedCanonicalActorAcknowledgesLateLinksWithoutRecreatingAlias() {
        UUID canonical = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO " + suppressionTable +
                        " (project_id, canonical_actor_sha256, suppressed_at) VALUES (?, ?, NOW())",
                PROJECT_ID,
                ActorIdentitySuppression.canonicalHash(canonical)
        );

        ActorIdentityLinkResponse response = service.link(PROJECT_ID, request(UUID.randomUUID(), canonical));

        assertThat(response.status()).isEqualTo("suppressed");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + linkTable + " WHERE project_id = ?",
                Integer.class,
                PROJECT_ID
        )).isZero();
    }

    @Test
    void concurrentOppositeEdgesCannotCreateAnAliasChain() throws Exception {
        UUID first = UUID.randomUUID();
        UUID middle = UUID.randomUUID();
        UUID last = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Object> firstLink = executor.submit(() -> linkAfterBarrier(ready, start, first, middle));
            Future<Object> secondLink = executor.submit(() -> linkAfterBarrier(ready, start, middle, last));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Object firstResult = getResult(firstLink);
            Object secondResult = getResult(secondLink);
            long created = java.util.stream.Stream.of(firstResult, secondResult)
                    .filter(ActorIdentityLinkResponse.class::isInstance)
                    .count();
            long rejectedChains = java.util.stream.Stream.of(firstResult, secondResult)
                    .filter(BusinessException.class::isInstance)
                    .map(BusinessException.class::cast)
                    .filter(exception -> "ACTOR_LINK_CHAIN_NOT_ALLOWED".equals(exception.getCode()))
                    .count();

            assertThat(created).isEqualTo(1);
            assertThat(rejectedChains).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + linkTable + " WHERE project_id = ?",
                    Integer.class,
                    PROJECT_ID
            )).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private Object linkAfterBarrier(
            CountDownLatch ready,
            CountDownLatch start,
            UUID source,
            UUID canonical
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent actor-link test did not start in time");
        }
        try {
            return service.link(PROJECT_ID, request(source, canonical));
        } catch (BusinessException exception) {
            return exception;
        }
    }

    private Object getResult(Future<Object> future) throws Exception {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private ActorIdentityLinkRequest request(UUID source, UUID canonical) {
        return new ActorIdentityLinkRequest(UUID.randomUUID(), source, canonical, Instant.now());
    }

    private MultiDataSourceManager manager(DataSource dataSource, String schema) {
        MultiDataSourceManager manager = mock(MultiDataSourceManager.class);
        MultiDataSourceManager.ProjectConfig config = new MultiDataSourceManager.ProjectConfig(
                PROJECT_ID,
                "Actor Link Test",
                "localhost",
                5432,
                "actor_link_test",
                schema,
                "actor_link_test",
                "actor_link_password",
                PREFIX,
                true
        );
        when(manager.getProjectConfig(PROJECT_ID)).thenReturn(config);
        when(manager.getDataSource(PROJECT_ID)).thenReturn(dataSource);
        when(manager.getTableName(PROJECT_ID, "actor_identity_links")).thenReturn(linkTable);
        when(manager.getTableName(PROJECT_ID, "actor_suppressions")).thenReturn(suppressionTable);
        return manager;
    }

    private static String quoted(String identifier) {
        return '"' + identifier + '"';
    }
}
