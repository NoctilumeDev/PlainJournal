package com.ecommerce.poc;

import com.ecommerce.poc.mysql.CompatibilityRecord;
import com.ecommerce.poc.mysql.CompatibilityRecordMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlMyBatisPlusCompatibilityTest extends BaseCompatibilityTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CompatibilityRecordMapper mapper;

    @Test
    void insertsAndReadsUsingMyBatisPlus() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS poc_compatibility_record (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    marker VARCHAR(64) NOT NULL,
                    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_poc_compatibility_marker (marker)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

        CompatibilityRecord record = new CompatibilityRecord();
        record.setMarker("poc-" + UUID.randomUUID());

        try {
            assertThat(mapper.insert(record)).isEqualTo(1);
            assertThat(record.getId()).isNotNull();

            CompatibilityRecord loaded = mapper.selectById(record.getId());
            assertThat(loaded).isNotNull();
            assertThat(loaded.getMarker()).isEqualTo(record.getMarker());
            assertThat(loaded.getCreatedAt()).isNotNull();
        }
        finally {
            if (record.getId() != null) {
                mapper.deleteById(record.getId());
            }
        }
    }
}

