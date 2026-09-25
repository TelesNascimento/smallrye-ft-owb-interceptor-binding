package io.github.telesnascimento.cdi;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
@TransitiveViaClass
public class TransitiveViaClassService implements Runnable {

    @Override
    public void run() {
    }
}
