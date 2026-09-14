package ru.a1pha1337.featurify.operator;

import java.io.IOException;

public interface ManifestClient {
    void apply(FeaturifyNamespace resource) throws IOException, InterruptedException;

    void cleanup(FeaturifyNamespace resource) throws IOException, InterruptedException;
}
