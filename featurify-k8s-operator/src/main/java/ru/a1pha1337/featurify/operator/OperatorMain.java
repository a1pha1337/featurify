package ru.a1pha1337.featurify.operator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(proxyBeanMethods = false)
public class OperatorMain {
    public static void main(String[] args) {
        // The starter closes the context on startup failure but does not set the process exit code.
        if (!SpringApplication.run(OperatorMain.class, args).isActive()) {
            throw new IllegalStateException("Kubernetes operator failed to start");
        }
    }
}
