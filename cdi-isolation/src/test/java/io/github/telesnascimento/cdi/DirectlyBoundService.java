package io.github.telesnascimento.cdi;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DirectlyBoundService implements Runnable {

    @CountingBinding
    @Override
    public void run() {
    }
}
