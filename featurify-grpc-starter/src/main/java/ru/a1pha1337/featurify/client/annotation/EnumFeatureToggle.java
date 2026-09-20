package ru.a1pha1337.featurify.client.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Gates a public, non-final void Spring bean method on an exact, case-sensitive enum value. */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EnumFeatureToggle {
    String name();

    String hasValue();

    /** Omission uses the service's default group; an empty string explicitly selects Global. */
    String group() default FeatureToggleGroups.DEFAULT;
}
