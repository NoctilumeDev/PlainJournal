package com.ecommerce.trade.interfaces.rest;

import com.ecommerce.platform.common.api.ApiResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemStatusControllerTest {

    @Test
    void exposesInstanceAndReleaseIdentityWithoutChangingSuccessContract() {
        SystemStatusController controller =
                new SystemStatusController("nacos", "trade-instance-2", "m3-candidate");

        ApiResponse<SystemStatusController.SystemStatusResponse> response = controller.status();

        assertThat(response.code()).isEqualTo("OK");
        assertThat(response.data()).isEqualTo(new SystemStatusController.SystemStatusResponse(
                "trade-service",
                "nacos",
                "trade-instance-2",
                "m3-candidate"));
    }
}
