-- 交互式漏斗与留存按项目、事件类型和发生时间读取，并以 id 保证同毫秒稳定顺序。
-- 当前部署体量允许在项目初始化窗口内原子建索引；数据量显著增长后应在升级前重新评估锁窗口。
CREATE INDEX ${tablePrefix}ix_evt_proj_type_ts_id
    ON ${tablePrefix}events(project_id, event_type, event_timestamp, id);
