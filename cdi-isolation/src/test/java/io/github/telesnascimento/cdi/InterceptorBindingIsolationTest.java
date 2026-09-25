package io.github.telesnascimento.cdi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import org.junit.jupiter.api.Test;

class InterceptorBindingIsolationTest {

    @Test
    void transitiveInterceptorBindingFromExtensionIsApplied() {
        try (SeContainer container = SeContainerInitializer.newInstance()
                .disableDiscovery()
                .addExtensions(new RegisteringExtension())
                .addBeanClasses(CountingInterceptor.class,
                        DirectlyBoundService.class,
                        TransitiveViaClassService.class,
                        TransitiveViaAnnotatedTypeService.class)
                .initialize()) {

            int direct = invoke(container, DirectlyBoundService.class);
            int viaClass = invoke(container, TransitiveViaClassService.class);
            int viaAnnotatedType = invoke(container, TransitiveViaAnnotatedTypeService.class);

            System.out.println("[ISO] container=" + container.getBeanManager().getClass().getName());
            System.out.println("[ISO] direct @CountingBinding         -> intercepted=" + direct);
            System.out.println("[ISO] transitive via Class,Annotation -> intercepted=" + viaClass);
            System.out.println("[ISO] transitive via AnnotatedType    -> intercepted=" + viaAnnotatedType);

            assertEquals(1, direct, "a directly bound bean must be intercepted");
            assertEquals(1, viaClass,
                    "a bean carrying a binding registered via addInterceptorBinding(Class, Annotation...) "
                            + "must be intercepted; 0 means OpenWebBeans dropped the transitive binding");
            assertEquals(1, viaAnnotatedType,
                    "a bean carrying a binding registered via addInterceptorBinding(AnnotatedType) "
                            + "must be intercepted; 0 means OpenWebBeans dropped the transitive binding");
        }
    }

    private static int invoke(SeContainer container, Class<? extends Runnable> serviceType) {
        CountingInterceptor.COUNT.set(0);
        container.select(serviceType).get().run();
        return CountingInterceptor.COUNT.get();
    }
}
