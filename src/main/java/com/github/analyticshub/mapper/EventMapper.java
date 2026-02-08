package com.github.analyticshub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.analyticshub.entity.Event;
import org.apache.ibatis.annotations.Mapper;

/**
 * Event Mapper 接口
 */
@Mapper
public interface EventMapper extends BaseMapper<Event> {
}
