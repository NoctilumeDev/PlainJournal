package com.ecommerce.trade;

import com.ecommerce.trade.application.port.CatalogPort;
import com.ecommerce.trade.application.port.InventoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@ActiveProfiles("test")
@SpringBootTest
class TradeServiceApplicationTest {

    @MockitoBean
    CatalogPort catalogPort;

    @MockitoBean
    InventoryPort inventoryPort;

    @Test
    void contextLoads() {
    }
}
