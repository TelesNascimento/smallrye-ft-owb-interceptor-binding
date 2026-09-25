package io.github.telesnascimento.cdi;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@TransitiveViaAnnotatedType
public class TransitiveViaAnnotatedTypeService implements Runnable {

    @Override
    public void run() {
    }
}
