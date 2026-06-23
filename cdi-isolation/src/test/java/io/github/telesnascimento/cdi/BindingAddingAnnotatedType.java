package io.github.telesnascimento.cdi;

import jakarta.enterprise.inject.spi.AnnotatedConstructor;
import jakarta.enterprise.inject.spi.AnnotatedField;
import jakarta.enterprise.inject.spi.AnnotatedMethod;
import jakarta.enterprise.inject.spi.AnnotatedType;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Set;

/**
 * An AnnotatedType wrapper that adds @CountingBinding, used to register a binding via the
 * BeforeBeanDiscovery.addInterceptorBinding(AnnotatedType) form (the second registration form).
 */
public class BindingAddingAnnotatedType<T extends Annotation> implements AnnotatedType<T> {

    private final AnnotatedType<T> delegate;
    private final Set<Annotation> annotations;

    public BindingAddingAnnotatedType(AnnotatedType<T> delegate) {
        this.delegate = delegate;
        this.annotations = new HashSet<>(delegate.getAnnotations());
        this.annotations.add(CountingBinding.Literal.INSTANCE);
    }

    @Override
    public Class<T> getJavaClass() {
        return delegate.getJavaClass();
    }

    @Override
    public Set<AnnotatedConstructor<T>> getConstructors() {
        return delegate.getConstructors();
    }

    @Override
    public Set<AnnotatedMethod<? super T>> getMethods() {
        return delegate.getMethods();
    }

    @Override
    public Set<AnnotatedField<? super T>> getFields() {
        return delegate.getFields();
    }

    @Override
    public Type getBaseType() {
        return delegate.getBaseType();
    }

    @Override
    public Set<Type> getTypeClosure() {
        return delegate.getTypeClosure();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends Annotation> A getAnnotation(Class<A> annotationType) {
        if (CountingBinding.class.equals(annotationType)) {
            return (A) CountingBinding.Literal.INSTANCE;
        }
        return delegate.getAnnotation(annotationType);
    }

    @Override
    public Set<Annotation> getAnnotations() {
        return annotations;
    }

    @Override
    public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {
        return CountingBinding.class.equals(annotationType) || delegate.isAnnotationPresent(annotationType);
    }
}
