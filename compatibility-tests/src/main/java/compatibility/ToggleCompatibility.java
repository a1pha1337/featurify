package compatibility;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import ru.a1pha1337.featurify.client.FeaturifyClient;
import ru.a1pha1337.featurify.client.annotation.BooleanFeatureToggle;
import ru.a1pha1337.featurify.client.annotation.EnumFeatureToggle;
import ru.a1pha1337.featurify.client.annotation.VectorFeatureToggle;
import ru.a1pha1337.featurify.grpc.proto.BooleanFeatureResponse;
import ru.a1pha1337.featurify.grpc.proto.EnumFeatureResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Checks proxy behavior using published jars on every supported Spring/Java combination. */
public final class ToggleCompatibility {
    private ToggleCompatibility() {
    }

    static void verify() {
        for (boolean classProxy : new boolean[]{true, false}) {
            Map<String, Object> settings = new HashMap<>();
            settings.put("spring.aop.proxy-target-class", classProxy);
            settings.put("featurify.grpc.cache.ttl", "0s");
            settings.put("featurify.grpc.default-group", "checkout");
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("toggle-test", settings));
                context.register(CompatibilitySmoke.Application.class, ToggleBeans.class);
                context.refresh();
                Client client = context.getBean(Client.class);
                Contract actions = context.getBean(Contract.class);
                check(AopUtils.isCglibProxy(actions) == classProxy, "Wrong toggle proxy type");
                actions.booleanAction();
                actions.enumAction();
                actions.vectorAction();
                actions.inverse();
                check(client.executions.get() == 1, "Disabled conditions executed");
                client.enabled = true;
                actions.booleanAction();
                actions.enumAction();
                actions.vectorAction();
                actions.inverse();
                check(client.executions.get() == 4, "Enabled conditions not executed");
                actions.global();
                check("".equals(client.group), "Explicit Global lost");
                actions.explicit();
                check("other".equals(client.group), "Explicit group lost");
                actions.booleanAction();
                check("checkout".equals(client.group), "Default group lost");
                @SuppressWarnings("unchecked")
                GenericContract<String> generic = context.getBean(GenericContract.class);
                generic.apply("value");
                int before = client.executions.get();
                client.enabled = false;
                generic.apply("value");
                check(client.executions.get() == before, "Generic interface bypassed toggle");
                InheritedContract inherited = context.getBean(InheritedContract.class);
                inherited.execute();
                check(client.executions.get() == before, "Inherited implementation bypassed toggle");
                client.enabled = true;
                inherited.execute();
                check(client.executions.get() == before + 1, "Inherited implementation not executed");
                before = client.executions.get();
                client.failure = Status.UNAVAILABLE.asRuntimeException();
                try {
                    actions.booleanAction();
                    throw new AssertionError("RPC error swallowed");
                } catch (StatusRuntimeException error) {
                    check(error == client.failure, "RPC error replaced");
                }
                check(client.executions.get() == before, "RPC error executed method");
            }
        }
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(CompatibilitySmoke.Application.class, ToggleBeans.class, InvalidAction.class);
            try {
                context.refresh();
                throw new AssertionError("Non-void toggle method accepted");
            } catch (org.springframework.beans.BeansException expected) {
                check(expected.getMessage().contains("InvalidAction"), "Unexpected startup failure: " + expected);
            }
        }
    }

    @Configuration
    public static class ToggleBeans {
        @Bean public Client client() { return new Client(); }
        @Bean public Contract actions(Client client) { return new Actions(client); }
        @Bean public GenericContract<String> generic(Client client) { return new GenericAction(client); }
        @Bean public InheritedContract inherited(Client client) { return new InheritedAction(client); }
    }

    public interface Contract {
        @BooleanFeatureToggle(name = "flag") void booleanAction();
        void enumAction();
        void vectorAction();
        void inverse();
        void global();
        void explicit();
    }

    public static class Actions implements Contract {
        private final Client client;
        public Actions(Client client) { this.client = client; }
        public void booleanAction() { client.executions.incrementAndGet(); }
        @EnumFeatureToggle(name = "provider", hasValue = "STRIPE")
        public void enumAction() { client.executions.incrementAndGet(); }
        @VectorFeatureToggle(name = "channels", element = "EMAIL")
        public void vectorAction() { client.executions.incrementAndGet(); }
        @BooleanFeatureToggle(name = "flag", hasValue = false)
        public void inverse() { client.executions.incrementAndGet(); }
        @BooleanFeatureToggle(name = "flag", group = "")
        public void global() { client.executions.incrementAndGet(); }
        @BooleanFeatureToggle(name = "flag", group = "other")
        public void explicit() { client.executions.incrementAndGet(); }
    }

    public interface GenericContract<T> {
        @BooleanFeatureToggle(name = "flag") void apply(T value);
    }

    public static class GenericAction implements GenericContract<String> {
        private final Client client;
        public GenericAction(Client client) { this.client = client; }
        public void apply(String value) { client.executions.incrementAndGet(); }
    }

    public static class InvalidAction {
        @BooleanFeatureToggle(name = "flag") public String invalid() { return "unexpected"; }
    }

    public interface InheritedContract {
        @BooleanFeatureToggle(name = "flag") void execute();
    }
    public static class BaseAction {
        private final Client client;
        public BaseAction(Client client) { this.client = client; }
        public void execute() { client.executions.incrementAndGet(); }
    }
    public static class InheritedAction extends BaseAction implements InheritedContract {
        public InheritedAction(Client client) { super(client); }
    }

    public static class Client implements FeaturifyClient {
        final AtomicInteger executions = new AtomicInteger();
        boolean enabled;
        String group;
        StatusRuntimeException failure;
        public BooleanFeatureResponse getBooleanFeature(String key, String group) {
            this.group = group;
            if (failure != null) throw failure;
            return BooleanFeatureResponse.newBuilder().setValue(enabled).build();
        }
        public EnumFeatureResponse getEnumFeature(String key, String group) {
            this.group = group;
            return EnumFeatureResponse.newBuilder().setValue(enabled ? "STRIPE" : "stripe").build();
        }
        public BooleanFeatureResponse getVectorFeature(String key, String element, String group) {
            check("EMAIL".equals(element), "Vector element lost");
            return getBooleanFeature(key, group);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
