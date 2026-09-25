package io.github.telesnascimento.cdi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.BeforeBeanDiscovery;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.InterceptionType;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

public class BindingGraphTest {
    enum Registration { CLASS, ANNOTATED_TYPE, CONFIGURATOR }

    static Stream<Arguments> bindingMatrix() {
        return Arrays.stream(Registration.values()).flatMap(registration -> Stream.of(
                Arguments.of(registration, "direct", "class", ClassService.class),
                Arguments.of(registration, "direct", "method", MethodService.class),
                Arguments.of(registration, "direct", "inherited", InheritedService.class),
                Arguments.of(registration, "programmatic-to-bytecode", "class", MixedService.class),
                Arguments.of(registration, "programmatic-to-bytecode", "method", MixedMethodService.class),
                Arguments.of(registration, "programmatic-to-bytecode", "inherited", InheritedMixedService.class),
                Arguments.of(registration, "bytecode-to-programmatic", "class", BytecodeToProgrammaticService.class),
                Arguments.of(registration, "bytecode-to-programmatic", "method", BytecodeToProgrammaticMethodService.class),
                Arguments.of(registration, "bytecode-to-programmatic", "inherited", InheritedBytecodeToProgrammaticService.class)));
    }

    @ParameterizedTest(name = "{0} / {1} / {2}")
    @MethodSource("bindingMatrix")
    void transitiveBindingMatrix(Registration registration, String chain, String placement, Class<? extends Runnable> bean) {
        try (SeContainer container = start(new GraphExtension(registration), bean)) {
            int intercepted = count(container, bean);
            System.out.println("[MATRIX] " + registration + " | " + chain + " | " + placement + " | " + intercepted);
            assertEquals(1, intercepted, registration + " / " + chain + " / " + placement);
        }
    }

    @ParameterizedTest
    @EnumSource(Registration.class)
    void nonInheritedBindingDoesNotReachSubclass(Registration registration) {
        try (SeContainer container = start(new GraphExtension(registration), NonInheritedParent.class)) {
            assertEquals(1, count(container, NonInheritedParent.class));
        }
        try (SeContainer container = start(new GraphExtension(registration), NonInheritedChild.class)) {
            assertEquals(0, count(container, NonInheritedChild.class));
        }
    }

    @ParameterizedTest
    @EnumSource(Registration.class)
    void bindingDefinitionIncludesExtensionMetadata(Registration registration) {
        try (SeContainer container = start(new GraphExtension(registration), ClassService.class)) {
            BeanManager manager = container.getBeanManager();
            assertTrue(manager.isInterceptorBinding(Programmatic.class));
            Set<Annotation> definition = manager.getInterceptorBindingDefinition(Programmatic.class);
            assertTrue(definition.contains(CountingBinding.Literal.INSTANCE));
            assertTrue(definition.stream().anyMatch(annotation -> annotation.annotationType() == InterceptorBinding.class));
        }
    }

    @ParameterizedTest
    @EnumSource(Registration.class)
    void spiResolvesTransitiveBindings(Registration registration) {
        try (SeContainer container = start(new GraphExtension(registration), MixedService.class)) {
            assertEquals(1, container.getBeanManager().resolveInterceptors(InterceptionType.AROUND_INVOKE,
                    MixedService.class.getAnnotation(Mixed.class)).size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void firstAnnotatedTypeDefinitionIsRetained(boolean firstHasBinding) {
        Extension extension = new Extension() {
            void register(@Observes BeforeBeanDiscovery event, BeanManager manager) {
                Annotation[] withBinding = {CountingBinding.Literal.INSTANCE};
                Annotation[] withoutBinding = {};
                event.addInterceptorBinding(new BindingAddingAnnotatedType<>(manager.createAnnotatedType(Programmatic.class),
                        firstHasBinding ? withBinding : withoutBinding));
                event.addInterceptorBinding(new BindingAddingAnnotatedType<>(manager.createAnnotatedType(Programmatic.class),
                        firstHasBinding ? withoutBinding : withBinding));
            }
        };
        try (SeContainer container = start(extension, ClassService.class)) {
            assertEquals(firstHasBinding ? 1 : 0, count(container, ClassService.class));
            assertEquals(firstHasBinding, container.getBeanManager().getInterceptorBindingDefinition(Programmatic.class)
                    .contains(CountingBinding.Literal.INSTANCE));
        }
    }

    @Test
    void annotatedTypeRemovalIsAuthoritative() {
        Extension extension = new Extension() {
            void configure(@Observes BeforeBeanDiscovery event) {
                event.configureInterceptorBinding(Bytecode.class)
                        .remove(annotation -> annotation.annotationType() == CountingBinding.class);
            }
        };
        try (SeContainer container = start(extension, BytecodeControl.class)) {
            assertEquals(0, count(container, BytecodeControl.class));
            assertFalse(container.getBeanManager().getInterceptorBindingDefinition(Bytecode.class)
                    .contains(CountingBinding.Literal.INSTANCE));
        }
    }

    @Test
    void nonbindingConfiguredByExtensionAllowsDifferentTransitiveValues() {
        try (SeContainer container = start(new ValueExtension(true), ConflictingService.class, ValueInterceptor.class)) {
            assertEquals("intercepted", container.select(ConflictingService.class).get().run());
        }
    }

    @Test
    void methodValueOverridesTransitiveClassValue() {
        try (SeContainer container = start(new ValueExtension(false), OverridingService.class, ValueInterceptor.class, ClassValueInterceptor.class)) {
            assertEquals("intercepted", container.select(OverridingService.class).get().run());
        }
    }

    @Test
    void unrelatedBeanIsNotIntercepted() {
        try (SeContainer container = start(new GraphExtension(Registration.CLASS), PlainService.class)) {
            assertEquals(0, count(container, PlainService.class));
        }
    }

    private static SeContainer start(Extension extension, Class<?>... beans) {
        return SeContainerInitializer.newInstance().disableDiscovery().addExtensions(extension)
                .addBeanClasses(CountingInterceptor.class).addBeanClasses(beans).initialize();
    }

    private static int count(SeContainer container, Class<? extends Runnable> type) {
        CountingInterceptor.COUNT.set(0);
        container.select(type).get().run();
        return CountingInterceptor.COUNT.get();
    }

    public static class GraphExtension implements Extension {
        private final Registration registration;

        GraphExtension(Registration registration) {
            this.registration = registration;
        }

        void register(@Observes BeforeBeanDiscovery event, BeanManager manager) {
            register(event, manager, Programmatic.class, CountingBinding.Literal.INSTANCE);
            register(event, manager, Mixed.class, new BytecodeLiteral());
            register(event, manager, NonInherited.class, CountingBinding.Literal.INSTANCE);
        }

        private <T extends Annotation> void register(BeforeBeanDiscovery event, BeanManager manager,
                Class<T> binding, Annotation inherited) {
            switch (registration) {
                case CLASS -> event.addInterceptorBinding(binding, inherited);
                case ANNOTATED_TYPE -> event.addInterceptorBinding(
                        new BindingAddingAnnotatedType<>(manager.createAnnotatedType(binding), inherited));
                case CONFIGURATOR -> event.configureInterceptorBinding(binding).add(inherited);
            }
        }
    }

    public static class ValueExtension implements Extension {
        private final boolean nonbinding;

        ValueExtension(boolean nonbinding) {
            this.nonbinding = nonbinding;
        }

        void register(@Observes BeforeBeanDiscovery event) {
            event.addInterceptorBinding(Left.class, new ValueLiteral("left"));
            event.addInterceptorBinding(Right.class, new ValueLiteral("right"));
            if (nonbinding) {
                event.configureInterceptorBinding(Value.class)
                        .filterMethods(method -> method.getJavaMember().getName().equals("value"))
                        .forEach(method -> method.add(Nonbinding.Literal.INSTANCE));
            }
        }
    }

    @Inherited
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Programmatic { }

    @Inherited
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Mixed { }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface NonInherited { }

    @InterceptorBinding
    @CountingBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Bytecode { }

    @InterceptorBinding
    @Programmatic
    @Inherited
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface BytecodeToProgrammatic { }

    @Dependent
    @BytecodeToProgrammatic
    public static class BytecodeToProgrammaticService implements Runnable {
        public void run() { }
    }

    public static class BytecodeLiteral extends AnnotationLiteral<Bytecode> implements Bytecode { }

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Value { String value(); }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface Left { }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public @interface Right { }

    public static class ValueLiteral extends AnnotationLiteral<Value> implements Value {
        private final String value;

        ValueLiteral(String value) { this.value = value; }

        @Override
        public String value() { return value; }
    }

    @Dependent
    @Programmatic
    public static class ClassService implements Runnable {
        public void run() { }
    }

    @Dependent
    public static class MethodService implements Runnable {
        @Programmatic
        public void run() { }
    }

    @Programmatic
    public static class ParentService implements Runnable {
        public void run() { }
    }

    @Dependent
    public static class InheritedService extends ParentService {
        @Override
        public void run() { }
    }

    @Dependent
    @Mixed
    public static class MixedService implements Runnable {
        public void run() { }
    }

    @Dependent
    public static class MixedMethodService implements Runnable {
        @Mixed
        public void run() { }
    }

    @Mixed
    public static class MixedParentService implements Runnable {
        public void run() { }
    }

    @Dependent
    public static class InheritedMixedService extends MixedParentService {
        @Override
        public void run() { }
    }

    @Dependent
    public static class BytecodeToProgrammaticMethodService implements Runnable {
        @BytecodeToProgrammatic
        public void run() { }
    }

    @BytecodeToProgrammatic
    public static class BytecodeToProgrammaticParentService implements Runnable {
        public void run() { }
    }

    @Dependent
    public static class InheritedBytecodeToProgrammaticService extends BytecodeToProgrammaticParentService {
        @Override
        public void run() { }
    }

    @Dependent
    @NonInherited
    public static class NonInheritedParent implements Runnable {
        public void run() { }
    }

    @Dependent
    public static class NonInheritedChild extends NonInheritedParent {
        @Override
        public void run() { }
    }

    @Dependent
    @Bytecode
    public static class BytecodeControl implements Runnable {
        public void run() { }
    }

    @Dependent
    public static class PlainService implements Runnable {
        public void run() { }
    }

    @Dependent
    @Left
    @Right
    public static class ConflictingService {
        public String run() { return "not intercepted"; }
    }

    @Dependent
    @Left
    public static class OverridingService {
        @Value("method")
        public String run() { return "not intercepted"; }
    }

    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION - 1)
    @Value("left")
    public static class ClassValueInterceptor {
        @AroundInvoke
        Object intercept(InvocationContext context) { return "overridden class binding was incorrectly restored"; }
    }

    @Interceptor
    @Priority(Interceptor.Priority.APPLICATION)
    @Value("method")
    public static class ValueInterceptor {
        @AroundInvoke
        Object intercept(InvocationContext context) { return "intercepted"; }
    }
}
