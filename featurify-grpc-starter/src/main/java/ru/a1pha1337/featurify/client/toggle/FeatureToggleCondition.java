package ru.a1pha1337.featurify.client.toggle;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import ru.a1pha1337.featurify.client.annotation.BooleanFeatureToggle;
import ru.a1pha1337.featurify.client.annotation.EnumFeatureToggle;
import ru.a1pha1337.featurify.client.annotation.FeatureToggleGroups;
import ru.a1pha1337.featurify.client.annotation.VectorFeatureToggle;
import ru.a1pha1337.featurify.client.service.BooleanFeatureService;
import ru.a1pha1337.featurify.client.service.EnumFeatureService;
import ru.a1pha1337.featurify.client.service.VectorFeatureService;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.Predicate;

final class FeatureToggleCondition {
    private final Predicate<BeanFactory> predicate;

    private FeatureToggleCondition(Predicate<BeanFactory> predicate) {
        this.predicate = predicate;
    }

    boolean matches(BeanFactory beans) {
        return predicate.test(beans);
    }

    static FeatureToggleCondition resolve(Method method, Class<?> targetClass) {
        Method specific = AopUtils.getMostSpecificMethod(method, targetClass);
        BooleanFeatureToggle bool = find(specific, method, targetClass, BooleanFeatureToggle.class);
        EnumFeatureToggle enumeration = find(specific, method, targetClass, EnumFeatureToggle.class);
        VectorFeatureToggle vector = find(specific, method, targetClass, VectorFeatureToggle.class);
        int count = (bool == null ? 0 : 1) + (enumeration == null ? 0 : 1) + (vector == null ? 0 : 1);
        if (count == 0) return null;
        require(count == 1, specific, "only one feature toggle annotation is allowed");
        int modifiers = specific.getModifiers();
        require(Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers) && !Modifier.isFinal(modifiers),
                specific, "feature toggle methods must be public, non-static and non-final");
        require(specific.getReturnType() == void.class, specific, "feature toggle methods must return void (Kotlin Unit)");
        if (bool != null) {
            validate(bool.name(), bool.group(), specific);
            return new FeatureToggleCondition(beans -> {
                BooleanFeatureService service = beans.getBean(BooleanFeatureService.class);
                boolean value = isDefault(bool.group()) ? service.isEnabled(bool.name()) : service.isEnabled(bool.name(), bool.group());
                return value == bool.hasValue();
            });
        }
        if (enumeration != null) {
            validate(enumeration.name(), enumeration.group(), specific);
            return new FeatureToggleCondition(beans -> {
                EnumFeatureService service = beans.getBean(EnumFeatureService.class);
                String value = isDefault(enumeration.group()) ? service.getValue(enumeration.name())
                        : service.getValue(enumeration.name(), enumeration.group());
                return enumeration.hasValue().equals(value);
            });
        }
        validate(vector.name(), vector.group(), specific);
        require(StringUtils.hasText(vector.element()) && vector.element().length() <= 255,
                specific, "vector element must contain 1-255 characters and not be blank");
        return new FeatureToggleCondition(beans -> {
            VectorFeatureService service = beans.getBean(VectorFeatureService.class);
            boolean value = isDefault(vector.group()) ? service.isEnabled(vector.name(), vector.element())
                    : service.isEnabled(vector.name(), vector.element(), vector.group());
            return value == vector.hasValue();
        });
    }

    private static <A extends Annotation> A find(Method specific, Method original, Class<?> targetClass, Class<A> type) {
        A annotation = AnnotationUtils.findAnnotation(specific, type);
        if (annotation != null) return annotation;
        // An inherited implementation may be declared on a superclass that does not implement
        // the annotated interface. Resolve against the actual bean type for both proxy kinds.
        for (Class<?> contract : ClassUtils.getAllInterfacesForClass(targetClass)) {
            for (Method candidate : contract.getMethods()) {
                if (AopUtils.getMostSpecificMethod(candidate, targetClass).equals(specific)) {
                    A inherited = AnnotationUtils.findAnnotation(candidate, type);
                    if (inherited != null) {
                        require(annotation == null || annotation.equals(inherited), specific,
                                "conflicting feature toggle declarations on interfaces");
                        annotation = inherited;
                    }
                }
            }
        }
        return annotation != null ? annotation : AnnotationUtils.findAnnotation(original, type);
    }

    private static void validate(String name, String group, Method method) {
        require(StringUtils.hasText(name), method, "feature name must not be blank");
        require(isDefault(group) || group.isEmpty() || StringUtils.hasText(group), method, "group must not be blank; use an empty string for Global");
    }

    private static boolean isDefault(String group) {
        return FeatureToggleGroups.DEFAULT.equals(group);
    }

    private static void require(boolean valid, Method method, String message) {
        if (!valid) throw new IllegalArgumentException(method.toGenericString() + ": " + message);
    }
}
