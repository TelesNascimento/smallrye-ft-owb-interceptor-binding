package io.github.telesnascimento.cdi;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@InterceptorBinding
@Target({ TYPE, METHOD })
@Retention(RUNTIME)
public @interface CountingBinding {

    final class Literal extends AnnotationLiteral<CountingBinding> implements CountingBinding {

        public static final CountingBinding INSTANCE = new Literal();
    }
}
