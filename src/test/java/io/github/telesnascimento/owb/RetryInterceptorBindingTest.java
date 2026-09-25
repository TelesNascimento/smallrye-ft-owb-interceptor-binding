package io.github.telesnascimento.owb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.junit.jupiter.api.Test;

class RetryInterceptorBindingTest {

    @Test
    void retryIsApplied() {
        try (SeContainer container = SeContainerInitializer.newInstance().initialize()) {
            RetryBean bean = container.select(RetryBean.class).get();

            assertThrows(IllegalStateException.class, bean::alwaysFails);
            assertEquals(3, bean.invocations(),
                    "expected 3 invocations (1 + 2 retries); 1 would mean @Retry was not applied");
        }
    }

}
