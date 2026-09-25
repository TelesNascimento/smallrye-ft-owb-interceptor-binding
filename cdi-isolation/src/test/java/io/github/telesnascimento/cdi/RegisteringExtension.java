package io.github.telesnascimento.cdi;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.BeforeBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;

public class RegisteringExtension implements Extension {

    void registerTransitiveBindings(@Observes BeforeBeanDiscovery beforeBeanDiscovery, BeanManager beanManager) {
        beforeBeanDiscovery.addInterceptorBinding(TransitiveViaClass.class, CountingBinding.Literal.INSTANCE);
        beforeBeanDiscovery.addInterceptorBinding(
                new BindingAddingAnnotatedType<>(beanManager.createAnnotatedType(TransitiveViaAnnotatedType.class)));
    }
}
