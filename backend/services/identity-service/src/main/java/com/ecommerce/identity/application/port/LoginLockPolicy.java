package com.ecommerce.identity.application.port;

import java.time.Duration;

public interface LoginLockPolicy {

    Duration lockDuration();
}
