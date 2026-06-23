package io.github.telesnascimento.owb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;

/**
 * Control for the real SmallRye Fault Tolerance @Retry: under Weld it deploys and applies the
 * interceptor (3 invocations = 1 + 2 retries). The SmallRye-free cdi-isolation module reproduces
 * minimally why the same @Retry is not applied on OpenWebBeans 4.1.0 (the transitive interceptor
 * binding that FaultToleranceExtension registers programmatically is not folded in). Running the
 * real stack on plain OpenWebBeans SE also needs the CDI API aligned to 4.1 and a separate SE
 * classpath workaround, so it is not asserted here; cdi-isolation is the reproducer to run.
 */
class RetryInterceptorBindingTest {

    @Test
    @DisabledIf("openWebBeans")
    void retryIsAppliedUnderWeld() {
        try (SeContainer container = SeContainerInitializer.newInstance().initialize()) {
            RetryBean bean = container.select(RetryBean.class).get();

            assertThrows(IllegalStateException.class, bean::alwaysFails);
            assertEquals(3, bean.invocations(),
                    "expected 3 invocations (1 + 2 retries); 1 would mean @Retry was not applied");
        }
    }

    static boolean openWebBeans() {
        try {
            Class.forName("org.apache.webbeans.container.BeanManagerImpl");
            return true;
        }
        catch (ClassNotFoundException notOpenWebBeans) {
            return false;
        }
    }
}
