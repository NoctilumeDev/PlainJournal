package com.ecommerce.poc;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class NacosCompatibilityTest extends BaseCompatibilityTest {

    private static final String GROUP = "POC_GROUP";

    @Value("${poc.nacos.server-addr}")
    private String serverAddress;

    @Value("${poc.nacos.username}")
    private String username;

    @Value("${poc.nacos.password}")
    private String password;

    @Test
    void publishesConfigurationAndRegistersAService() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(PropertyKeyConst.SERVER_ADDR, serverAddress);
        properties.setProperty(PropertyKeyConst.USERNAME, username);
        properties.setProperty(PropertyKeyConst.PASSWORD, password);

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String dataId = "middleware-compatibility-" + suffix + ".properties";
        String serviceName = "middleware-compatibility-" + suffix;
        String content = "poc.compatibility=ready";

        ConfigService configService = NacosFactory.createConfigService(properties);
        NamingService namingService = NacosFactory.createNamingService(properties);
        try {
            assertThat(configService.publishConfig(dataId, GROUP, content)).isTrue();
            assertThat(awaitConfig(configService, dataId)).isEqualTo(content);

            namingService.registerInstance(serviceName, GROUP, "127.0.0.1", 19090);
            List<Instance> instances = namingService.getAllInstances(serviceName, GROUP);
            assertThat(instances).anyMatch(instance -> instance.getPort() == 19090);
        }
        finally {
            namingService.deregisterInstance(serviceName, GROUP, "127.0.0.1", 19090);
            configService.removeConfig(dataId, GROUP);
            namingService.shutDown();
            configService.shutDown();
        }
    }

    private String awaitConfig(ConfigService configService, String dataId) throws Exception {
        Instant deadline = Instant.now().plusSeconds(10);
        String value = null;
        while (value == null && Instant.now().isBefore(deadline)) {
            value = configService.getConfig(dataId, GROUP, 3000);
            if (value == null) {
                Thread.sleep(200);
            }
        }
        return value;
    }
}
