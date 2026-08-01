---
title: Dashboard 与项目定制
type: architecture-guide
status: current
audience: maintainer, contributor, integrator
scope: 多项目 Dashboard、语义字典、安全扩展边界和私有定制接入
agent_notes: 设计或实现项目定制页面前先读取；禁止把私有业务语义写入开源仓库
---

# Dashboard 与项目定制

AnalyticsHub 定位为公司自行部署的 internal operations center（内部运营中心），不是向公众托管数据的 SaaS。它可以提供较自由的可信扩展能力，但 Admin Token、项目数据库凭据和私有业务语义仍必须有明确边界。

## 三层能力

### 1. 通用多项目底座

底座负责项目连接、数据库迁移、认证、采集、基础指标、Counter、语义字典、工单和内置 Dashboard widgets。所有内容都应保持通用、可开源，不包含某个下游项目的名称、事件 key、域名或业务规则。

### 2. 声明式 Dashboard

运营人员可以拖拽内置组件并保存项目专属布局。system database 只保存 declarative JSON（声明式 JSON）：

- widget type 必须在后端 allow-list 中；
- layout 使用 12 列网格；
- config 按 widget 类型做 typed validation（类型化校验）；
- schemaVersion 1 中每种 core widget type 最多一个实例，避免多个实例错误共享运行时数据；未来需要多实例时应升级 schema 并使用 widget-id scoped data state；
- 不允许 HTML、JavaScript、SQL、任意 URL、`eval` 或 dynamic import；
- 使用 revision 做并发更新保护。

这层适合绝大多数日常调整，并且可以安全地由非开发人员使用。

### 3. 可信 build-time extension

当某个项目需要独有图表、交互或视觉风格时，在该公司的私有下游工程中开发 Vue component/page，并在 build time（构建期）注册到受信任的组件 registry。开源底座只提供 extension contract（扩展契约），不包含下游源码和业务配置。

构建期扩展拥有和管理后台相同的权限，因此代码必须由部署方审查。不要从数据库下载一段 JavaScript 后直接在同源管理后台执行。未来若支持第三方网页，应使用不同 origin、严格 CSP 和 sandboxed iframe，并设计最小化消息协议。

1.0.1 的正式 registry contract 覆盖 Dashboard widget。完全独立的定制页面仍通过下游源码中的静态 Vue route 接入；底座不会把页面源码或脚本保存到数据库，也不会在运行时下载执行。

## 接入可信定制 Widget

一次完整接入由 backend bean（后端注册）和 frontend registry entry（前端注册）组成，两端必须使用同一个稳定 `custom.*` type 和同一套 config schema。只注册一端是不完整部署：后端未注册会拒绝保存，前端未注册会显示 unsupported widget（不支持的组件），不会执行未知代码。

### 后端 allow-list 与校验

在下游 Java 工程中把 `DashboardWidgetExtension` 实现注册成 Spring bean：

```java
@Component
final class ExampleScoreWidgetExtension implements DashboardWidgetExtension {
    @Override
    public String type() {
        return "custom.example.score";
    }

    @Override
    public Set<String> allowedConfigFields() {
        return Set.of("threshold");
    }

    @Override
    public boolean configRequired() {
        return true;
    }

    @Override
    public void validateConfig(JsonNode config) {
        JsonNode threshold = config.get("threshold");
        if (threshold == null || !threshold.isInt()
                || threshold.intValue() < 0 || threshold.intValue() > 100) {
            throw new IllegalArgumentException("threshold 必须是 0 到 100 的整数");
        }
    }
}
```

- type 必须匹配 `custom.*` 格式，最长 100 字符，且不能和其他扩展重复；非法注册会让 Spring context fail fast（启动即失败）。
- `allowedConfigFields()` 只声明扩展字段；通用 `title` 由底座提供，不要重复声明。
- 启动时底座会 snapshot（快照）type、allow-list 和 required flag，避免运行中修改注册信息绕过校验。
- `validateConfig` 必须校验每个字段的值、范围、数组大小和组合关系；异常消息应可安全展示给管理员，不要包含 secret 或业务数据。

### 前端 component 与 registry

下游 Vue component 使用底座导出的 typed contract（类型契约）：

```ts
import type {
  DashboardWidgetExtensionEmits,
  DashboardWidgetExtensionProps,
} from '@/extensions/dashboard'

type ScoreConfig = Readonly<{ threshold: number }>

const props = defineProps<DashboardWidgetExtensionProps<ScoreConfig>>()
const emit = defineEmits<DashboardWidgetExtensionEmits<ScoreConfig>>()
```

Host 会传入：

- `projectId`、`widgetId`：当前项目与稳定组件实例 ID；
- `config`：经过 JSON-safe 校验和 deep readonly（深只读）处理的配置；
- `dateRange`、`locale`、`editable`；
- `refreshToken`：项目、筛选条件或手动刷新变化时递增，组件应监听它重新取数；
- `update:config`：只有 layout editing（布局编辑）状态才会被 Host 接受，提交后仍会再次校验。

然后在前端工程的 `frontend/src/extensions/dashboardRegistry.ts` 静态 import 组件，并把部署方组件加入默认空注册表；通用 contract 与校验逻辑继续留在 `dashboard.ts`，避免每次私有接入都修改底座实现：

```ts
import ExampleScoreWidget from '@/custom/ExampleScoreWidget.vue'
import type { DashboardWidgetExtension } from '@/extensions/dashboard'

type ScoreConfig = Readonly<{ threshold: number }>

const exampleScoreWidget = {
  type: 'custom.example.score',
  displayName: { 'zh-CN': '示例评分', en: 'Example score' },
  spaces: ['operations'],
  component: ExampleScoreWidget,
  defaultLayout: { w: 6, h: 8, minW: 4, minH: 4 },
  defaultConfig: { threshold: 80 },
  configRequired: true,
  validateConfig: (config) => typeof config.threshold === 'number'
    && Number.isInteger(config.threshold)
    && config.threshold >= 0
    && config.threshold <= 100,
} satisfies DashboardWidgetExtension<ScoreConfig>

export const dashboardWidgetExtensions: readonly DashboardWidgetExtension[] =
  Object.freeze([exampleScoreWidget])
```

`spaces` 是 base admin UI 的 placement scope（放置范围），当前支持 `operations` 和 `technical`。前端在 palette、载入、渲染和保存阶段都会校验该范围；后端仍对 type/config 做最终权威校验。Config 只能包含 JSON value，不能包含函数、`BigInt`、循环对象、`NaN`、class instance 或超过 256 KiB 的内容。

### 发布与兼容规则

- backend 与 frontend extension 必须成对构建、测试和部署；先部署不认识新 type 的一端会安全拒绝或显示 unsupported。
- schemaVersion 1 中同一个 widget type 只能出现一次。需要同类多实例时，应先升级 schema 和前端 data state，不能只放宽校验。
- 已保存 config 必须向后兼容。删除字段时继续兼容旧值；破坏性变化应使用新 type 或新的 Dashboard schemaVersion。
- 自定义组件负责自己的 loading、empty、error 和 stale response（过期响应）处理，并始终以传入的 `projectId` 和 `dateRange` 限定查询。
- 私有事件 key、业务名称和视觉资源只留在下游仓库；开源仓库只维护通用 contract、示例占位符和安全校验。

## 数据边界

- system database：项目元数据、semantic definitions/aliases、Dashboard definitions。
- project database：设备、事件、会话、流量、Counter、隐私工单和 outbox。
- raw analytics 数据不复制进 system database；需要组合时由服务层分别查询后合并，避免 cross-database JOIN。

## 埋点改名与 Counter 规则

语义字典以 `(projectId, sourceKind, rawKey)` 保证一个 raw key 只有一个归属，同时允许多个 raw key 指向同一个 semantic key。例如 `task_completed` 与后续的 `task_done_v2` 可以继续显示为同一个“完成任务”。

未映射事件自动显示 raw key；inactive definition 不参与页面解析。这样先采集、后补字典也不会丢数据。

语义字典只负责展示语义，不会静默改写累计口径。若多个 aliases 都是无条件等价事件，Counter 可以使用 `event_types`；若旧事件无条件、而新事件还需属性过滤，则使用 `any_of` 为每个事件配置独立 `conditions`。这些项目专属 alias 和条件属于下游配置，不应写进开源底座源码。规则 contract 与安全边界见 [管理端 API](API_MANAGEMENT.md#9-运营累计统计counters配置与管理)。

## 扩展一个新内置 Widget

1. 定义稳定的 `core.*` widget type 和最小 JSON config。
2. 在后端 Dashboard validator 增加字段 allow-list、长度/数量/枚举校验和测试。
3. 在前端 registry 增加渲染组件与 definition/layout 转换。
4. 明确数据 API 的 project scope、时间范围和权限。
5. 添加无配置、空数据、旧 schemaVersion 和 revision conflict 测试。
6. 更新管理端 API 文档；示例只使用通用项目和通用事件名。

私有组件应留在下游仓库，不要为了第一个使用者把业务专属 widget 合入开源 core。
