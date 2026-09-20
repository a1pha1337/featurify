package ru.a1pha1337.featurify.client.annotation;

/** Constants shared by method toggle annotations. */
public final class FeatureToggleGroups {
    /** Sentinel distinct from every valid group key, including the empty Global group. */
    public static final String DEFAULT = "\n<featurify-default-group>\n";

    private FeatureToggleGroups() {
    }
}
