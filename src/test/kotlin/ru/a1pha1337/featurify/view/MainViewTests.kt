package ru.a1pha1337.featurify.view

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.server.VaadinSession
import jakarta.validation.Validation
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.dto.*
import ru.a1pha1337.featurify.service.FeatureToggleService
import java.time.Instant
import java.util.UUID

class MainViewTests {
    private val service = mock(FeatureToggleService::class.java)
    private val factory = Validation.buildDefaultValidatorFactory()
    private val ui = UI()
    private val now = Instant.parse("2026-09-11T12:00:00Z")
    private val tenant = TenantResponse(UUID.randomUUID(), "blue", "Blue", true, now, now, true)
    private val source = FeatureGroupResponse(UUID.randomUUID(), "source", "Source", 1, now, now)
    private val target = source.copy(id = UUID.randomUUID(), key = "target", displayName = "Target")
    private val groups = mutableListOf(source, target)
    private var feature = AdminFeatureResponse("source", "checkout.enabled", FeatureType.BOOLEAN, true,
        null, "Checkout flag", 7, now, now)
    private lateinit var view: MainView

    @BeforeEach
    fun setup() {
        val session = mock(VaadinSession::class.java, RETURNS_DEEP_STUBS)
        `when`(session.hasLock()).thenReturn(true)
        `when`(session.locale).thenReturn(java.util.Locale.ENGLISH)
        VaadinSession.setCurrent(session)
        ui.internals.session = session
        UI.setCurrent(ui)
        `when`(service.listTenants()).thenReturn(listOf(tenant))
        `when`(service.listGroups("blue")).thenAnswer { groups.toList() }
        // Default answer avoids nullable Mockito matchers crossing Kotlin's non-null parameters.
        doAnswer { invocation ->
            val page = invocation.getArgument<Pageable>(1)
            val group = invocation.getArgument<String?>(3)
            val global = invocation.getArgument<Boolean>(4)
            val visible = if (global) feature.group == null else group == null || feature.group == group
            PageImpl(if (visible) listOf(feature) else emptyList(), page, if (visible) 1 else 0)
        }.`when`(service).listForAdmin(eq("blue"), anyPage(), nullableQuery(), nullableQuery(), anyBoolean())
        view = MainView(service, factory.validator)
        ui.add(view)
    }

    @AfterEach
    fun cleanup() {
        UI.setCurrent(null)
        VaadinSession.setCurrent(null)
        factory.close()
    }

    @Test
    fun `tenant creation offers no default tenant control`() {
        button(view, "New tenant").click()
        val dialog = dialog()

        assertTrue(components(dialog).filterIsInstance<com.vaadin.flow.component.checkbox.Checkbox>().isEmpty())
        assertEquals(setOf("Tenant key", "Display name"),
            components(dialog).filterIsInstance<TextField>().map { it.label }.toSet())
    }

    @Test
    fun `create group validates fields and selects the persisted group`() {
        button(view, "New group").click()
        val dialog = dialog()
        button(dialog, "Create group").click()
        assertTrue(field(dialog, "Group key").isInvalid)
        verify(service, never()).createGroup(anyString(), anyRequest())
        val created = source.copy(id = UUID.randomUUID(), key = "payments", displayName = "Payments")
        `when`(service.createGroup("blue", CreateFeatureGroupRequest("payments", "Payments"))).thenAnswer {
            groups.add(created)
            created
        }
        field(dialog, "Group key").value = " payments "
        field(dialog, "Display name").value = " Payments "

        button(dialog, "Create group").click()

        verify(service).createGroup("blue", CreateFeatureGroupRequest("payments", "Payments"))
        assertFalse(dialog.isOpened)
        assertEquals("Payments (payments)", selectedLabel(combo(view, "Group")))
        assertTrue(button(view, "New group").isEnabled)
    }

    @Test
    fun `move uses selected feature source and version and follows target filter`() {
        choose(combo(view, "Group"), "Source (source)")
        grid().select(feature)
        assertTrue(button(view, "Move to group").isEnabled)
        `when`(service.moveFeature("blue", feature.key, "source", MoveFeatureRequest(7, "target"))).thenAnswer {
            feature = feature.copy(group = "target", version = 8)
            feature
        }
        button(view, "Move to group").click()
        val dialog = dialog()
        assertFalse(button(dialog, "Move feature").isEnabled)
        choose(combo(dialog, "Target group"), "Target (target)")

        button(dialog, "Move feature").click()

        verify(service).moveFeature("blue", feature.key, "source", MoveFeatureRequest(7, "target"))
        assertFalse(dialog.isOpened)
        assertEquals("Target (target)", selectedLabel(combo(view, "Group")))
        assertFalse(button(view, "Move to group").isEnabled)
    }

    @Test
    fun `move to Global is explicit`() {
        grid().select(feature)
        `when`(service.moveFeature("blue", feature.key, "source", MoveFeatureRequest(7, null))).thenAnswer {
            feature = feature.copy(group = null, version = 8)
            feature
        }
        button(view, "Move to group").click()
        val dialog = dialog()
        val picker = combo(dialog, "Target group")
        assertEquals(3, picker.listDataView.itemCount)
        choose(picker, "Global")

        button(dialog, "Move feature").click()

        verify(service).moveFeature("blue", feature.key, "source", MoveFeatureRequest(7, null))
        assertEquals("All groups", selectedLabel(combo(view, "Group")))
    }

    @Test
    fun `switching tenant clears selection and keeps group creation enabled`() {
        val other = tenant.copy(id = UUID.randomUUID(), key = "other", displayName = "Other", defaultTenant = false)
        `when`(service.listGroups("other")).thenReturn(emptyList())
        `when`(service.listForAdmin(eq("other"), anyPage(), nullableQuery(), nullableQuery(), anyBoolean()))
            .thenReturn(PageImpl(emptyList()))
        grid().select(feature)
        val tenants = combo(view, "Tenant")
        tenants.setItems(listOf(tenant, other))
        tenants.value = other

        assertTrue(button(view, "New group").isEnabled)
        assertFalse(button(view, "Move to group").isEnabled)
        assertEquals("All groups", selectedLabel(combo(view, "Group")))
        assertEquals(2, combo(view, "Group").listDataView.itemCount)
    }

    @Test
    fun `failed move keeps dialog open for correction`() {
        grid().select(feature)
        `when`(service.moveFeature("blue", feature.key, "source", MoveFeatureRequest(7, "target")))
            .thenThrow(ru.a1pha1337.featurify.service.ConflictException("Target already contains this key"))
        button(view, "Move to group").click()
        val dialog = dialog()
        choose(combo(dialog, "Target group"), "Target (target)")

        button(dialog, "Move feature").click()

        assertTrue(dialog.isOpened)
        assertEquals("Target (target)", selectedLabel(combo(dialog, "Target group")))
    }

    @Test
    fun `feature deletion requires confirmation and uses selected version`() {
        grid().select(feature)
        button(view, "Delete").click()
        val cancelled = dialog()
        button(cancelled, "Cancel").click()
        verify(service, never()).deleteFeature("blue", feature.key, feature.group, feature.version)
        button(view, "Delete").click()
        val confirmed = dialog()
        button(confirmed, "Delete").click()
        verify(service).deleteFeature("blue", feature.key, feature.group, feature.version)
        assertFalse(confirmed.isOpened)
        assertFalse(button(view, "Delete").isEnabled)
    }

    @Test
    fun `default tenant deletion is disabled`() {
        assertFalse(button(view, "Delete tenant").isEnabled)
    }

    @Test
    fun `tenant deletion confirms cascade and selects default afterwards`() {
        val other = tenant.copy(id = UUID.randomUUID(), key = "other", displayName = "Other", defaultTenant = false)
        `when`(service.listGroups("other")).thenReturn(emptyList())
        `when`(service.listForAdmin(eq("other"), anyPage(), nullableQuery(), nullableQuery(), anyBoolean()))
            .thenReturn(PageImpl(emptyList()))
        val tenants = combo(view, "Tenant")
        tenants.setItems(listOf(tenant, other))
        tenants.value = other
        assertTrue(button(view, "Delete tenant").isEnabled)
        button(view, "Delete tenant").click()
        val confirmation = dialog()
        verify(service, never()).deleteTenant("other")
        button(confirmation, "Delete tenant").click()
        verify(service).deleteTenant("other")
        assertFalse(confirmation.isOpened)
        assertEquals(tenant, tenants.value)
        assertFalse(button(view, "Delete tenant").isEnabled)
    }

    private fun components(root: Component): List<Component> =
        listOf(root) + root.element.children.toList().flatMap { element ->
            element.component.map { components(it) }.orElse(emptyList())
        }
    private fun button(root: Component, text: String): Button {
        val candidates = components(root) + if (root is Dialog) {
            root.footer.element.children.toList().mapNotNull { it.component.orElse(null) }.flatMap(::components)
        } else emptyList()
        return candidates.filterIsInstance<Button>().distinct().single { it.text == text }
    }
    private fun field(root: Component, label: String) = components(root).filterIsInstance<TextField>().single { it.label == label }
    private fun dialog(): Dialog {
        ui.internals.stateTree.runExecutionsBeforeClientResponse()
        return components(ui).filterIsInstance<Dialog>().single { it.isOpened }
    }
    @Suppress("UNCHECKED_CAST")
    private fun grid() = components(view).filterIsInstance<Grid<*>>().single() as Grid<AdminFeatureResponse>
    @Suppress("UNCHECKED_CAST")
    private fun combo(root: Component, label: String) =
        components(root).filterIsInstance<ComboBox<*>>().single { it.label == label } as ComboBox<Any>
    private fun selectedLabel(combo: ComboBox<Any>) = combo.itemLabelGenerator.apply(combo.value)
    private fun choose(combo: ComboBox<Any>, label: String) {
        combo.value = combo.listDataView.items.toList().single { combo.itemLabelGenerator.apply(it) == label }
    }
    private fun anyPage(): Pageable = any(Pageable::class.java) ?: org.springframework.data.domain.PageRequest.of(0, 20)
    private fun nullableQuery(): String? = nullable(String::class.java)
    private fun anyRequest(): CreateFeatureGroupRequest = any(CreateFeatureGroupRequest::class.java) ?: CreateFeatureGroupRequest("", "")
}
