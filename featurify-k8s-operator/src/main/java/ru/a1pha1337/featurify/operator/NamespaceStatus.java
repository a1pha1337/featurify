package ru.a1pha1337.featurify.operator;

import java.util.List;

public record NamespaceStatus(Long observedGeneration, List<Condition> conditions) {
    public record Condition(String type, String status, String reason, String message,
                            long observedGeneration, String lastTransitionTime) {
    }
}
