package ru.a1pha1337.featurify.client

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import ru.a1pha1337.featurify.client.annotation.BooleanFeatureToggle
import ru.a1pha1337.featurify.client.autoconfigure.FeaturifyGrpcAutoConfiguration
import java.util.concurrent.atomic.AtomicInteger

class FeatureToggleKotlinTests {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FeaturifyGrpcAutoConfiguration::class.java))
            .withUserConfiguration(CustomClientConfiguration::class.java)

    @Test
    fun `ordinary Unit methods execute or skip according to their condition`() {
        val executions = AtomicInteger()
        runner.withBean(UnitActions::class.java, { UnitActions(executions) }).run { context ->
            assertThat(context).hasNotFailed()
            val actions = context.getBean(UnitActions::class.java)
            actions.enabled()
            actions.disabled()
            assertThat(executions.get()).isEqualTo(1)
        }
    }

    @Test
    fun `suspend methods fail at bean initialization`() {
        runner.withUserConfiguration(SuspendAction::class.java).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("must return void")
        }
    }

    open class UnitActions(
        private val executions: AtomicInteger,
    ) {
        @BooleanFeatureToggle(name = "flag")
        open fun enabled() {
            executions.incrementAndGet()
        }

        @BooleanFeatureToggle(name = "flag", hasValue = false)
        open fun disabled() {
            executions.incrementAndGet()
        }
    }

    open class SuspendAction {
        @BooleanFeatureToggle(name = "flag")
        open suspend fun execute() = Unit
    }
}
