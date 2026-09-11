package ru.a1pha1337.featurify.view

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.H1
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import jakarta.annotation.security.PermitAll
import ru.a1pha1337.featurify.dto.AdminFeatureResponse
import ru.a1pha1337.featurify.dto.CreateFeatureRequest
import ru.a1pha1337.featurify.dto.CreateTenantRequest
import ru.a1pha1337.featurify.dto.PatchFeatureRequest
import ru.a1pha1337.featurify.dto.TenantResponse
import ru.a1pha1337.featurify.domain.FeatureStatus
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.service.FeatureToggleService

@Route("")
@PageTitle("Featurify")
@PermitAll
class MainView(private val service: FeatureToggleService) : VerticalLayout() {
    private val tenantSelect = ComboBox<TenantResponse>("Tenant")
    private val grid = Grid<AdminFeatureResponse>()
    private val createFeatureButton = Button("New feature")
    private val editButton = Button("Edit")
    private val archiveButton = Button("Archive")
    private val historyButton = Button("History")

    init {
        setSizeFull()
        add(H1("Featurify"), Paragraph("PostgreSQL-backed feature configuration"))

        tenantSelect.setItemLabelGenerator { "${it.displayName} (${it.key})" }
        tenantSelect.addValueChangeListener { refreshFeatures() }
        val createTenantButton = Button("New tenant") { openTenantDialog() }
        createFeatureButton.addClickListener { openCreateFeatureDialog() }
        createFeatureButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)

        editButton.isEnabled = false
        archiveButton.isEnabled = false
        historyButton.isEnabled = false
        editButton.addClickListener { selected()?.let(::openEditDialog) }
        archiveButton.addClickListener { selected()?.let(::openArchiveDialog) }
        historyButton.addClickListener { selected()?.let(::openHistoryDialog) }

        grid.addColumn { it.key }.setHeader("Key").setAutoWidth(true).setFlexGrow(1)
        grid.addColumn { it.type }.setHeader("Type").setAutoWidth(true)
        grid.addColumn { it.value }.setHeader("Value").setAutoWidth(true)
        grid.addColumn { it.status }.setHeader("Status").setAutoWidth(true)
        grid.addColumn { it.version }.setHeader("Version").setAutoWidth(true)
        grid.addColumn { it.description }.setHeader("Description").setFlexGrow(2)
        grid.setSizeFull()
        grid.asSingleSelect().addValueChangeListener { event ->
            val selected = event.value
            editButton.isEnabled = selected?.status == FeatureStatus.ACTIVE
            archiveButton.isEnabled = selected?.status == FeatureStatus.ACTIVE
            historyButton.isEnabled = selected != null
        }

        add(
            HorizontalLayout(tenantSelect, createTenantButton, createFeatureButton, editButton, archiveButton, historyButton),
            grid,
        )
        expand(grid)
        refreshTenants()
    }

    private fun refreshTenants(selectTenantKey: String? = tenantSelect.value?.key) {
        val tenants = service.listTenants()
        tenantSelect.setItems(tenants)
        tenantSelect.value = tenants.firstOrNull { it.key == selectTenantKey } ?: tenants.firstOrNull()
        createFeatureButton.isEnabled = tenantSelect.value != null
        refreshFeatures()
    }

    private fun refreshFeatures() {
        val tenant = tenantSelect.value
        grid.setItems(if (tenant == null) emptyList() else service.listAllForAdmin(tenant.key))
        createFeatureButton.isEnabled = tenant != null
    }

    private fun openTenantDialog() {
        val dialog = Dialog("Create tenant")
        val tenantKey = TextField("Tenant key")
        val displayName = TextField("Display name")
        tenantKey.isRequired = true
        displayName.isRequired = true
        dialog.add(VerticalLayout(tenantKey, displayName))
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(Button("Create") {
            runUiAction {
                service.createTenant(CreateTenantRequest(tenantKey.value, displayName.value))
                dialog.close()
                refreshTenants(tenantKey.value)
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) })
        dialog.open()
    }

    private fun openCreateFeatureDialog() {
        val tenant = tenantSelect.value ?: return
        val dialog = Dialog("Create feature")
        val key = TextField("Key")
        val type = ComboBox<FeatureType>("Type").apply {
            setItems(*FeatureType.entries.toTypedArray())
            value = FeatureType.BOOLEAN
        }
        val description = TextArea("Description")
        val booleanValue = Checkbox("Enabled")
        val enumValue = TextField("Current enum value")
        val enumOptions = TextField("Allowed values (comma-separated)")
        fun updateFields() {
            val isEnum = type.value == FeatureType.ENUM
            booleanValue.isVisible = !isEnum
            enumValue.isVisible = isEnum
            enumOptions.isVisible = isEnum
        }
        type.addValueChangeListener { updateFields() }
        updateFields()
        dialog.add(VerticalLayout(key, type, description, booleanValue, enumValue, enumOptions))
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(Button("Create") {
            runUiAction {
                val selectedType = type.value
                val options = enumOptions.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                service.createFeature(
                    tenant.key,
                    CreateFeatureRequest(
                        key = key.value,
                        type = selectedType,
                        description = description.value,
                        booleanValue = booleanValue.value.takeIf { selectedType == FeatureType.BOOLEAN },
                        enumValue = enumValue.value.takeIf { selectedType == FeatureType.ENUM },
                        enumOptions = options.takeIf { selectedType == FeatureType.ENUM } ?: emptyList(),
                    ),
                )
                dialog.close()
                refreshFeatures()
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) })
        dialog.open()
    }

    private fun openEditDialog(feature: AdminFeatureResponse) {
        val tenant = tenantSelect.value ?: return
        val dialog = Dialog("Edit ${feature.key}")
        val description = TextArea("Description").apply { value = feature.description }
        val booleanValue = Checkbox("Enabled").apply {
            value = feature.value as? Boolean ?: false
            isVisible = feature.type == FeatureType.BOOLEAN
        }
        val enumValue = ComboBox<String>("Value").apply {
            setItems(feature.enumOptions ?: emptyList())
            value = feature.value as? String
            isVisible = feature.type == FeatureType.ENUM
        }
        dialog.add(VerticalLayout(description, booleanValue, enumValue))
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(Button("Save") {
            runUiAction {
                service.patchFeature(
                    tenant.key,
                    feature.key,
                    PatchFeatureRequest(
                        version = feature.version,
                        description = description.value,
                        booleanValue = booleanValue.value.takeIf { feature.type == FeatureType.BOOLEAN },
                        enumValue = enumValue.value.takeIf { feature.type == FeatureType.ENUM },
                    ),
                )
                dialog.close()
                refreshFeatures()
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) })
        dialog.open()
    }

    private fun openArchiveDialog(feature: AdminFeatureResponse) {
        val tenant = tenantSelect.value ?: return
        val dialog = Dialog("Archive ${feature.key}?")
        dialog.add(Paragraph("Archived features disappear from the public API but remain visible here."))
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(Button("Archive") {
            runUiAction {
                service.archive(tenant.key, feature.key, feature.version)
                dialog.close()
                refreshFeatures()
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_ERROR) })
        dialog.open()
    }

    private fun openHistoryDialog(feature: AdminFeatureResponse) {
        val tenant = tenantSelect.value ?: return
        val dialog = Dialog("History: ${feature.key}")
        dialog.width = "850px"
        val history = Grid(service.history(tenant.key, feature.key))
        history.addColumn { it.changedAt }.setHeader("Changed at").setAutoWidth(true)
        history.addColumn { it.operation }.setHeader("Operation").setAutoWidth(true)
        history.addColumn { it.oldValue }.setHeader("Old value")
        history.addColumn { it.newValue }.setHeader("New value")
        history.addColumn { it.changedBy }.setHeader("User").setAutoWidth(true)
        dialog.add(history)
        dialog.footer.add(Button("Close") { dialog.close() })
        dialog.open()
    }

    private fun selected(): AdminFeatureResponse? = grid.asSingleSelect().value

    private fun runUiAction(action: () -> Unit) {
        try {
            action()
        } catch (exception: RuntimeException) {
            Notification.show(exception.message ?: "Operation failed", 5000, Notification.Position.MIDDLE)
                .addThemeVariants(NotificationVariant.LUMO_ERROR)
        }
    }
}
