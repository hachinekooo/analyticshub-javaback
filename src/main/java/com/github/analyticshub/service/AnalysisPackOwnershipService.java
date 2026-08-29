package com.github.analyticshub.service;

import com.github.analyticshub.dto.AnalyticsPropertyDataType;
import com.github.analyticshub.dto.AnalyticsPropertyDefinitionResponse;
import com.github.analyticshub.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 解析启用中的 Analysis Pack 对属性和指标定义的所有权。
 *
 * <p>Pack 清单是受管定义的唯一事实源；受管定义只能通过整包导入修改，
 * 避免单项编辑后真实配置与 Pack 版本、校验和及审计记录发生漂移。</p>
 */
@Service
public class AnalysisPackOwnershipService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AnalysisPackOwnershipService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void requirePropertyManuallyEditable(String projectId, String propertyKey) {
        requireManuallyEditable(projectId, propertyKey, "properties", "propertyKey", "属性");
    }

    public void requireMetricManuallyEditable(String projectId, String metricKey) {
        requireManuallyEditable(projectId, metricKey, "metrics", "metricKey", "指标");
    }

    /**
     * 外部属性可以被 Pack 的可信策略引用，但手动编辑不能让已启用策略失效。
     * 该检查与属性写入处于同一事务，失败时由事务回滚刚写入的定义。
     */
    public void requireTrustedSchemaPolicyCompatible(
            String projectId,
            AnalyticsPropertyDefinitionResponse definition
    ) {
        jdbcTemplate.query("""
                SELECT pack_key, manifest::text
                  FROM analytics_analysis_packs
                 WHERE project_id = ? AND is_active = TRUE
                """, resultSet -> {
            JsonNode policy = objectMapper.readTree(resultSet.getString("manifest"))
                    .path("trustedSchemaPolicy");
            if (!policy.isObject()
                    || !definition.propertyKey().equals(policy.path("propertyKey").asString())) {
                return;
            }
            boolean compatible = definition.active()
                    && !definition.sensitive()
                    && definition.filterable()
                    && definition.dataType() == AnalyticsPropertyDataType.STRING
                    && definition.allowedValues() != null;
            if (compatible) {
                for (JsonNode value : policy.path("trustedValues")) {
                    String normalized = AnalyticsPropertyValueNormalizer.normalize(
                            value.asString(), AnalyticsPropertyDataType.STRING
                    );
                    if (!definition.allowedValues().contains(normalized)) {
                        compatible = false;
                        break;
                    }
                }
            }
            if (!compatible) {
                throw BusinessException.analysisPackTrustedSchemaConflict(
                        definition.propertyKey(), resultSet.getString("pack_key")
                );
            }
        }, projectId);
    }

    /**
     * Pack 导入和单项定义写入共同持有项目级事务锁，防止所有权检查后发生交错写入。
     * 锁不产生持久状态，并在当前事务结束时由 PostgreSQL 自动释放。
     */
    public void acquireProjectDefinitionWriteLock(String projectId) {
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                resultSet -> { },
                "analytics-definition:" + projectId
        );
    }

    private void requireManuallyEditable(
            String projectId,
            String definitionKey,
            String collectionField,
            String keyField,
            String definitionName
    ) {
        jdbcTemplate.query("""
                SELECT pack_key, manifest::text
                  FROM analytics_analysis_packs
                 WHERE project_id = ? AND is_active = TRUE
                """, resultSet -> {
            JsonNode manifest = objectMapper.readTree(resultSet.getString("manifest"));
            for (JsonNode item : manifest.path(collectionField)) {
                if (definitionKey.equals(item.path(keyField).asString())) {
                    throw new BusinessException(
                            "ANALYSIS_PACK_DEFINITION_MANAGED",
                            definitionName + " " + definitionKey + " 由 Analysis Pack "
                                    + resultSet.getString("pack_key") + " 管理，请通过 Pack 升级修改"
                    );
                }
            }
        }, projectId);
    }
}
