package com.ecommerce.marketing.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.marketing.infrastructure.persistence.entity.MarketingRuleEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface MarketingRuleMapper extends BaseMapper<MarketingRuleEntity> {

    @Select("SELECT * FROM marketing_rule WHERE rule_code = #{ruleCode}")
    MarketingRuleEntity selectByRuleCode(@Param("ruleCode") String ruleCode);
}
