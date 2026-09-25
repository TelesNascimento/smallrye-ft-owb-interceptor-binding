package io.github.telesnascimento.owb;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.faulttolerance.Retry;

@ApplicationScoped
public class RetryBean {

    private final AtomicInteger invocations = new AtomicInteger();

    @Retry(maxRetries = 2)
    public void alwaysFails() {
        invocations.incrementAndGet();
        throw new IllegalStateException("simulated failure");
    }

    public int invocations() {
        return invocations.get();
    }
}
