package ru.a1pha1337.featurify.operator;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("featurify.io")
@Version("v1alpha1")
@Kind("FeaturifyNamespace")
@Plural("featurifynamespaces")
@Singular("featurifynamespace")
public final class FeaturifyNamespace extends CustomResource<NamespaceSpec, NamespaceStatus> implements Namespaced {
}
