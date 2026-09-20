package ru.a1pha1337.featurify.operator;

import java.util.List;
import java.util.Map;

public record NamespaceSpec(String key, String displayName, Management management,
                            List<Feature> features, List<Group> groups) {
    public NamespaceSpec {
        management = management == null ? new Management(null, null, null, false) : management;
        features = features == null ? List.of() : List.copyOf(features);
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    public enum OwnershipPolicy { Exclusive, Shared }
    public enum ValuePolicy { Managed, InitialOnly }
    public enum DeletionPolicy { Retain, Delete }
    public enum FeatureType { BOOLEAN, ENUM, VECTOR, PAYLOAD }

    public record Management(OwnershipPolicy ownershipPolicy, ValuePolicy valuePolicy,
                             DeletionPolicy deletionPolicy, boolean adoptExisting) {
        public Management {
            ownershipPolicy = ownershipPolicy == null ? OwnershipPolicy.Exclusive : ownershipPolicy;
            valuePolicy = valuePolicy == null ? ValuePolicy.Managed : valuePolicy;
            deletionPolicy = deletionPolicy == null ? DeletionPolicy.Retain : deletionPolicy;
        }
    }

    public record Group(String key, String displayName, List<Feature> features) {
        public Group {
            features = features == null ? List.of() : List.copyOf(features);
        }
    }

    public record Feature(String key, FeatureType type, String description, Boolean booleanValue,
                          String enumValue, List<String> enumOptions, Map<String, Boolean> vectorValues,
                          String payloadValue) {
        public Feature {
            description = description == null ? "" : description;
            enumOptions = enumOptions == null ? List.of() : List.copyOf(enumOptions);
            vectorValues = vectorValues == null ? Map.of() : Map.copyOf(vectorValues);
        }
    }
}
