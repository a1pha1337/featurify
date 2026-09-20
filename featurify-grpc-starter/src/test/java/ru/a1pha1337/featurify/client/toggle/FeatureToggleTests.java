package ru.a1pha1337.featurify.client.toggle;

import io.grpc.Metadata;
import io.grpc.Status;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import ru.a1pha1337.featurify.client.FeaturifyClient;
import ru.a1pha1337.featurify.client.annotation.BooleanFeatureToggle;
import ru.a1pha1337.featurify.client.annotation.EnumFeatureToggle;
import ru.a1pha1337.featurify.client.annotation.VectorFeatureToggle;
import ru.a1pha1337.featurify.client.autoconfigure.FeaturifyGrpcAutoConfiguration;
import ru.a1pha1337.featurify.client.service.BooleanFeatureService;
import ru.a1pha1337.featurify.grpc.proto.BooleanFeatureResponse;
import ru.a1pha1337.featurify.grpc.proto.EnumFeatureResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeatureToggleTests {
    private final StubClient client = new StubClient();
    private final AtomicInteger executions = new AtomicInteger();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FeaturifyGrpcAutoConfiguration.class))
            .withBean(FeaturifyClient.class, () -> client)
            .withPropertyValues("featurify.grpc.cache.ttl=0s", "featurify.grpc.default-group=checkout");

    @Test
    void allTypesMatchExactlyAndReevaluateEachCall() {
        runner.withBean(Actions.class, () -> new Actions(executions)).run(context -> {
            assertThat(context).hasNotFailed();
            Actions actions = context.getBean(Actions.class);
            assertThat(AopUtils.isCglibProxy(actions)).isTrue();
            actions.booleanAction("argument");
            actions.inverse();
            actions.enumAction();
            actions.vectorAction();
            actions.inverseVector();
            assertThat(executions).hasValue(2);
            client.enabled = true;
            client.enumValue = "STRIPE";
            actions.booleanAction("argument");
            actions.inverse();
            actions.enumAction();
            actions.vectorAction();
            actions.inverseVector();
            assertThat(executions).hasValue(5);
            assertThat(client.requests).contains("boolean:flag:checkout", "enum:provider:checkout", "vector:channels:EMAIL:checkout");
        });
    }

    @Test
    void groupOverridesDistinguishDefaultGlobalAndExplicitGroups() {
        runner.withBean(Actions.class, () -> new Actions(executions)).run(context -> {
            Actions actions = context.getBean(Actions.class);
            actions.booleanAction("unused");
            actions.global();
            actions.other();
            assertThat(client.requests).containsExactly("boolean:flag:checkout", "boolean:flag:", "boolean:flag:other");
        });
    }

    @Test
    void usesExistingServiceCacheAndCanBeReplacedByACustomService() {
        runner.withPropertyValues("featurify.grpc.cache.ttl=1h")
                .withBean(Actions.class, () -> new Actions(executions)).run(context -> {
                    Actions actions = context.getBean(Actions.class);
                    actions.booleanAction("unused");
                    client.enabled = true;
                    actions.booleanAction("unused");
                    assertThat(context.getBean(BooleanFeatureService.class).isEnabled("flag")).isFalse();
                    assertThat(executions).hasValue(0);
                    assertThat(client.requests).hasSize(1);
                });
        BooleanFeatureService custom = mock(BooleanFeatureService.class);
        when(custom.isEnabled(anyString())).thenReturn(true);
        runner.withBean(BooleanFeatureService.class, () -> custom)
                .withBean(Actions.class, () -> new Actions(executions)).run(context -> {
                    context.getBean(Actions.class).booleanAction("argument");
                    assertThat(executions).hasValue(1);
                    assertThat(client.requests).hasSize(1);
                });
    }

    @Test
    void rpcErrorsIncludingTrailersPropagateWithoutExecutingOrCaching() {
        runner.withPropertyValues("featurify.grpc.cache.ttl=1h")
                .withBean(Actions.class, () -> new Actions(executions)).run(context -> {
                    Actions actions = context.getBean(Actions.class);
                    for (Status status : new Status[]{Status.NOT_FOUND, Status.UNAVAILABLE, Status.UNAUTHENTICATED, Status.FAILED_PRECONDITION}) {
                        client.error = status.asRuntimeException(new Metadata());
                        assertThatThrownBy(() -> actions.booleanAction("argument")).isSameAs(client.error);
                    }
                    assertThat(executions).hasValue(0);
                    client.error = null;
                    client.enabled = true;
                    actions.booleanAction("argument");
                    assertThat(executions).hasValue(1);
                    assertThat(client.requests).hasSize(5);
                });
    }

    @Test
    void originalMethodExceptionPropagatesUnchanged() {
        client.enabled = true;
        runner.withBean(Actions.class, () -> new Actions(executions)).run(context -> {
            assertThatThrownBy(() -> context.getBean(Actions.class).throwing()).isSameAs(Actions.FAILURE);
        });
    }

    @Test
    void interfaceAndImplementationAnnotationsWorkWithBothProxyTypes() {
        for (boolean classProxy : new boolean[]{true, false}) {
            runner.withPropertyValues("spring.aop.proxy-target-class=" + classProxy)
                    .withBean(Contract.class, () -> new ContractImpl(executions)).run(context -> {
                        assertThat(context).hasNotFailed();
                        Contract actions = context.getBean(Contract.class);
                        assertThat(AopUtils.isCglibProxy(actions)).isEqualTo(classProxy);
                        client.enabled = false;
                        actions.onInterface();
                        actions.onImplementation();
                        int before = executions.get();
                        client.enabled = true;
                        actions.onInterface();
                        actions.onImplementation();
                        assertThat(executions).hasValue(before + 2);
                    });
        }
    }

    @Test
    void inheritedAndGenericInterfaceMethodsAreGuarded() {
        for (boolean classProxy : new boolean[]{true, false}) {
            runner.withPropertyValues("spring.aop.proxy-target-class=" + classProxy)
                    .withBean(GenericContract.class, () -> new GenericImpl(executions)).run(context -> {
                        assertThat(context).hasNotFailed();
                        @SuppressWarnings("unchecked")
                        GenericContract<String> actions = context.getBean(GenericContract.class);
                        client.enabled = false;
                        int before = executions.get();
                        actions.apply("argument");
                        assertThat(executions).hasValue(before);
                        client.enabled = true;
                        actions.apply("argument");
                        assertThat(executions).hasValue(before + 1);
                    });
        }
    }

    @Test
    void rejectsUnsupportedDeclarationsAtStartupWithoutRpc() {
        for (Class<?> invalid : new Class<?>[]{Returning.class, PrivateMethod.class, StaticMethod.class,
                FinalMethod.class, FinalClass.class, BlankName.class, BlankElement.class, BlankGroup.class, Multiple.class, ConflictingImpl.class}) {
            runner.withUserConfiguration(invalid).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining(invalid.getSimpleName());
                assertThat(client.requests).isEmpty();
            });
        }
    }

    @Test
    void interfaceOnSubclassGuardsAnInheritedImplementation() {
        for (boolean classProxy : new boolean[]{true, false}) {
            runner.withPropertyValues("spring.aop.proxy-target-class=" + classProxy)
                    .withBean(InheritedContract.class, () -> new InheritedAction(executions)).run(context -> {
                        assertThat(context).hasNotFailed();
                        InheritedContract action = context.getBean(InheritedContract.class);
                        client.enabled = false;
                        int before = executions.get();
                        action.execute();
                        assertThat(executions).hasValue(before);
                        client.enabled = true;
                        action.execute();
                        assertThat(executions).hasValue(before + 1);
                    });
        }
    }

    @Test
    void jdkProxyRejectsAnnotatedMethodsMissingFromItsInterfaces() {
        runner.withPropertyValues("spring.aop.proxy-target-class=false")
                .withUserConfiguration(Unexposed.class).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("not exposed by a proxy interface");
                });
    }

    @Test
    void skippedMethodDoesNotStartATransaction() {
        runner.withUserConfiguration(Transactions.class)
                .withBean(TransactionalAction.class, () -> new TransactionalAction(executions)).run(context -> {
                    assertThat(context).hasNotFailed();
                    TransactionalAction action = context.getBean(TransactionalAction.class);
                    CountingTransactions transactions = context.getBean(CountingTransactions.class);
                    action.execute();
                    assertThat(transactions.begins).hasValue(0);
                    client.enabled = true;
                    action.execute();
                    assertThat(transactions.begins).hasValue(1);
                    assertThat(executions).hasValue(1);
                });
    }

    @Test
    void asyncVoidMethodIsCheckedInWorkerBeforeItsBodyRuns() {
        runner.withUserConfiguration(AsyncConfiguration.class)
                .withBean(AsyncAction.class, () -> new AsyncAction(executions)).run(context -> {
                    assertThat(context).hasNotFailed();
                    AsyncAction action = context.getBean(AsyncAction.class);
                    action.execute();
                    // A barrier queued on the same single-thread executor observes completion of the guarded call.
                    CountDownLatch barrier = new CountDownLatch(1);
                    context.getBean(java.util.concurrent.ExecutorService.class).execute(barrier::countDown);
                    assertThat(barrier.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(executions).hasValue(0);
                    assertThat(client.lastThread).isNotSameAs(Thread.currentThread());
                    client.enabled = true;
                    action.execute();
                    CountDownLatch second = new CountDownLatch(1);
                    context.getBean(java.util.concurrent.ExecutorService.class).execute(second::countDown);
                    assertThat(second.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(executions).hasValue(1);
                });
    }

    public static class Actions {
        static final RuntimeException FAILURE = new IllegalStateException("business failure");
        private final AtomicInteger executions;
        Actions(AtomicInteger executions) { this.executions = executions; }

        @BooleanFeatureToggle(name = "flag")
        public void booleanAction(String argument) {
            assertThat(argument).isEqualTo("argument");
            executions.incrementAndGet();
        }
        @BooleanFeatureToggle(name = "flag", hasValue = false)
        public void inverse() { executions.incrementAndGet(); }
        @EnumFeatureToggle(name = "provider", hasValue = "STRIPE")
        public void enumAction() { executions.incrementAndGet(); }
        @VectorFeatureToggle(name = "channels", element = "EMAIL")
        public void vectorAction() { executions.incrementAndGet(); }
        @VectorFeatureToggle(name = "channels", element = "EMAIL", hasValue = false)
        public void inverseVector() { executions.incrementAndGet(); }
        @BooleanFeatureToggle(name = "flag", group = "")
        public void global() { executions.incrementAndGet(); }
        @BooleanFeatureToggle(name = "flag", group = "other")
        public void other() { executions.incrementAndGet(); }
        @BooleanFeatureToggle(name = "flag")
        public void throwing() { throw FAILURE; }
    }

    public interface Contract {
        @BooleanFeatureToggle(name = "flag")
        void onInterface();
        void onImplementation();
    }
    public static class ContractImpl implements Contract {
        private final AtomicInteger executions;
        ContractImpl(AtomicInteger executions) { this.executions = executions; }
        public void onInterface() { executions.incrementAndGet(); }
        @BooleanFeatureToggle(name = "flag")
        public void onImplementation() { executions.incrementAndGet(); }
    }
    public interface GenericContract<T> {
        @BooleanFeatureToggle(name = "flag")
        void apply(T value);
    }
    public static class GenericImpl implements GenericContract<String> {
        private final AtomicInteger executions;
        GenericImpl(AtomicInteger executions) { this.executions = executions; }
        public void apply(String value) { executions.incrementAndGet(); }
    }
    public static class Returning {
        @BooleanFeatureToggle(name = "flag") public String execute() { return "bad"; }
    }
    public interface InheritedContract {
        @BooleanFeatureToggle(name = "flag") void execute();
    }
    public static class BaseAction {
        private final AtomicInteger executions;
        BaseAction(AtomicInteger executions) { this.executions = executions; }
        public void execute() { executions.incrementAndGet(); }
    }
    public static class InheritedAction extends BaseAction implements InheritedContract {
        InheritedAction(AtomicInteger executions) { super(executions); }
    }
    public static class PrivateMethod {
        @BooleanFeatureToggle(name = "flag") private void execute() { }
    }
    public static class StaticMethod {
        @BooleanFeatureToggle(name = "flag") public static void execute() { }
    }
    public static class FinalMethod {
        @BooleanFeatureToggle(name = "flag") public final void execute() { }
    }
    public static final class FinalClass {
        @BooleanFeatureToggle(name = "flag") public void execute() { }
    }
    public static class BlankName {
        @BooleanFeatureToggle(name = " ") public void execute() { }
    }
    public static class BlankElement {
        @VectorFeatureToggle(name = "flag", element = " ") public void execute() { }
    }
    public static class BlankGroup {
        @EnumFeatureToggle(name = "flag", hasValue = "ON", group = " ") public void execute() { }
    }
    public static class Multiple {
        @BooleanFeatureToggle(name = "flag")
        @EnumFeatureToggle(name = "flag", hasValue = "ON") public void execute() { }
    }
    public static class ConflictingImpl implements Contract {
        @EnumFeatureToggle(name = "flag", hasValue = "ON") public void onInterface() { }
        public void onImplementation() { }
    }
    public static class Unexposed implements Runnable {
        public void run() { }
        @BooleanFeatureToggle(name = "flag") public void execute() { }
    }
    @Configuration
    @EnableTransactionManagement
    public static class Transactions {
        @Bean public CountingTransactions transactionManager() { return new CountingTransactions(); }
    }
    public static class CountingTransactions extends AbstractPlatformTransactionManager {
        final AtomicInteger begins = new AtomicInteger();
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object transaction, TransactionDefinition definition) { begins.incrementAndGet(); }
        protected void doCommit(DefaultTransactionStatus status) { }
        protected void doRollback(DefaultTransactionStatus status) { }
    }
    public static class TransactionalAction {
        private final AtomicInteger executions;
        TransactionalAction(AtomicInteger executions) { this.executions = executions; }
        @Transactional
        @BooleanFeatureToggle(name = "flag")
        public void execute() { executions.incrementAndGet(); }
    }
    @Configuration
    @EnableAsync
    public static class AsyncConfiguration {
        @Bean(destroyMethod = "shutdownNow")
        public java.util.concurrent.ExecutorService taskExecutor() {
            return java.util.concurrent.Executors.newSingleThreadExecutor();
        }
    }
    public static class AsyncAction {
        private final AtomicInteger executions;
        AsyncAction(AtomicInteger executions) { this.executions = executions; }
        @Async
        @BooleanFeatureToggle(name = "flag")
        public void execute() { executions.incrementAndGet(); }
    }
    private static class StubClient implements FeaturifyClient {
        boolean enabled;
        String enumValue = "stripe";
        RuntimeException error;
        Thread lastThread;
        final List<String> requests = new ArrayList<>();
        public BooleanFeatureResponse getBooleanFeature(String key, String group) {
            lastThread = Thread.currentThread();
            requests.add("boolean:" + key + ":" + group);
            if (error != null) throw error;
            return BooleanFeatureResponse.newBuilder().setValue(enabled).build();
        }
        public EnumFeatureResponse getEnumFeature(String key, String group) {
            requests.add("enum:" + key + ":" + group);
            return EnumFeatureResponse.newBuilder().setValue(enumValue).build();
        }
        public BooleanFeatureResponse getVectorFeature(String key, String element, String group) {
            requests.add("vector:" + key + ":" + element + ":" + group);
            return BooleanFeatureResponse.newBuilder().setValue(enabled).build();
        }
    }
}
