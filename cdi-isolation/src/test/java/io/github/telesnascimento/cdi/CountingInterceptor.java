package io.github.telesnascimento.cdi;

import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.util.concurrent.atomic.AtomicInteger;

@CountingBinding
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class CountingInterceptor {

    public static final AtomicInteger COUNT = new AtomicInteger();

    @AroundInvoke
    Object count(InvocationContext context) throws Exception {
        COUNT.incrementAndGet();
        return context.proceed();
    }
}
