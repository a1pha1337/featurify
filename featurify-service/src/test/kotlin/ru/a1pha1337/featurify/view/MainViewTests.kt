package ru.a1pha1337.featurify.view

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.checkbox.CheckboxGroup
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.server.VaadinSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.dto.AdminFeatureResponse
import ru.a1pha1337.featurify.dto.CreateAccessTokenRequest
import ru.a1pha1337.featurify.dto.CreateFeatureGroupRequest
import ru.a1pha1337.featurify.dto.CreateFeatureRequest
import ru.a1pha1337.featurify.dto.CreatedAccessTokenResponse
import ru.a1pha1337.featurify.dto.FeatureGroupResponse
import ru.a1pha1337.featurify.dto.NamespaceResponse
import ru.a1pha1337.featurify.dto.PatchFeatureRequest
import ru.a1pha1337.featurify.service.FeatureToggleService
import java.time.Instant
import java.util.UUID

class MainViewTests {
    private val accessTokens = mockk<ru.a1pha1337.featurify.service.AccessTokenService>(relaxed = true)
    private val service = mockk<FeatureToggleService>(relaxed = true)
    private val factory = Validation.buildDefaultValidatorFactory()
    private val ui = UI()
    private val now = Instant.parse("2026-09-11T12:00:00Z")
    private val namespace = NamespaceResponse(UUID.randomUUID(), "blue", "Blue", true, now, now, true)
    private val source = FeatureGroupResponse(UUID.randomUUID(), "source", "Source", 1, now, now)
    private val target = source.copy(id = UUID.randomUUID(), key = "target", displayName = "Target")
    private val groups = mutableListOf(source, target)
    private var feature =
        AdminFeatureResponse(
            "source",
            "checkout.enabled",
            FeatureType.BOOLEAN,
            true,
            null,
            "Checkout flag",
            7,
            now,
            now,
        )
    private lateinit var view: MainView

    @BeforeEach
    fun setup() {
        val session = mockk<VaadinSession>(relaxed = true)
        every { session.hasLock() } returns true
        every { session.locale } returns java.util.Locale.ENGLISH
        VaadinSession.setCurrent(session)
        ui.internals.session = session
        UI.setCurrent(ui)
        every { service.listNamespaces() } returns listOf(namespace)
        every { service.listGroups("blue") } answers { groups.toList() }
        every { service.listForAdmin(eq("blue"), any(), any(), any(), any()) } answers {
            val page = arg<Pageable>(1)
            val group = arg<String?>(3)
            val global = arg<Boolean>(4)
            val visible = if (global) feature.group == null else group == null || feature.group == group
            PageImpl(if (visible) listOf(feature) else emptyList(), page, if (visible) 1 else 0)
        }
        view = MainView(service, factory.validator, accessTokens)
        ui.add(view)
    }

    @AfterEach
    fun cleanup() {
        UI.setCurrent(null)
        VaadinSession.setCurrent(null)
        factory.close()
    }

    @Test
    fun `namespace creation offers no default namespace control`() {
        button(view, "New namespace").click()
        val dialog = dialog()

        assertThat(components(dialog).filterIsInstance<com.vaadin.flow.component.checkbox.Checkbox>().isEmpty()).isTrue()
        assertThat(
            components(dialog).filterIsInstance<TextField>().map { it.label }.toSet(),
        ).isEqualTo(setOf("Namespace key", "Display name"))
    }

    @Test
    fun `create group validates fields and selects the persisted group`() {
        button(view, "New group").click()
        val dialog = dialog()
        button(dialog, "Create group").click()
        assertThat(field(dialog, "Group key").isInvalid).isTrue()
        verify(exactly = 0) { service.createGroup(any(), any()) }
        val created = source.copy(id = UUID.randomUUID(), key = "payments", displayName = "Payments")
        every { service.createGroup("blue", CreateFeatureGroupRequest("payments", "Payments")) } answers {
            groups.add(created)
            created
        }
        field(dialog, "Group key").value = " payments "
        field(dialog, "Display name").value = " Payments "

        button(dialog, "Create group").click()

        verify(exactly = 1) { service.createGroup("blue", CreateFeatureGroupRequest("payments", "Payments")) }
        assertThat(dialog.isOpened).isFalse()
        assertThat(selectedLabel(combo(view, "Group"))).isEqualTo("Payments (payments)")
        assertThat(button(view, "New group").isEnabled).isTrue()
    }

    @Test
    fun `move uses selected feature source and version and follows target filter`() {
        choose(combo(view, "Group"), "Source (source)")
        grid().select(feature)
        assertThat(button(view, "Edit").isEnabled).isTrue()
        every {
            service.editFeature(
                "blue",
                feature.key,
                "source",
                "target",
                PatchFeatureRequest(7, description = "Checkout flag", booleanValue = true),
            )
        } answers {
            feature = feature.copy(group = "target", version = 8)
            feature
        }
        button(view, "Edit").click()
        val dialog = dialog()
        choose(combo(dialog, "Group"), "Target (target)")

        button(dialog, "Save").click()

        verify(exactly = 1) {
            service.editFeature(
                "blue",
                feature.key,
                "source",
                "target",
                PatchFeatureRequest(7, description = "Checkout flag", booleanValue = true),
            )
        }
        assertThat(dialog.isOpened).isFalse()
        assertThat(selectedLabel(combo(view, "Group"))).isEqualTo("Target (target)")
        assertThat(button(view, "Edit").isEnabled).isFalse()
    }

    @Test
    fun `move to Global is explicit`() {
        grid().select(feature)
        every {
            service.editFeature(
                "blue",
                feature.key,
                "source",
                null,
                PatchFeatureRequest(7, description = "Checkout flag", booleanValue = true),
            )
        } answers {
            feature = feature.copy(group = null, version = 8)
            feature
        }
        button(view, "Edit").click()
        val dialog = dialog()
        val picker = combo(dialog, "Group")
        assertThat(picker.listDataView.itemCount).isEqualTo(3)
        choose(picker, "Global")

        button(dialog, "Save").click()

        verify(exactly = 1) {
            service.editFeature(
                "blue",
                feature.key,
                "source",
                null,
                PatchFeatureRequest(7, description = "Checkout flag", booleanValue = true),
            )
        }
        assertThat(selectedLabel(combo(view, "Group"))).isEqualTo("All groups")
    }

    @Test
    fun `switching namespace clears selection and keeps group creation enabled`() {
        val other =
            namespace.copy(id = UUID.randomUUID(), key = "other", displayName = "Other", defaultNamespace = false)
        every { service.listGroups("other") } returns emptyList()
        every { service.listForAdmin(eq("other"), any(), any(), any(), any()) } returns PageImpl(emptyList())
        grid().select(feature)
        val namespaces = combo(view, "Namespace")
        namespaces.setItems(listOf(namespace, other))
        namespaces.value = other

        assertThat(button(view, "New group").isEnabled).isTrue()
        assertThat(button(view, "Edit").isEnabled).isFalse()
        assertThat(selectedLabel(combo(view, "Group"))).isEqualTo("All groups")
        assertThat(combo(view, "Group").listDataView.itemCount).isEqualTo(2)
    }

    @Test
    fun `failed move keeps dialog open for correction`() {
        grid().select(feature)
        every {
            service.editFeature(
                "blue",
                feature.key,
                "source",
                "target",
                PatchFeatureRequest(7, description = "Checkout flag", booleanValue = true),
            )
        } throws
            ru.a1pha1337.featurify.service
                .ConflictException("Target already contains this key")
        button(view, "Edit").click()
        val dialog = dialog()
        choose(combo(dialog, "Group"), "Target (target)")

        button(dialog, "Save").click()

        assertThat(dialog.isOpened).isTrue()
        assertThat(selectedLabel(combo(dialog, "Group"))).isEqualTo("Target (target)")
    }

    @Test
    fun `feature deletion requires confirmation and uses selected version`() {
        grid().select(feature)
        button(view, "Delete").click()
        val cancelled = dialog()
        button(cancelled, "Cancel").click()
        verify(exactly = 0) { service.deleteFeature("blue", feature.key, feature.group, feature.version) }
        button(view, "Delete").click()
        val confirmed = dialog()
        button(confirmed, "Delete").click()
        verify(exactly = 1) { service.deleteFeature("blue", feature.key, feature.group, feature.version) }
        assertThat(confirmed.isOpened).isFalse()
        assertThat(button(view, "Delete").isEnabled).isFalse()
    }

    @Test
    fun `default namespace deletion is disabled`() {
        assertThat(button(view, "Delete namespace").isEnabled).isFalse()
    }

    @Test
    fun `namespace deletion confirms cascade and selects default afterwards`() {
        val other =
            namespace.copy(id = UUID.randomUUID(), key = "other", displayName = "Other", defaultNamespace = false)
        every { service.listGroups("other") } returns emptyList()
        every { service.listForAdmin(eq("other"), any(), any(), any(), any()) } returns PageImpl(emptyList())
        val namespaces = combo(view, "Namespace")
        namespaces.setItems(listOf(namespace, other))
        namespaces.value = other
        assertThat(button(view, "Delete namespace").isEnabled).isTrue()
        button(view, "Delete namespace").click()
        val confirmation = dialog()
        verify(exactly = 0) { service.deleteNamespace("other") }
        button(confirmation, "Delete namespace").click()
        verify(exactly = 1) { service.deleteNamespace("other") }
        assertThat(confirmation.isOpened).isFalse()
        assertThat(namespaces.value).isEqualTo(namespace)
        assertThat(button(view, "Delete namespace").isEnabled).isFalse()
    }

    @Test
    fun `toolbar has no move action and edit contains group picker`() {
        assertThat(components(view).filterIsInstance<Button>().any { it.text == "Move to group" }).isFalse()
        grid().select(feature)
        button(view, "Edit").click()
        assertThat(selectedLabel(combo(dialog(), "Group"))).isEqualTo("Source (source)")
    }

    @Test
    fun `managed feature hides structural edits but initial only keeps value controls`() {
        feature = feature.copy(managed = true, valueManaged = false)
        grid().asSingleSelect().value = feature
        assertThat(button(view, "Delete").isEnabled).isFalse()
        assertThat(button(view, "Edit").isEnabled).isTrue()
        assertThat(valueEditor()).isInstanceOf(Button::class.java)
        button(view, "Edit").click()
        assertThat(combo(dialog(), "Group").isEnabled).isFalse()
    }

    @Test
    fun `managed values are read only and exclusive namespace disables creation`() {
        feature = feature.copy(managed = true, valueManaged = true)
        every { service.listNamespaces() } returns listOf(namespace.copy(managedBy = "cluster/app/checkout", exclusive = true))
        ui.remove(view)
        view = MainView(service, factory.validator, accessTokens)
        ui.add(view)
        grid().asSingleSelect().value = feature
        assertThat(button(view, "New feature").isEnabled).isFalse()
        assertThat(button(view, "New group").isEnabled).isFalse()
        assertThat(button(view, "Edit").isEnabled).isFalse()
        assertThat(valueEditor()).isNotInstanceOf(Button::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun valueEditor(): Component =
        (grid().getColumnByKey("value").renderer as com.vaadin.flow.data.renderer.ComponentRenderer<Component, AdminFeatureResponse>)
            .createComponent(feature)

    @Test
    fun `inline switch saves value and uses new version for subsequent changes`() {
        val toggle = valueEditor() as Button
        assertThat(toggle.element.getAttribute("aria-checked")).isEqualTo("true")
        every { service.patchFeature("blue", feature.key, "source", PatchFeatureRequest(7, booleanValue = false)) } returns
            feature.copy(value = false, version = 8)
        every { service.patchFeature("blue", feature.key, "source", PatchFeatureRequest(8, booleanValue = true)) } returns
            feature.copy(value = true, version = 9)
        toggle.click()
        assertThat(toggle.element.getAttribute("aria-checked")).isEqualTo("false")
        toggle.click()
        verify(exactly = 1) { service.patchFeature("blue", feature.key, "source", PatchFeatureRequest(8, booleanValue = true)) }
        assertThat(toggle.element.getAttribute("aria-checked")).isEqualTo("true")
    }

    @Test
    fun `failed inline switch leaves persisted value displayed`() {
        val toggle = valueEditor() as Button
        every { service.patchFeature("blue", feature.key, "source", PatchFeatureRequest(7, booleanValue = false)) } throws
            ru.a1pha1337.featurify.service
                .ConflictException("Stale version")
        toggle.click()
        assertThat(toggle.element.getAttribute("aria-checked")).isEqualTo("true")
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `inline enum saves client changes and rolls back failed selection`() {
        feature = feature.copy(type = FeatureType.ENUM, value = "a", enumOptions = listOf("a", "b"))
        val picker = valueEditor() as ComboBox<String>
        assertThat(picker.listDataView.items.toList()).isEqualTo(listOf("a", "b"))
        every { service.patchFeature("blue", feature.key, "source", PatchFeatureRequest(7, enumValue = "b")) } returns
            feature.copy(value = "b", version = 8)
        picker.value = "b"
        com.vaadin.flow.component.ComponentUtil.fireEvent(
            picker,
            com.vaadin.flow.component.AbstractField
                .ComponentValueChangeEvent(picker, picker, "a", true),
        )
        assertThat(picker.value).isEqualTo("b")
        every { service.patchFeature("blue", feature.key, "source", PatchFeatureRequest(8, enumValue = "a")) } throws
            ru.a1pha1337.featurify.service
                .ConflictException("Stale version")
        picker.value = "a"
        com.vaadin.flow.component.ComponentUtil.fireEvent(
            picker,
            com.vaadin.flow.component.AbstractField
                .ComponentValueChangeEvent(picker, picker, "b", true),
        )
        assertThat(picker.value).isEqualTo("b")
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `enum tags add trim deduplicate remove and preserve current value`() {
        button(view, "New feature").click()
        val dialog = dialog()
        combo(dialog, "Type").value = FeatureType.ENUM
        field(dialog, "Key").value = "language"
        val tags =
            components(dialog).filterIsInstance<com.vaadin.flow.component.combobox.MultiSelectComboBox<*>>().single()
                as com.vaadin.flow.component.combobox.MultiSelectComboBox<String>

        fun add(text: String) =
            com.vaadin.flow.component.ComponentUtil.fireEvent(
                tags,
                com.vaadin.flow.component.combobox.ComboBoxBase
                    .CustomValueSetEvent(tags, true, text),
            )
        add("  English  ")
        add("French")
        combo(dialog, "Current enum value").value = "French"
        add("Italian")
        add("French")
        assertThat(combo(dialog, "Current enum value").value).isEqualTo("French")
        assertThat(tags.value.size).isEqualTo(3)
        tags.value = linkedSetOf("English", "Italian")
        assertThat(combo(dialog, "Current enum value").value).isEqualTo("English")
        add(" ")
        assertThat(tags.isInvalid).isTrue()
        add("Italian")
        button(dialog, "Create").click()
        verify(exactly = 1) {
            service.createFeature(
                "blue",
                CreateFeatureRequest(
                    key = "language",
                    type = FeatureType.ENUM,
                    enumValue = "English",
                    enumOptions = listOf("English", "Italian"),
                ),
            )
        }
    }

    @Test
    fun `access token is generated for selected namespace and cleared on dialog close`() {
        every { accessTokens.list("blue") } returns emptyList()
        button(view, "Access tokens").click()
        val dialog = dialog()
        button(dialog, "Generate token").click()
        assertThat(field(dialog, "Token name").isInvalid).isTrue()
        val id = UUID.randomUUID()
        every { accessTokens.create("blue", CreateAccessTokenRequest("backend")) } returns
            CreatedAccessTokenResponse(id, "backend", now, "test-only-secret")
        field(dialog, "Token name").value = "backend"
        button(dialog, "Generate token").click()
        verify(exactly = 1) { accessTokens.create("blue", CreateAccessTokenRequest("backend")) }
        val secret = components(dialog).filterIsInstance<com.vaadin.flow.component.textfield.TextArea>().single()
        assertThat(secret.isVisible).isTrue()
        assertThat(secret.isReadOnly).isTrue()
        assertThat(secret.value).isEqualTo("test-only-secret")
        button(dialog, "Close").click()
        assertThat(secret.value).isEqualTo("")
    }

    private fun components(root: Component): List<Component> =
        listOf(root) +
            root.element.children.toList().flatMap { element ->
                element.component.map { components(it) }.orElse(emptyList())
            }

    private fun button(
        root: Component,
        text: String,
    ): Button {
        val candidates =
            components(root) +
                if (root is Dialog) {
                    root.footer.element.children
                        .toList()
                        .mapNotNull { it.component.orElse(null) }
                        .flatMap(::components)
                } else {
                    emptyList()
                }
        return candidates.filterIsInstance<Button>().distinct().single { it.text == text }
    }

    private fun field(
        root: Component,
        label: String,
    ) = components(root).filterIsInstance<TextField>().single { it.label == label }

    private fun dialog(): Dialog {
        ui.internals.stateTree.runExecutionsBeforeClientResponse()
        return components(ui).filterIsInstance<Dialog>().single { it.isOpened }
    }

    @Suppress("UNCHECKED_CAST")
    private fun grid() = components(view).filterIsInstance<Grid<*>>().single() as Grid<AdminFeatureResponse>

    @Suppress("UNCHECKED_CAST")
    private fun combo(
        root: Component,
        label: String,
    ) = components(root).filterIsInstance<ComboBox<*>>().single { it.label == label } as ComboBox<Any>

    private fun selectedLabel(combo: ComboBox<Any>) = combo.itemLabelGenerator.apply(combo.value)

    private fun choose(
        combo: ComboBox<Any>,
        label: String,
    ) {
        combo.value =
            combo.listDataView.items
                .toList()
                .single { combo.itemLabelGenerator.apply(it) == label }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `vector creation selects independent enabled elements`() {
        button(view, "New feature").click()
        val dialog = dialog()
        combo(dialog, "Type").value = FeatureType.VECTOR
        field(dialog, "Key").value = "feat"
        val elements =
            components(dialog).filterIsInstance<com.vaadin.flow.component.combobox.MultiSelectComboBox<*>>().single()
                as com.vaadin.flow.component.combobox.MultiSelectComboBox<String>
        val enabled = components(dialog).filterIsInstance<CheckboxGroup<*>>().single() as CheckboxGroup<String>
        button(dialog, "Create").click()
        assertThat(elements.isInvalid).isTrue()
        listOf("CAT", "DOG", "SHIP").forEach { name ->
            com.vaadin.flow.component.ComponentUtil.fireEvent(
                elements,
                com.vaadin.flow.component.combobox.ComboBoxBase
                    .CustomValueSetEvent(elements, true, name),
            )
        }
        enabled.value = setOf("CAT", "DOG")
        button(dialog, "Create").click()
        verify(exactly = 1) {
            service.createFeature(
                "blue",
                CreateFeatureRequest(
                    "feat",
                    FeatureType.VECTOR,
                    vectorValues = mapOf("CAT" to true, "DOG" to true, "SHIP" to false),
                ),
            )
        }
    }

    @Test
    fun `inline vector element uses latest version and preserves displayed state on failure`() {
        feature = feature.copy(type = FeatureType.VECTOR, value = mapOf("CAT" to true, "DOG" to false, "SHIP" to false))
        val editor = valueEditor()
        val dog = button(editor, "DOG: Disabled")
        every { service.patchFeature("blue", feature.key, "source", PatchFeatureRequest(7, vectorValues = mapOf("DOG" to true))) } returns
            feature.copy(version = 8, value = mapOf("CAT" to true, "DOG" to true, "SHIP" to false))
        dog.click()
        assertThat(dog.element.getAttribute("aria-checked")).isEqualTo("true")
        assertThat(button(editor, "SHIP: Disabled").element.getAttribute("aria-checked")).isEqualTo("false")
        every { service.patchFeature("blue", feature.key, "source", PatchFeatureRequest(8, vectorValues = mapOf("DOG" to false))) } throws
            ru.a1pha1337.featurify.service
                .ConflictException("Stale version")
        dog.click()
        assertThat(dog.element.getAttribute("aria-checked")).isEqualTo("true")
        verify(
            exactly = 1,
        ) { service.patchFeature("blue", feature.key, "source", PatchFeatureRequest(8, vectorValues = mapOf("DOG" to false))) }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `vector edit saves selected enabled elements`() {
        feature = feature.copy(type = FeatureType.VECTOR, value = mapOf("CAT" to true, "DOG" to false, "SHIP" to false))
        grid().select(feature)
        button(view, "Edit").click()
        val dialog = dialog()
        val enabled = components(dialog).filterIsInstance<CheckboxGroup<*>>().single() as CheckboxGroup<String>
        assertThat(enabled.value).containsExactly("CAT")
        enabled.value = setOf("CAT", "DOG")
        button(dialog, "Save").click()
        verify(exactly = 1) {
            service.editFeature(
                "blue",
                feature.key,
                "source",
                "source",
                PatchFeatureRequest(7, description = "Checkout flag", vectorValues = mapOf("CAT" to true, "DOG" to true, "SHIP" to false)),
            )
        }
    }
}
