package com.github.analyticshub.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.analyticshub.config.AnalyticsQueryProperties;
import com.github.analyticshub.config.MultiDataSourceManager;
import com.github.analyticshub.database.project.ProjectSchemaMigrator;
import com.github.analyticshub.dto.AdminFunnelGroupResult;
import com.github.analyticshub.dto.AdminFunnelResponse;
import com.github.analyticshub.dto.AdminAppVersionDistributionResponse;
import com.github.analyticshub.dto.AdminEventRecord;
import com.github.analyticshub.dto.AdminEventsResponse;
import com.github.analyticshub.dto.AdminEventJourneyResponse;
import com.github.analyticshub.dto.AdminJourneyEventRecord;
import com.github.analyticshub.dto.AdminMetricsOverviewResponse;
import com.github.analyticshub.dto.AdminMetricsTrendResponse;
import com.github.analyticshub.dto.AdminRetentionResponse;
import com.github.analyticshub.dto.AnalyticsDataQualityResponse;
import com.github.analyticshub.dto.AnalyticsPropertyDataType;
import com.github.analyticshub.dto.AnalyticsPropertyDefinitionResponse;
import com.github.analyticshub.dto.AnalyticsPropertyDefinitionsResponse;
import com.github.analyticshub.dto.TrustedSchemaPolicyResponse;
import com.github.analyticshub.exception.BusinessException;
import com.github.analyticshub.projectdb.ProjectTransactionExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.eq;

@Testcontainers
class AdminProductAnalyticsServicePostgresIT {

    private static final String PROJECT_ID = "product_analytics_project";
    private static final String PREFIX = "product_";
    private static final AtomicInteger SCHEMA_SEQUENCE = new AtomicInteger();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("product_analytics_test")
            .withUsername("product_test")
            .withPassword("product_test_password");

    private JdbcTemplate jdbcTemplate;
    private MultiDataSourceManager dataSourceManager;
    private AdminProductAnalyticsService service;
    private AdminMetricsService metricsService;
    private ActorIdentityResolver actorIdentityResolver;
    private AnalyticsPropertyFilterService propertyFilterService;
    private AnalyticsQueryProperties queryProperties;

    @BeforeEach
    void setUp() {
        String schema = "product_analytics_it_" + SCHEMA_SEQUENCE.incrementAndGet();
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl() + "&currentSchema=" + schema + ",public");
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);

        new ProjectSchemaMigrator().migrate(dataSource, schema, PREFIX);

        dataSourceManager = mock(MultiDataSourceManager.class);
        MultiDataSourceManager.ProjectConfig projectConfig = new MultiDataSourceManager.ProjectConfig(
                PROJECT_ID,
                "Product Analytics Test",
                "localhost",
                5432,
                "product_analytics_test",
                schema,
                "product_test",
                "product_test_password",
                PREFIX,
                true
        );
        when(dataSourceManager.getProjectConfig(PROJECT_ID)).thenReturn(projectConfig);
        when(dataSourceManager.getDataSource(PROJECT_ID)).thenReturn(dataSource);
        when(dataSourceManager.getTableName(PROJECT_ID, "events")).thenReturn(quoted(PREFIX + "events"));
        when(dataSourceManager.getTableName(PROJECT_ID, "devices")).thenReturn(quoted(PREFIX + "devices"));
        when(dataSourceManager.getTableName(PROJECT_ID, "sessions")).thenReturn(quoted(PREFIX + "sessions"));
        when(dataSourceManager.getTableName(PROJECT_ID, "actor_identity_links"))
                .thenReturn(quoted(PREFIX + "actor_identity_links"));

        SemanticDictionaryService semantics = mock(SemanticDictionaryService.class);
        when(semantics.resolveActiveEventAliases(eq(PROJECT_ID), anyList())).thenAnswer(invocation -> {
            Map<String, List<String>> result = new LinkedHashMap<>();
            for (String key : invocation.<List<String>>getArgument(1)) result.put(key, List.of(key));
            return result;
        });
        when(semantics.resolveAvailableActiveEventAliases(eq(PROJECT_ID), anyList())).thenAnswer(invocation -> {
            Map<String, List<String>> result = new LinkedHashMap<>();
            for (String key : invocation.<List<String>>getArgument(1)) result.put(key, List.of(key));
            return result;
        });
        actorIdentityResolver = new ActorIdentityResolver();
        queryProperties = new AnalyticsQueryProperties();
        propertyFilterService = mock(AnalyticsPropertyFilterService.class);
        when(propertyFilterService.compile(eq(PROJECT_ID), nullable(String.class), anyString()))
                .thenReturn(AnalyticsPropertyFilterService.CompiledPropertyFilters.empty());
        service = new AdminProductAnalyticsService(
                dataSourceManager,
                semantics,
                actorIdentityResolver,
                queryProperties,
                new ProjectTransactionExecutor(),
                propertyFilterService
        );
        metricsService = new AdminMetricsService(
                dataSourceManager, semantics, actorIdentityResolver, propertyFilterService,
                queryProperties, new ProjectTransactionExecutor()
        );
    }

    @Test
    void funnelPreservesEventOrderAndFirstTouchGrouping() {
        UUID organicUser = UUID.randomUUID();
        UUID adUser = UUID.randomUUID();
        UUID reorderedUser = UUID.randomUUID();

        insertEvent(organicUser, "landing", "2026-01-01T01:00:00Z", "{\"source\":\"organic\"}");
        insertEvent(organicUser, "checkout", "2026-01-01T01:05:00Z", null);
        insertEvent(organicUser, "purchase", "2026-01-01T01:10:00Z", null);
        // A later first-step event must not move this actor to another attribution group.
        insertEvent(organicUser, "landing", "2026-01-01T01:15:00Z", "{\"source\":\"ad\"}");

        insertEvent(adUser, "landing", "2026-01-01T02:00:00Z", "{\"source\":\"ad\"}");
        insertEvent(adUser, "purchase", "2026-01-01T02:05:00Z", null);

        // Checkout before entry does not satisfy step two; the later checkout does.
        insertEvent(reorderedUser, "checkout", "2026-01-01T02:55:00Z", null);
        insertEvent(reorderedUser, "landing", "2026-01-01T03:00:00Z", "{\"source\":\"organic\"}");
        insertEvent(reorderedUser, "checkout", "2026-01-01T03:05:00Z", null);

        AdminFunnelResponse response = service.getFunnel(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "landing,checkout,purchase",
                "source"
        );

        assertThat(response.attributionModel()).isEqualTo("first_touch_actor");
        assertThat(response.groups()).extracting(AdminFunnelGroupResult::groupValue)
                .containsExactly("ad", "organic");

        AdminFunnelGroupResult ad = response.groups().getFirst();
        assertThat(ad.steps()).extracting(step -> step.users()).containsExactly(1L, 0L, 0L);
        assertThat(ad.steps()).extracting(step -> step.conversionRate()).containsExactly(1d, 0d, 0d);
        assertThat(ad.steps()).extracting(step -> step.dropOffRate()).containsExactly(0d, 1d, 0d);

        AdminFunnelGroupResult organic = response.groups().get(1);
        assertThat(organic.steps()).extracting(step -> step.users()).containsExactly(2L, 2L, 1L);
        assertThat(organic.steps()).extracting(step -> step.conversionRate()).containsExactly(1d, 1d, 0.5d);
        assertThat(organic.steps()).extracting(step -> step.dropOffRate()).containsExactly(0d, 0d, 0.5d);
    }

    @Test
    void funnelCanonicalizesTypedNumericGroupsAndExcludesInvalidValues() {
        when(propertyFilterService.requireGroupable(PROJECT_ID, "score"))
                .thenReturn(AnalyticsPropertyDataType.NUMBER);
        UUID integerActor = UUID.randomUUID();
        UUID decimalActor = UUID.randomUUID();
        UUID exponentActor = UUID.randomUUID();
        UUID invalidActor = UUID.randomUUID();
        insertEvent(integerActor, "landing", "2026-01-01T01:00:00Z", "{\"score\":1}");
        insertEvent(decimalActor, "landing", "2026-01-01T02:00:00Z", "{\"score\":1.0}");
        insertEvent(exponentActor, "landing", "2026-01-01T03:00:00Z", "{\"score\":1e0}");
        insertEvent(invalidActor, "landing", "2026-01-01T04:00:00Z", "{\"score\":\"1\"}");

        AdminFunnelResponse response = service.getFunnel(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "landing,purchase",
                "score"
        );

        assertThat(response.groups()).singleElement().satisfies(group -> {
            assertThat(group.groupValue()).isEqualTo("1");
            assertThat(group.steps()).extracting(step -> step.users()).containsExactly(3L, 0L);
        });
    }

    @Test
    void funnelRejectsGroupCardinalityBeyondTheResponseBudget() {
        queryProperties.setMaxFunnelGroups(2);
        insertEvent(UUID.randomUUID(), "landing", "2026-01-01T01:00:00Z", "{\"source\":\"a\"}");
        insertEvent(UUID.randomUUID(), "landing", "2026-01-01T02:00:00Z", "{\"source\":\"b\"}");
        insertEvent(UUID.randomUUID(), "landing", "2026-01-01T03:00:00Z", "{\"source\":\"c\"}");

        assertThatThrownBy(() -> service.getFunnel(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "landing,purchase",
                "source"
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("ANALYTICS_QUERY_BUDGET_EXCEEDED")
        );
    }

    @Test
    void funnelRejectsOversizedGroupAndJourneyDimensionValues() {
        queryProperties.setMaxDimensionValueLength(4);
        insertEvent(
                UUID.randomUUID(), "landing", "2026-01-01T01:00:00Z",
                "{\"source\":\"12345\",\"flow_id\":\"abcde\"}"
        );

        assertThatThrownBy(() -> service.getFunnel(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "landing,purchase",
                "source"
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("ANALYTICS_QUERY_BUDGET_EXCEEDED")
        );
        assertThatThrownBy(() -> service.getFunnel(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "landing,purchase",
                null,
                "flow_id"
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("ANALYTICS_QUERY_BUDGET_EXCEEDED")
        );
    }

    @Test
    void legacyFunnelExcludesJsonNullGroupWithoutTreatingItAsMissingOrFailing() {
        insertEvent(UUID.randomUUID(), "landing", "2026-01-01T01:00:00Z", "{\"source\":null}");
        insertEvent(UUID.randomUUID(), "landing", "2026-01-01T02:00:00Z", "{}");

        AdminFunnelResponse response = service.getFunnel(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "landing,purchase",
                "source"
        );

        assertThat(response.groups()).singleElement().satisfies(group -> {
            assertThat(group.groupValue()).isEqualTo("(none)");
            assertThat(group.steps()).extracting(step -> step.users()).containsExactly(1L, 0L);
        });
    }

    @Test
    void funnelKeepsLegacyUngovernedNumericGroupsReadable() {
        UUID actor = UUID.randomUUID();
        insertEvent(actor, "landing", "2026-01-01T01:00:00Z", "{\"legacy_build\":42}");

        AdminFunnelResponse response = service.getFunnel(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "landing,purchase",
                "legacy_build"
        );

        assertThat(response.groups()).singleElement().satisfies(group -> {
            assertThat(group.groupValue()).isEqualTo("42");
            assertThat(group.steps()).extracting(step -> step.users()).containsExactly(1L, 0L);
        });
    }

    @Test
    void funnelUsesDatabaseIdToOrderEventsWithTheSameTimestamp() {
        String timestamp = "2026-01-01T01:00:00Z";
        UUID reversedActor = UUID.randomUUID();
        insertEvent(reversedActor, "checkout", timestamp, null);
        insertEvent(reversedActor, "landing", timestamp, null);

        UUID orderedActor = UUID.randomUUID();
        insertEvent(orderedActor, "landing", timestamp, null);
        insertEvent(orderedActor, "checkout", timestamp, null);

        AdminFunnelResponse response = service.getFunnel(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "landing,checkout",
                null
        );

        assertThat(response.groups()).singleElement().satisfies(group ->
                assertThat(group.steps()).extracting(step -> step.users()).containsExactly(2L, 1L)
        );
    }

    @Test
    void funnelCanCountMultiplePaywallJourneysFromTheSameActor() {
        UUID actor = UUID.randomUUID();
        insertEvent(actor, "paywall_prompt_viewed", "2026-01-01T01:00:00Z",
                "{\"paywall_flow_id\":\"flow-1\",\"entry_point\":\"styling_effects\"}");
        insertEvent(actor, "paywall_prompt_action_selected", "2026-01-01T01:01:00Z",
                "{\"paywall_flow_id\":\"flow-1\",\"prompt_action\":\"primary_upgrade\"}");
        insertEvent(actor, "purchase_succeeded", "2026-01-01T01:02:00Z",
                "{\"paywall_flow_id\":\"flow-1\"}");

        insertEvent(actor, "paywall_prompt_viewed", "2026-01-01T02:00:00Z",
                "{\"paywall_flow_id\":\"flow-2\",\"entry_point\":\"sticker_panel\"}");
        insertEvent(actor, "paywall_prompt_action_selected", "2026-01-01T02:01:00Z",
                "{\"paywall_flow_id\":\"flow-2\",\"prompt_action\":\"primary_upgrade\"}");

        // 客户端 flow ID 不是全局身份；另一 actor 使用相同值仍是一条独立旅程。
        UUID otherActor = UUID.randomUUID();
        insertEvent(otherActor, "paywall_prompt_viewed", "2026-01-01T03:00:00Z",
                "{\"paywall_flow_id\":\"flow-1\",\"entry_point\":\"styling_effects\"}");
        insertEvent(otherActor, "paywall_prompt_action_selected", "2026-01-01T03:01:00Z",
                "{\"paywall_flow_id\":\"flow-1\",\"prompt_action\":\"primary_upgrade\"}");

        // 缺少 journey key 的噪声事件不能串入任一付费旅程。
        insertEvent(actor, "purchase_succeeded", "2026-01-01T02:02:00Z", null);

        AdminFunnelResponse response = service.getFunnel(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "paywall_prompt_viewed,paywall_prompt_action_selected,purchase_succeeded",
                "entry_point",
                "paywall_flow_id"
        );

        assertThat(response.countingUnit()).isEqualTo("journeys");
        assertThat(response.journeyKey()).isEqualTo("paywall_flow_id");
        assertThat(response.attributionModel()).isEqualTo("first_touch_journey");
        assertThat(response.groups()).extracting(AdminFunnelGroupResult::groupValue)
                .containsExactly("sticker_panel", "styling_effects");
        assertThat(response.groups().getFirst().steps()).extracting(step -> step.users())
                .containsExactly(1L, 1L, 0L);
        assertThat(response.groups().get(1).steps()).extracting(step -> step.users())
                .containsExactly(2L, 2L, 1L);
    }

    @Test
    void funnelAndRetentionResolveAnonymousAliasesToTheCloudActor() {
        UUID anonymousActor = UUID.randomUUID();
        UUID cloudActor = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        jdbcTemplate.update(
                String.format(
                        "INSERT INTO %s (binding_id, project_id, source_actor_id, canonical_actor_id, linked_at) "
                                + "VALUES (?::uuid, ?, ?::uuid, ?::uuid, ?)",
                        quoted(PREFIX + "actor_identity_links")
                ),
                bindingId.toString(),
                PROJECT_ID,
                anonymousActor.toString(),
                cloudActor.toString().toUpperCase(),
                Timestamp.from(Instant.parse("2026-01-01T00:30:00Z"))
        );

        insertEvent(anonymousActor, "landing", "2026-01-01T01:00:00Z", "{\"flow_id\":\"login-flow\"}");
        insertEvent(cloudActor, "purchase", "2026-01-01T01:10:00Z", "{\"flow_id\":\"login-flow\"}");
        insertEvent(cloudActor, "return", "2026-01-02T02:00:00Z", null);

        AdminFunnelResponse funnel = service.getFunnel(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "landing,purchase",
                null
        );
        assertThat(funnel.groups().getFirst().steps())
                .extracting(step -> step.users())
                .containsExactly(1L, 1L);

        AdminFunnelResponse journeyFunnel = service.getFunnel(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "landing,purchase",
                null,
                "flow_id"
        );
        assertThat(journeyFunnel.groups().getFirst().steps())
                .extracting(step -> step.users())
                .containsExactly(1L, 1L);

        AdminRetentionResponse retention = service.getRetention(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "landing",
                "return",
                "1"
        );
        assertThat(retention.cohortUsers()).isEqualTo(1L);
        assertThat(retention.buckets().getFirst().retainedUsers()).isEqualTo(1L);
    }

    @Test
    void overviewCountsLinkedAnonymousPhasesAsOneActiveUser() {
        UUID firstAnonymousActor = UUID.randomUUID();
        UUID secondAnonymousActor = UUID.randomUUID();
        UUID cloudActor = UUID.randomUUID();
        insertLink(firstAnonymousActor, cloudActor, "2026-01-01T00:30:00Z");
        insertLink(secondAnonymousActor, cloudActor, "2026-01-01T02:30:00Z");

        insertEvent(firstAnonymousActor, "open", "2026-01-01T01:00:00Z", null);
        insertEvent(cloudActor, "open", "2026-01-01T02:00:00Z", null);
        insertEvent(secondAnonymousActor, "open", "2026-01-01T03:00:00Z", null);

        AdminMetricsOverviewResponse overview = metricsService.getOverview(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z"
        );

        assertThat(overview.usersActive()).isEqualTo(1);
        assertThat(overview.eventsTotal()).isEqualTo(3);
    }

    @Test
    void aggregateViewsRejectAnOversizedEventWindowInsteadOfReturningPartialStatistics() {
        queryProperties.setMaxCandidateRows(1);
        UUID actor = UUID.randomUUID();
        insertEvent(actor, "open", "2026-01-01T01:00:00Z", null);
        insertEvent(actor, "open", "2026-01-01T02:00:00Z", null);

        assertBudgetExceeded(() -> metricsService.getOverview(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"
        ));
        assertBudgetExceeded(() -> metricsService.getTrends(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "day"
        ));
        assertBudgetExceeded(() -> metricsService.getTopEvents(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", 10, "raw"
        ));
        assertBudgetExceeded(() -> metricsService.getAppVersionDistribution(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"
        ));
    }

    @Test
    void overviewAndTrendsRejectAnOversizedSessionWindowEvenWhenThereAreNoEvents() {
        queryProperties.setMaxCandidateRows(1);
        insertSession("2026-01-01T01:00:00Z");
        insertSession("2026-01-01T02:00:00Z");

        assertBudgetExceeded(() -> metricsService.getOverview(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"
        ));
        assertBudgetExceeded(() -> metricsService.getTrends(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "day"
        ));
    }

    @Test
    void filteredOverviewAndTrendsIgnoreUnrelatedSessionVolume() {
        queryProperties.setMaxCandidateRows(1);
        insertSession("2026-01-01T01:00:00Z");
        insertSession("2026-01-01T02:00:00Z");
        AnalyticsPropertyDefinitionService definitions = mock(AnalyticsPropertyDefinitionService.class);
        AnalyticsPropertyDefinitionResponse property = new AnalyticsPropertyDefinitionResponse(
                PROJECT_ID, "segment", Map.of("en", "Segment"), AnalyticsPropertyDataType.STRING,
                null, List.of("target"), true, false, false, false, true, Instant.EPOCH, Instant.EPOCH
        );
        when(definitions.requireCapabilities(PROJECT_ID, List.of("segment")))
                .thenReturn(Map.of("segment", property));
        AnalyticsPropertyFilterService filters = new AnalyticsPropertyFilterService(
                JsonMapper.builder().build(), definitions
        );
        AdminMetricsService filteredMetrics = new AdminMetricsService(
                dataSourceManager, mock(SemanticDictionaryService.class), actorIdentityResolver,
                filters, queryProperties, new ProjectTransactionExecutor()
        );
        insertEvent(UUID.randomUUID(), "open", "2026-01-01T03:00:00Z", "{\"segment\":\"target\"}");
        String encodedFilters = "[{\"propertyKey\":\"segment\",\"operator\":\"EQ\",\"values\":[\"target\"]}]";

        AdminMetricsOverviewResponse overview = filteredMetrics.getOverview(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", encodedFilters
        );
        AdminMetricsTrendResponse trends = filteredMetrics.getTrends(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "day", encodedFilters
        );

        assertThat(overview.eventsTotal()).isEqualTo(1L);
        assertThat(overview.sessionsTotal()).isZero();
        assertThat(trends.points()).singleElement().satisfies(point -> {
            assertThat(point.events()).isEqualTo(1L);
            assertThat(point.sessions()).isZero();
        });
    }

    @Test
    void overviewCountsDistinctDevicesWithEventsInTheRequestedRange() {
        UUID actor = UUID.randomUUID();
        UUID firstDevice = UUID.randomUUID();
        UUID secondDevice = UUID.randomUUID();

        insertEvent(actor.toString(), firstDevice, "open", "2026-01-01T01:00:00Z", null);
        insertEvent(actor.toString(), firstDevice, "open", "2026-01-01T02:00:00Z", null);
        insertEvent(actor.toString(), secondDevice, "open", "2026-01-01T03:00:00Z", null);
        insertEvent(actor.toString(), UUID.randomUUID(), "open", "2026-01-02T01:00:00Z", null);

        AdminMetricsOverviewResponse overview = metricsService.getOverview(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z"
        );

        assertThat(overview.devicesActive()).isEqualTo(2L);
        assertThat(overview.eventsTotal()).isEqualTo(3L);
    }

    @Test
    void overviewAndTrendsUseStableAccountSemanticsAndOccurrenceTime() {
        UUID actor = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        insertEvent(actor.toString(), device, "core.account.created", "2026-01-01T01:00:00Z", null);
        insertEvent(actor.toString(), device, "core.account.recreated", "2026-01-02T01:00:00Z", null);
        insertEvent(actor.toString(), device, "unrelated", "2026-01-02T02:00:00Z", null);

        AdminMetricsOverviewResponse overview = metricsService.getOverview(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-03T00:00:00Z"
        );
        AdminMetricsTrendResponse trends = metricsService.getTrends(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-03T00:00:00Z", "day"
        );

        assertThat(overview.cloudAccountsCreated()).isEqualTo(1L);
        assertThat(overview.cloudAccountsRecreated()).isEqualTo(1L);
        assertThat(overview.availableMetricKeys())
                .containsExactly(
                        "system.active_devices",
                        "system.active_actors",
                        "system.event_occurrences",
                        "system.top_active_app_version",
                        "core.account.created",
                        "core.account.recreated"
                );
        assertThat(trends.availableMetricKeys())
                .containsExactly(
                        "system.active_actors",
                        "system.active_devices",
                        "core.account.created",
                        "core.account.recreated"
                );
        assertThat(trends.points()).extracting(point -> point.activeUsers()).containsExactly(1L, 1L);
        assertThat(trends.points()).extracting(point -> point.cloudAccountsCreated()).containsExactly(1L, 0L);
        assertThat(trends.points()).extracting(point -> point.cloudAccountsRecreated()).containsExactly(0L, 1L);
    }

    @Test
    void operationalReportsUseOccurrenceTimeInsteadOfDelayedUploadTime() {
        UUID actor = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        insertEvent(
                actor.toString(),
                device,
                "offline_open",
                "2026-01-01T10:00:00Z",
                "2026-01-03T10:00:00Z",
                "{\"app_version\":\"1.1.6\",\"build_number\":\"116\"}"
        );

        AdminMetricsOverviewResponse occurrenceDay = metricsService.getOverview(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"
        );
        AdminMetricsOverviewResponse uploadDay = metricsService.getOverview(
                PROJECT_ID, "2026-01-03T00:00:00Z", "2026-01-04T00:00:00Z"
        );
        AdminMetricsTrendResponse trends = metricsService.getTrends(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "day"
        );
        AdminFunnelResponse funnel = service.getFunnel(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "offline_open,offline_opened_again",
                null
        );
        AdminEventsResponse eventRecords = new AdminEventQueryService(
                dataSourceManager,
                JsonMapper.builder().build(),
                actorIdentityResolver
        ).listEvents(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                1,
                20,
                null,
                null,
                null,
                null
        );

        assertThat(occurrenceDay.eventsTotal()).isEqualTo(1L);
        assertThat(occurrenceDay.devicesActive()).isEqualTo(1L);
        assertThat(uploadDay.eventsTotal()).isZero();
        assertThat(trends.points()).hasSize(1);
        assertThat(trends.points().getFirst().events()).isEqualTo(1L);
        assertThat(trends.points().getFirst().activeDevices()).isEqualTo(1L);
        assertThat(trends.points().getFirst().activeUsers()).isEqualTo(1L);
        assertThat(funnel.groups()).hasSize(1);
        assertThat(funnel.groups().getFirst().steps().getFirst().users()).isEqualTo(1L);
        assertThat(eventRecords.items()).extracting(item -> item.eventType())
                .containsExactly("offline_open");
    }

    @Test
    void eventRecordsExposeAndFilterOneContinuousActorJourney() {
        UUID anonymousActor = UUID.randomUUID();
        UUID cloudActor = UUID.randomUUID();
        insertLink(anonymousActor, cloudActor, "2026-01-01T00:30:00Z");
        insertEvent(
                anonymousActor.toString(),
                UUID.randomUUID(),
                "anonymous_open",
                "2026-01-01T01:00:00Z",
                "2026-01-01T01:00:01Z",
                "{\"identity_scope\":\"anonymous\"}"
        );
        insertEvent(
                cloudActor.toString().toUpperCase(),
                UUID.randomUUID(),
                "cloud_open",
                "2026-01-01T02:00:00Z",
                "2026-01-01T02:00:01Z",
                "{\"identity_scope\":\"cloud_account\"}"
        );
        insertEvent(
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                "unrelated_open",
                "2026-01-01T03:00:00Z",
                "2026-01-01T03:00:01Z",
                "{\"identity_scope\":\"anonymous\"}"
        );

        AdminEventsResponse response = new AdminEventQueryService(
                dataSourceManager,
                JsonMapper.builder().build(),
                actorIdentityResolver
        ).listEvents(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                1,
                20,
                null,
                null,
                cloudActor.toString(),
                null
        );

        assertThat(response.total()).isEqualTo(2L);
        assertThat(response.items()).extracting(item -> item.eventType())
                .containsExactly("cloud_open", "anonymous_open");
        assertThat(response.items()).allSatisfy(item ->
                assertThat(item.resolvedActorId()).isEqualTo(cloudActor.toString())
        );
        assertThat(response.items()).filteredOn(item -> item.eventType().equals("anonymous_open"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.userId()).isEqualTo(anonymousActor.toString());
                    assertThat(item.identityScope()).isEqualTo("anonymous");
                    assertThat(item.actorLinked()).isTrue();
                });
        assertThat(response.items()).filteredOn(item -> item.eventType().equals("cloud_open"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.userId()).isEqualTo(cloudActor.toString().toUpperCase());
                    assertThat(item.identityScope()).isEqualTo("cloud_account");
                    assertThat(item.actorLinked()).isFalse();
                });

        AdminEventsResponse responseFromRawAnonymousActor = new AdminEventQueryService(
                dataSourceManager,
                JsonMapper.builder().build(),
                actorIdentityResolver
        ).listEvents(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                1,
                20,
                null,
                null,
                anonymousActor.toString(),
                null
        );
        assertThat(responseFromRawAnonymousActor.items()).extracting(item -> item.eventType())
                .containsExactly("cloud_open", "anonymous_open");

        AdminEventsResponse responseWithRawActorIntersection = new AdminEventQueryService(
                dataSourceManager,
                JsonMapper.builder().build(),
                actorIdentityResolver
        ).listEvents(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                1,
                20,
                null,
                anonymousActor.toString(),
                cloudActor.toString(),
                null
        );
        assertThat(responseWithRawActorIntersection.items()).extracting(item -> item.eventType())
                .containsExactly("anonymous_open");

        assertThatThrownBy(() -> new AdminEventQueryService(
                dataSourceManager,
                JsonMapper.builder().build(),
                actorIdentityResolver
        ).listEvents(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                1,
                20,
                null,
                null,
                "not-a-uuid",
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("resolvedActorId 格式无效");
    }

    @Test
    void eventJourneyUsesTheSelectedEventAsAnchorAndJoinsIdentityStages() {
        UUID anonymousActor = UUID.randomUUID();
        UUID cloudActor = UUID.randomUUID();
        UUID sharedDevice = UUID.randomUUID();
        insertLink(anonymousActor, cloudActor, "2026-01-01T01:00:00Z");
        insertEvent(
                anonymousActor.toString(), sharedDevice, "anonymous_open",
                "2026-01-01T01:10:00Z", "{\"identity_scope\":\"anonymous\"}"
        );
        String anchorEventId = insertEventReturningId(
                anonymousActor.toString(), sharedDevice, "authoring_started",
                "2026-01-01T02:00:00Z",
                "{\"identity_scope\":\"anonymous\",\"entry_point\":\"compose\","
                        + "\"letterContent\":\"private text\"}"
        );
        insertEvent(
                cloudActor.toString(), sharedDevice, "cloud_auth_succeeded",
                "2026-01-01T02:20:00Z", "{\"identity_scope\":\"cloud_account\"}"
        );
        insertEvent(
                cloudActor.toString(), sharedDevice, "letter_created",
                "2026-01-01T02:40:00Z", "{\"identity_scope\":\"cloud_account\"}"
        );
        insertEvent(
                cloudActor.toString(), sharedDevice, "outside_window",
                "2026-01-01T04:30:00Z", "{\"identity_scope\":\"cloud_account\"}"
        );
        insertEvent(
                UUID.randomUUID().toString(), UUID.randomUUID(), "unrelated",
                "2026-01-01T02:10:00Z", "{\"identity_scope\":\"anonymous\"}"
        );

        AdminEventJourneyResponse response = new AdminEventJourneyService(
                dataSourceManager,
                JsonMapper.builder().build(),
                actorIdentityResolver
        ).getJourney(PROJECT_ID, anchorEventId, 60, 60);

        assertThat(response.anchorEventId()).isEqualTo(anchorEventId);
        assertThat(response.subjectType()).isEqualTo("actor");
        assertThat(response.resolvedActorId()).isEqualTo(cloudActor.toString());
        assertThat(response.total()).isEqualTo(4L);
        assertThat(response.truncated()).isFalse();
        assertThat(response.items()).extracting(AdminJourneyEventRecord::eventType)
                .containsExactly(
                        "anonymous_open",
                        "authoring_started",
                        "cloud_auth_succeeded",
                        "letter_created"
                );
        assertThat(response.items()).allSatisfy(item ->
                assertThat(item.resolvedActorId()).isEqualTo(cloudActor.toString())
        );
    }

    @Test
    void eventJourneyKeepsTheAnchorWhenMoreThanTwoHundredEventsShareItsTimestamp() {
        UUID actor = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        String occurredAt = "2026-01-01T02:00:00Z";
        for (int index = 0; index < 205; index++) {
            insertEvent(actor.toString(), device, "concurrent_" + index, occurredAt, "{}");
        }
        String anchorEventId = insertEventReturningId(
                actor.toString(), device, "selected_anchor", occurredAt, "{}"
        );

        AdminEventJourneyResponse response = new AdminEventJourneyService(
                dataSourceManager,
                JsonMapper.builder().build(),
                actorIdentityResolver
        ).getJourney(PROJECT_ID, anchorEventId, 15, 15);

        assertThat(response.total()).isEqualTo(206L);
        assertThat(response.truncated()).isTrue();
        assertThat(response.items()).hasSize(200);
        assertThat(response.items()).extracting(AdminJourneyEventRecord::eventId)
                .contains(anchorEventId);
    }

    @Test
    void eventJourneyUsesDatabaseInsertionOrderWhenOccurrenceTimesAreEqual() {
        UUID actor = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        String occurredAt = "2026-01-01T02:00:00Z";
        insertEvent(actor.toString(), device, "first_received", occurredAt, "{}");
        String anchorEventId = insertEventReturningId(
                actor.toString(), device, "second_received", occurredAt, "{}"
        );
        insertEvent(actor.toString(), device, "third_received", occurredAt, "{}");

        AdminEventJourneyResponse response = new AdminEventJourneyService(
                dataSourceManager,
                JsonMapper.builder().build(),
                actorIdentityResolver
        ).getJourney(PROJECT_ID, anchorEventId, 15, 15);

        assertThat(response.items()).extracting(AdminJourneyEventRecord::eventType)
                .containsExactly("first_received", "second_received", "third_received");
    }

    @Test
    void eventJourneyNormalizesHistoricalUppercaseUuidActors() {
        UUID anonymousActor = UUID.randomUUID();
        UUID cloudActor = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        insertLink(anonymousActor, cloudActor, "2026-01-01T01:00:00Z");
        insertEvent(
                anonymousActor.toString(), device, "anonymous_open",
                "2026-01-01T01:30:00Z", "{\"identity_scope\":\"anonymous\"}"
        );
        String anchorEventId = insertEventReturningId(
                cloudActor.toString(), device, "cloud_auth_succeeded",
                "2026-01-01T02:00:00Z", "{\"identity_scope\":\"cloud_account\"}"
        );
        jdbcTemplate.update(
                "UPDATE " + quoted(PREFIX + "events") + " SET user_id = UPPER(user_id) WHERE event_id = ?",
                anchorEventId
        );

        AdminEventJourneyResponse response = new AdminEventJourneyService(
                dataSourceManager,
                JsonMapper.builder().build(),
                actorIdentityResolver
        ).getJourney(PROJECT_ID, anchorEventId, 60, 60);

        assertThat(response.resolvedActorId()).isEqualTo(cloudActor.toString());
        assertThat(response.total()).isEqualTo(2L);
        assertThat(response.items()).extracting(AdminJourneyEventRecord::eventType)
                .containsExactly("anonymous_open", "cloud_auth_succeeded");
        assertThat(response.items()).extracting(AdminJourneyEventRecord::eventId)
                .contains(anchorEventId);
    }

    @Test
    void eventJourneyDefersOneOversizedPropertyPayloadAndLoadsItExplicitly() {
        UUID actor = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        String largeValue = "x".repeat(70 * 1024);
        String eventId = insertEventReturningId(
                actor.toString(),
                device,
                "oversized_diagnostic_event",
                "2026-01-01T02:00:00Z",
                "{\"debug_blob\":\"" + largeValue + "\","
                        + "\"identity_scope\":\"" + largeValue + "\"}"
        );
        AdminEventJourneyService service = new AdminEventJourneyService(
                dataSourceManager,
                JsonMapper.builder().build(),
                actorIdentityResolver
        );

        AdminEventJourneyResponse journey = service.getJourney(PROJECT_ID, eventId, 15, 15);

        assertThat(journey.items()).singleElement().satisfies(item -> {
            assertThat(item.properties()).isNull();
            assertThat(item.propertiesDeferred()).isTrue();
            assertThat(item.propertiesLoadable()).isTrue();
            assertThat(item.propertiesBytes()).isGreaterThan(70 * 1024);
        });
        assertThat(service.getEventProperties(PROJECT_ID, eventId).properties()
                .get("debug_blob").asString()).hasSize(70 * 1024);
        assertThat(service.getEventProperties(PROJECT_ID, eventId).properties()
                .get("identity_scope").asString()).hasSize(70 * 1024);
    }

    @Test
    void eventJourneyRejectsExplicitLoadingBeyondTheOnlineSafetyLimit() {
        UUID actor = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        byte[] randomBytes = new byte[1_600_000];
        new Random(42L).nextBytes(randomBytes);
        String largeValue = Base64.getEncoder().encodeToString(randomBytes);
        String eventId = insertEventReturningId(
                actor.toString(),
                device,
                "pathological_diagnostic_event",
                "2026-01-01T02:00:00Z",
                "{\"debug_blob\":\"" + largeValue + "\"}"
        );
        AdminEventJourneyService service = new AdminEventJourneyService(
                dataSourceManager,
                JsonMapper.builder().build(),
                actorIdentityResolver
        );

        AdminEventJourneyResponse journey = service.getJourney(PROJECT_ID, eventId, 15, 15);

        assertThat(journey.items()).singleElement().satisfies(item -> {
            assertThat(item.properties()).isNull();
            assertThat(item.propertiesDeferred()).isTrue();
            assertThat(item.propertiesLoadable()).isFalse();
            assertThat(item.propertiesBytes()).isGreaterThan(2 * 1024 * 1024);
        });
        assertThatThrownBy(() -> service.getEventProperties(PROJECT_ID, eventId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("事件属性超过在线查看上限");
    }

    @Test
    void appVersionDistributionUsesEachActiveDevicesLatestOccurredEvent() {
        UUID actor = UUID.randomUUID();
        UUID upgradedDevice = UUID.randomUUID();
        UUID currentDevice = UUID.randomUUID();
        UUID unknownDevice = UUID.randomUUID();

        insertEvent(actor.toString(), upgradedDevice, "open", "2026-01-01T01:00:00Z",
                "{\"app_version\":\"1.1.5\",\"build_number\":\"115\"}");
        insertEvent(actor.toString(), upgradedDevice, "open", "2026-01-01T02:00:00Z",
                "{\"app_version\":\"1.1.6\",\"build_number\":\"116\"}");
        insertEvent(actor.toString(), currentDevice, "open", "2026-01-01T03:00:00Z",
                "{\"app_version\":\"1.1.6\",\"build_number\":\"117\"}");
        insertEvent(actor.toString(), unknownDevice, "legacy_open", "2026-01-01T04:00:00Z", null);
        insertEvent(actor.toString(), UUID.randomUUID(), "outside", "2026-01-03T01:00:00Z",
                "{\"app_version\":\"9.9.9\",\"build_number\":\"999\"}");

        AdminAppVersionDistributionResponse response = metricsService.getAppVersionDistribution(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"
        );

        assertThat(response.measurement()).isEqualTo("latest_occurred_event_per_device");
        assertThat(response.activeDevices()).isEqualTo(3L);
        assertThat(response.versionKnownDevices()).isEqualTo(2L);
        assertThat(response.coverageRate()).isEqualTo(0.6667d);
        assertThat(response.items()).extracting(item -> item.appVersion() + ":" + item.buildNumber())
                .containsExactly("1.1.6:117", "1.1.6:116", "unknown:unknown");
        assertThat(response.items()).extracting(item -> item.activeDevices())
                .containsExactly(1L, 1L, 1L);
        assertThat(response.items()).extracting(item -> item.share())
                .containsExactly(0.3333d, 0.3333d, 0.3333d);
    }

    @Test
    void aliasesUppercaseCanonicalUuidFormsInEveryProductReport() {
        UUID anonymousActor = UUID.randomUUID();
        UUID cloudActor = UUID.randomUUID();
        insertLink(anonymousActor, cloudActor, "2026-01-01T00:30:00Z");

        insertEvent(anonymousActor, "landing", "2026-01-01T01:00:00Z", null);
        insertEvent(cloudActor, "purchase", "2026-01-01T01:10:00Z", null);
        jdbcTemplate.update(
                "UPDATE " + quoted(PREFIX + "events") + " SET user_id = UPPER(user_id) WHERE event_type = ?",
                "landing"
        );

        AdminFunnelResponse funnel = service.getFunnel(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "landing,purchase", null
        );
        AdminMetricsOverviewResponse overview = metricsService.getOverview(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"
        );
        AdminMetricsTrendResponse trends = metricsService.getTrends(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", "day"
        );

        assertThat(funnel.groups().getFirst().steps()).extracting(step -> step.users()).containsExactly(1L, 1L);
        assertThat(overview.usersActive()).isEqualTo(1L);
        assertThat(trends.points()).extracting(point -> point.activeUsers()).containsExactly(1L);
    }

    @Test
    void resolverKeepsHistoricalActorsAndNormalizesUuidAliasesOnce() {
        UUID anonymousActor = UUID.randomUUID();
        UUID cloudActor = UUID.randomUUID();
        insertLink(anonymousActor, cloudActor, "2026-01-01T00:30:00Z");

        Map<String, String> resolved = actorIdentityResolver.resolveCanonicalActors(
                jdbcTemplate,
                quoted(PREFIX + "actor_identity_links"),
                PROJECT_ID,
                List.of("legacy-user", anonymousActor.toString().toUpperCase(), cloudActor.toString())
        );

        assertThat(resolved).containsEntry("legacy-user", "legacy-user");
        assertThat(resolved).containsEntry(anonymousActor.toString().toUpperCase(), cloudActor.toString());
        assertThat(resolved).containsEntry(cloudActor.toString(), cloudActor.toString());
    }

    @Test
    void overviewCountsMixedHistoricalAndAliasedActors() {
        UUID anonymousActor = UUID.randomUUID();
        UUID cloudActor = UUID.randomUUID();
        insertLink(anonymousActor, cloudActor, "2026-01-01T00:30:00Z");

        insertEvent(anonymousActor, "open", "2026-01-01T01:00:00Z", null);
        insertEvent(cloudActor, "open", "2026-01-01T02:00:00Z", null);
        insertEvent("legacy-user", "open", "2026-01-01T03:00:00Z", null);
        insertEvent("legacy-user", "open", "2026-01-01T04:00:00Z", null);

        AdminMetricsOverviewResponse overview = metricsService.getOverview(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"
        );

        assertThat(overview.usersActive()).isEqualTo(2L);
        assertThat(overview.eventsTotal()).isEqualTo(4L);
    }

    @Test
    void overviewQueriesDistinctActorsBeforeResolvingAliases() {
        UUID anonymousActor = UUID.randomUUID();
        UUID cloudActor = UUID.randomUUID();
        insertLink(anonymousActor, cloudActor, "2026-01-01T00:30:00Z");

        insertEvent(anonymousActor, "open", "2026-01-01T01:00:00Z", null);
        insertEvent(anonymousActor, "open", "2026-01-01T02:00:00Z", null);
        insertEvent(cloudActor, "open", "2026-01-01T03:00:00Z", null);
        insertEvent("legacy-user", "open", "2026-01-01T04:00:00Z", null);
        insertEvent("legacy-user", "open", "2026-01-01T05:00:00Z", null);

        ActorIdentityResolver resolver = spy(new ActorIdentityResolver());
        AdminMetricsService serviceWithResolverSpy = new AdminMetricsService(
                dataSourceManager,
                mock(SemanticDictionaryService.class),
                resolver,
                propertyFilterService,
                new AnalyticsQueryProperties(),
                new ProjectTransactionExecutor()
        );
        AdminMetricsOverviewResponse overview = serviceWithResolverSpy.getOverview(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"
        );

        assertThat(overview.usersActive()).isEqualTo(2L);
        assertThat(overview.eventsTotal()).isEqualTo(5L);
        verify(resolver).resolveCanonicalActors(
                any(JdbcTemplate.class),
                eq(quoted(PREFIX + "actor_identity_links")),
                eq(PROJECT_ID),
                argThat(actorIds -> actorIds.size() == 3
                        && actorIds.containsAll(List.of(anonymousActor.toString(), cloudActor.toString(), "legacy-user")))
        );
    }

    @Test
    void retentionUsesCohortRelativeDayWindowsAndDeduplicatesActors() {
        UUID firstUser = UUID.randomUUID();
        UUID secondUser = UUID.randomUUID();

        insertEvent(firstUser, "signup", "2026-01-01T10:00:00Z", null);
        insertEvent(firstUser, "open", "2026-01-01T12:00:00Z", null);
        // D1 is the next UTC calendar day, even when less than 24 hours after signup.
        insertEvent(firstUser, "open", "2026-01-02T09:00:00Z", null);
        insertEvent(firstUser, "open", "2026-01-08T11:00:00Z", null);
        insertEvent(firstUser, "open", "2026-01-08T12:00:00Z", null);

        insertEvent(secondUser, "signup", "2026-01-01T14:00:00Z", null);
        insertEvent(secondUser, "open", "2026-01-01T15:00:00Z", null);

        AdminRetentionResponse response = service.getRetention(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "signup",
                "open",
                "7,0,1,7"
        );

        assertThat(response.cohortUsers()).isEqualTo(2);
        assertThat(response.observationEnd()).isEqualTo("2026-01-10T00:00:00Z");
        assertThat(response.observationComplete()).isTrue();
        assertThat(response.buckets()).extracting(bucket -> bucket.day()).containsExactly(0, 1, 7);
        assertThat(response.buckets()).extracting(bucket -> bucket.eligibleUsers()).containsExactly(2L, 2L, 2L);
        assertThat(response.buckets()).extracting(bucket -> bucket.retainedUsers()).containsExactly(2L, 1L, 1L);
        assertThat(response.buckets()).extracting(bucket -> bucket.retentionRate()).containsExactly(1d, 0.5d, 0.5d);
    }

    @Test
    void retentionRejectsWhenCohortAndObservationWindowExceedTheInteractiveRangeBudget() {
        assertThatThrownBy(() -> service.getRetention(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-06-01T00:00:00Z",
                "signup",
                "open",
                "90"
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("ANALYTICS_QUERY_RANGE_EXCEEDED")
        );
    }

    @Test
    void retentionExcludesCohortMembersWhoseRequestedDayHasNotMatured() {
        Instant now = Instant.now();
        Instant cohortAt = now.minusSeconds(24 * 60 * 60L);
        insertEvent(UUID.randomUUID(), "signup", cohortAt.toString(), null);

        AdminRetentionResponse response = service.getRetention(
                PROJECT_ID,
                now.minusSeconds(2 * 24 * 60 * 60L).toString(),
                now.toString(),
                "signup",
                "open",
                "30"
        );

        assertThat(response.cohortUsers()).isEqualTo(1);
        assertThat(response.observationComplete()).isFalse();
        assertThat(Instant.parse(response.observationEnd())).isBefore(Instant.parse(response.requestedObservationEnd()));
        assertThat(response.buckets()).singleElement().satisfies(bucket -> {
            assertThat(bucket.eligibleUsers()).isZero();
            assertThat(bucket.retainedUsers()).isZero();
            assertThat(bucket.retentionRate()).isZero();
        });
    }

    @Test
    void retentionDoesNotCountAnEventThatOccurredBeforeTheCohortOnTheSameUtcDay() {
        UUID actor = UUID.randomUUID();
        insertEvent(actor, "open", "2026-01-01T09:00:00Z", null);
        insertEvent(actor, "signup", "2026-01-01T20:00:00Z", null);

        AdminRetentionResponse response = service.getRetention(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "signup",
                "open",
                "0"
        );

        assertThat(response.cohortUsers()).isEqualTo(1);
        assertThat(response.buckets()).singleElement().satisfies(bucket -> {
            assertThat(bucket.eligibleUsers()).isEqualTo(1);
            assertThat(bucket.retainedUsers()).isZero();
            assertThat(bucket.retentionRate()).isZero();
        });
    }

    @Test
    void retentionUsesDatabaseIdToOrderEventsWithTheSameTimestamp() {
        String timestamp = "2026-01-01T09:00:00Z";
        UUID reversedActor = UUID.randomUUID();
        insertEvent(reversedActor, "open", timestamp, null);
        insertEvent(reversedActor, "signup", timestamp, null);

        UUID orderedActor = UUID.randomUUID();
        insertEvent(orderedActor, "signup", timestamp, null);
        insertEvent(orderedActor, "open", timestamp, null);

        AdminRetentionResponse response = service.getRetention(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "signup",
                "open",
                "0"
        );

        assertThat(response.cohortUsers()).isEqualTo(2);
        assertThat(response.buckets()).singleElement().satisfies(bucket -> {
            assertThat(bucket.eligibleUsers()).isEqualTo(2);
            assertThat(bucket.retainedUsers()).isEqualTo(1);
            assertThat(bucket.retentionRate()).isEqualTo(0.5d);
        });
    }

    @Test
    void dataQualityReturnsZeroCoverageRowsForGovernedPropertiesInAnEmptyWindow() {
        AnalyticsPropertyDefinitionService definitions = mock(AnalyticsPropertyDefinitionService.class);
        AnalyticsPropertyDefinitionResponse property = new AnalyticsPropertyDefinitionResponse(
                PROJECT_ID,
                "release_channel",
                Map.of("en", "Release channel"),
                AnalyticsPropertyDataType.STRING,
                null,
                null,
                true,
                false,
                false,
                false,
                true,
                Instant.EPOCH,
                Instant.EPOCH
        );
        when(definitions.list(PROJECT_ID)).thenReturn(
                new AnalyticsPropertyDefinitionsResponse(PROJECT_ID, List.of(property))
        );
        AnalyticsDataQualityService qualityService = new AnalyticsDataQualityService(
                dataSourceManager,
                definitions,
                mock(AnalysisConfigurationService.class),
                queryProperties,
                new ProjectTransactionExecutor(),
                JsonMapper.builder().build()
        );

        AnalyticsDataQualityResponse response = qualityService.inspect(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"
        );

        assertThat(response.totalEvents()).isZero();
        assertThat(response.trustedSchemaPolicyConfigured()).isFalse();
        assertThat(response.schemaVersionPropertyKey()).isNull();
        assertThat(response.schemaVersions()).isEmpty();
        assertThat(response.propertyCoverageTotal()).isEqualTo(1);
        assertThat(response.propertyCoverageTruncated()).isFalse();
        assertThat(response.propertyCoverage()).singleElement().satisfies(coverage -> {
            assertThat(coverage.propertyKey()).isEqualTo("release_channel");
            assertThat(coverage.presentEvents()).isZero();
            assertThat(coverage.typeMismatchEvents()).isZero();
            assertThat(coverage.disallowedValueEvents()).isZero();
        });
    }

    @Test
    void dataQualityRejectsSchemaVersionsOutsideTheProjectAllowlistAsCleanData() {
        AnalyticsPropertyDefinitionService definitions = mock(AnalyticsPropertyDefinitionService.class);
        AnalyticsPropertyDefinitionResponse property = new AnalyticsPropertyDefinitionResponse(
                PROJECT_ID,
                "event_schema_version",
                Map.of("en", "Event schema version"),
                AnalyticsPropertyDataType.STRING,
                null,
                List.of("3"),
                true,
                false,
                false,
                false,
                true,
                Instant.EPOCH,
                Instant.EPOCH
        );
        when(definitions.list(PROJECT_ID)).thenReturn(
                new AnalyticsPropertyDefinitionsResponse(PROJECT_ID, List.of(property))
        );
        AnalysisConfigurationService analysisConfiguration = mock(AnalysisConfigurationService.class);
        when(analysisConfiguration.getTrustedSchemaPolicy(PROJECT_ID)).thenReturn(
                new TrustedSchemaPolicyResponse(PROJECT_ID, "event_schema_version", List.of("3"))
        );
        AnalyticsDataQualityService qualityService = new AnalyticsDataQualityService(
                dataSourceManager,
                definitions,
                analysisConfiguration,
                queryProperties,
                new ProjectTransactionExecutor(),
                JsonMapper.builder().build()
        );
        insertEvent(UUID.randomUUID(), "open", "2026-01-01T09:00:00Z",
                "{\"event_schema_version\":\"2\"}");

        AnalyticsDataQualityResponse response = qualityService.inspect(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"
        );

        assertThat(response.trustedSchemaPolicyConfigured()).isTrue();
        assertThat(response.schemaVersions()).containsEntry("2", 1L);
        assertThat(response.propertyCoverage()).singleElement().satisfies(coverage -> {
            assertThat(coverage.propertyKey()).isEqualTo("event_schema_version");
            assertThat(coverage.disallowedValueEvents()).isEqualTo(1);
        });
        assertThat(response.issues()).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("property_value_outside_allowlist");
            assertThat(issue.severity()).isEqualTo("error");
            assertThat(issue.count()).isEqualTo(1);
        });
    }

    @Test
    void dataQualityUsesTheProjectTrustedPolicyPropertyInsteadOfAFixedSchemaKey() {
        AnalyticsPropertyDefinitionService definitions = mock(AnalyticsPropertyDefinitionService.class);
        AnalyticsPropertyDefinitionResponse property = new AnalyticsPropertyDefinitionResponse(
                PROJECT_ID,
                "contract_revision",
                Map.of("en", "Contract revision"),
                AnalyticsPropertyDataType.STRING,
                null,
                List.of("stable"),
                true,
                false,
                false,
                false,
                true,
                Instant.EPOCH,
                Instant.EPOCH
        );
        when(definitions.list(PROJECT_ID)).thenReturn(
                new AnalyticsPropertyDefinitionsResponse(PROJECT_ID, List.of(property))
        );
        AnalysisConfigurationService analysisConfiguration = mock(AnalysisConfigurationService.class);
        when(analysisConfiguration.getTrustedSchemaPolicy(PROJECT_ID)).thenReturn(
                new TrustedSchemaPolicyResponse(PROJECT_ID, "contract_revision", List.of("stable"))
        );
        AnalyticsDataQualityService qualityService = new AnalyticsDataQualityService(
                dataSourceManager,
                definitions,
                analysisConfiguration,
                queryProperties,
                new ProjectTransactionExecutor(),
                JsonMapper.builder().build()
        );
        insertEvent(UUID.randomUUID(), "open", "2026-01-01T09:00:00Z",
                "{\"contract_revision\":\"stable\",\"event_schema_version\":\"legacy\"}");

        AnalyticsDataQualityResponse response = qualityService.inspect(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"
        );

        assertThat(response.schemaVersionPropertyKey()).isEqualTo("contract_revision");
        assertThat(response.schemaVersions()).containsExactlyEntriesOf(Map.of("stable", 1L));
    }

    @Test
    void dataQualityTruncatesHighCardinalitySchemaDistributionWithoutLosingIssueTotals() {
        AnalyticsPropertyDefinitionService definitions = mock(AnalyticsPropertyDefinitionService.class);
        AnalyticsPropertyDefinitionResponse property = new AnalyticsPropertyDefinitionResponse(
                PROJECT_ID,
                "contract_revision",
                Map.of("en", "Contract revision"),
                AnalyticsPropertyDataType.STRING,
                null,
                null,
                true,
                false,
                false,
                false,
                true,
                Instant.EPOCH,
                Instant.EPOCH
        );
        when(definitions.list(PROJECT_ID)).thenReturn(
                new AnalyticsPropertyDefinitionsResponse(PROJECT_ID, List.of(property))
        );
        AnalysisConfigurationService analysisConfiguration = mock(AnalysisConfigurationService.class);
        when(analysisConfiguration.getTrustedSchemaPolicy(PROJECT_ID)).thenReturn(
                new TrustedSchemaPolicyResponse(PROJECT_ID, "contract_revision", List.of("stable"))
        );
        AnalyticsDataQualityService qualityService = new AnalyticsDataQualityService(
                dataSourceManager,
                definitions,
                analysisConfiguration,
                queryProperties,
                new ProjectTransactionExecutor(),
                JsonMapper.builder().build()
        );
        for (int index = 0; index < 201; index++) {
            insertEvent(
                    UUID.randomUUID(),
                    "open",
                    "2026-01-01T09:00:00Z",
                    "{\"contract_revision\":\"bad-" + index + "\"}"
            );
        }

        AnalyticsDataQualityResponse response = qualityService.inspect(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"
        );

        assertThat(response.schemaVersions()).hasSize(200);
        assertThat(new ArrayList<>(response.schemaVersions().keySet())).isSorted();
        assertThat(response.schemaVersionDistributionTruncated()).isTrue();
        assertThat(response.issues()).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("untrusted_schema_value");
            assertThat(issue.count()).isEqualTo(201);
        });
        assertThat(response.issues()).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("schema_version_distribution_truncated");
            assertThat(issue.count()).isEqualTo(1);
        });
    }

    @Test
    void dataQualityRejectsAllowedButUntrustedContractValuesFromTheCleanBaseline() {
        AnalyticsPropertyDefinitionService definitions = mock(AnalyticsPropertyDefinitionService.class);
        AnalyticsPropertyDefinitionResponse property = new AnalyticsPropertyDefinitionResponse(
                PROJECT_ID,
                "contract_revision",
                Map.of("en", "Contract revision"),
                AnalyticsPropertyDataType.STRING,
                null,
                List.of("stable", "next"),
                true,
                false,
                false,
                false,
                true,
                Instant.EPOCH,
                Instant.EPOCH
        );
        when(definitions.list(PROJECT_ID)).thenReturn(
                new AnalyticsPropertyDefinitionsResponse(PROJECT_ID, List.of(property))
        );
        AnalysisConfigurationService analysisConfiguration = mock(AnalysisConfigurationService.class);
        when(analysisConfiguration.getTrustedSchemaPolicy(PROJECT_ID)).thenReturn(
                new TrustedSchemaPolicyResponse(PROJECT_ID, "contract_revision", List.of("stable"))
        );
        AnalyticsDataQualityService qualityService = new AnalyticsDataQualityService(
                dataSourceManager,
                definitions,
                analysisConfiguration,
                queryProperties,
                new ProjectTransactionExecutor(),
                JsonMapper.builder().build()
        );
        insertEvent(UUID.randomUUID(), "open", "2026-01-01T09:00:00Z",
                "{\"contract_revision\":\"next\"}");

        AnalyticsDataQualityResponse response = qualityService.inspect(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"
        );

        assertThat(response.issues()).anySatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("untrusted_schema_value");
            assertThat(issue.count()).isEqualTo(1);
        });
    }

    @Test
    void dataQualityUsesNumericSemanticsForAllowedValues() {
        AnalyticsPropertyDefinitionService definitions = mock(AnalyticsPropertyDefinitionService.class);
        AnalyticsPropertyDefinitionResponse property = new AnalyticsPropertyDefinitionResponse(
                PROJECT_ID,
                "ratio",
                Map.of("en", "Ratio"),
                AnalyticsPropertyDataType.NUMBER,
                null,
                List.of("1"),
                false,
                false,
                false,
                false,
                true,
                Instant.EPOCH,
                Instant.EPOCH
        );
        when(definitions.list(PROJECT_ID)).thenReturn(
                new AnalyticsPropertyDefinitionsResponse(PROJECT_ID, List.of(property))
        );
        AnalyticsDataQualityService qualityService = new AnalyticsDataQualityService(
                dataSourceManager,
                definitions,
                mock(AnalysisConfigurationService.class),
                queryProperties,
                new ProjectTransactionExecutor(),
                JsonMapper.builder().build()
        );
        insertEvent(UUID.randomUUID(), "open", "2026-01-01T09:00:00Z", "{\"ratio\":1.0}");

        AnalyticsDataQualityResponse response = qualityService.inspect(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"
        );

        assertThat(response.trustedSchemaPolicyConfigured()).isFalse();
        assertThat(response.propertyCoverage()).singleElement().satisfies(coverage -> {
            assertThat(coverage.typeMismatchEvents()).isZero();
            assertThat(coverage.disallowedValueEvents()).isZero();
        });
        assertThat(response.issues()).noneMatch(issue ->
                issue.code().equals("property_value_outside_allowlist")
        );
        assertThat(response.issues()).noneMatch(issue ->
                issue.code().equals("missing_distribution_environment")
                        || issue.code().equals("missing_backend_environment")
        );
    }

    @Test
    void stringFiltersAndQualityUseTheSameBoundaryWhitespaceNormalization() {
        AnalyticsPropertyDefinitionService definitions = mock(AnalyticsPropertyDefinitionService.class);
        AnalyticsPropertyDefinitionResponse property = new AnalyticsPropertyDefinitionResponse(
                PROJECT_ID,
                "release_channel",
                Map.of("en", "Release channel"),
                AnalyticsPropertyDataType.STRING,
                null,
                List.of("production"),
                true,
                false,
                false,
                false,
                true,
                Instant.EPOCH,
                Instant.EPOCH
        );
        when(definitions.list(PROJECT_ID)).thenReturn(
                new AnalyticsPropertyDefinitionsResponse(PROJECT_ID, List.of(property))
        );
        when(definitions.requireCapabilities(PROJECT_ID, List.of("release_channel")))
                .thenReturn(Map.of("release_channel", property));

        JsonMapper objectMapper = JsonMapper.builder().build();
        AnalyticsPropertyFilterService filters = new AnalyticsPropertyFilterService(objectMapper, definitions);
        SemanticDictionaryService semantics = mock(SemanticDictionaryService.class);
        ActorIdentityResolver resolver = new ActorIdentityResolver();
        AdminMetricsService filteredMetrics = new AdminMetricsService(
                dataSourceManager,
                semantics,
                resolver,
                filters,
                queryProperties,
                new ProjectTransactionExecutor()
        );
        AnalyticsDataQualityService qualityService = new AnalyticsDataQualityService(
                dataSourceManager,
                definitions,
                mock(AnalysisConfigurationService.class),
                queryProperties,
                new ProjectTransactionExecutor(),
                objectMapper
        );
        insertEvent(UUID.randomUUID(), "open", "2026-01-01T09:00:00Z",
                "{\"release_channel\":\"\\t production \\n\"}");

        AdminMetricsOverviewResponse overview = filteredMetrics.getOverview(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "[{\"propertyKey\":\"release_channel\",\"operator\":\"EQ\",\"values\":[\"production\"]}]"
        );
        AnalyticsDataQualityResponse quality = qualityService.inspect(
                PROJECT_ID, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"
        );

        assertThat(overview.eventsTotal()).isEqualTo(1);
        assertThat(quality.propertyCoverage()).singleElement().satisfies(coverage -> {
            assertThat(coverage.presentEvents()).isEqualTo(1);
            assertThat(coverage.typeMismatchEvents()).isZero();
            assertThat(coverage.disallowedValueEvents()).isZero();
        });
    }

    @Test
    void funnelRequiresAtLeastTwoDistinctSteps() {
        assertThatThrownBy(() -> service.getFunnel(
                PROJECT_ID,
                "2026-01-01T00:00:00Z",
                "2026-01-02T00:00:00Z",
                "landing,landing",
                null
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少需要 2 个不同事件");
    }

    private void insertEvent(UUID userId, String eventType, String createdAt, String properties) {
        insertEvent(userId.toString(), eventType, createdAt, properties);
    }

    private void insertEvent(String userId, String eventType, String createdAt, String properties) {
        insertEvent(userId, UUID.randomUUID(), eventType, createdAt, properties);
    }

    private void insertEvent(
            String userId,
            UUID deviceId,
            String eventType,
            String createdAt,
            String properties
    ) {
        insertEventReturningId(userId, deviceId, eventType, createdAt, createdAt, properties);
    }

    private void insertEvent(
            String userId,
            UUID deviceId,
            String eventType,
            String occurredAt,
            String receivedAt,
            String properties
    ) {
        insertEventReturningId(userId, deviceId, eventType, occurredAt, receivedAt, properties);
    }

    private String insertEventReturningId(
            String userId,
            UUID deviceId,
            String eventType,
            String occurredAt,
            String properties
    ) {
        return insertEventReturningId(userId, deviceId, eventType, occurredAt, occurredAt, properties);
    }

    private String insertEventReturningId(
            String userId,
            UUID deviceId,
            String eventType,
            String occurredAt,
            String receivedAt,
            String properties
    ) {
        Instant occurrence = Instant.parse(occurredAt);
        Instant received = Instant.parse(receivedAt);
        String eventId = "evt_" + UUID.randomUUID();
        int propertiesSizeBytes = properties == null
                ? 0
                : properties.getBytes(StandardCharsets.UTF_8).length;
        String identityScope = null;
        if (properties != null) {
            try {
                var identityScopeNode = JsonMapper.builder().build().readTree(properties).get("identity_scope");
                if (identityScopeNode != null
                        && identityScopeNode.isString()
                        && identityScopeNode.asString().length() <= 64) {
                    identityScope = identityScopeNode.asString();
                }
            } catch (Exception ignored) {
                // 测试辅助方法允许无属性或非对象 JSON；生产写入由 EventService 统一校验。
            }
        }
        jdbcTemplate.update(
                "INSERT INTO " + quoted(PREFIX + "events") + " "
                        + "(event_id, device_id, user_id, event_type, event_timestamp, properties, "
                        + "properties_size_bytes, identity_scope, project_id, created_at) "
                        + "VALUES (?, ?::uuid, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                eventId,
                deviceId.toString(),
                userId,
                eventType,
                occurrence.toEpochMilli(),
                properties,
                propertiesSizeBytes,
                identityScope,
                PROJECT_ID,
                Timestamp.from(received)
        );
        return eventId;
    }

    private void insertLink(UUID sourceActorId, UUID canonicalActorId, String linkedAt) {
        jdbcTemplate.update(
                String.format(
                        "INSERT INTO %s (binding_id, project_id, source_actor_id, canonical_actor_id, linked_at) "
                                + "VALUES (?::uuid, ?, ?::uuid, ?::uuid, ?)",
                        quoted(PREFIX + "actor_identity_links")
                ),
                UUID.randomUUID().toString(),
                PROJECT_ID,
                sourceActorId.toString(),
                canonicalActorId.toString(),
                Timestamp.from(Instant.parse(linkedAt))
        );
    }

    private void insertSession(String startedAt) {
        jdbcTemplate.update(
                "INSERT INTO " + quoted(PREFIX + "sessions") + " "
                        + "(session_id, device_id, user_id, session_start_time, project_id) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?, ?)",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                Timestamp.from(Instant.parse(startedAt)),
                PROJECT_ID
        );
    }

    private static String quoted(String identifier) {
        return '"' + identifier + '"';
    }

    private static void assertBudgetExceeded(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("ANALYTICS_QUERY_BUDGET_EXCEEDED")
        );
    }
}
