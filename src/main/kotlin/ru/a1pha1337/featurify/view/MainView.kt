package ru.a1pha1337.featurify.view

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.combobox.MultiSelectComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.H1
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import jakarta.annotation.security.PermitAll
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import ru.a1pha1337.featurify.dto.AdminFeatureResponse
import ru.a1pha1337.featurify.dto.CreateFeatureRequest
import ru.a1pha1337.featurify.dto.CreateTenantRequest
import ru.a1pha1337.featurify.dto.PatchFeatureRequest
import ru.a1pha1337.featurify.dto.TenantResponse
import ru.a1pha1337.featurify.domain.FeatureStatus
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.service.FeatureToggleService
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
    private val statusFilter = MultiSelectComboBox<FeatureStatus>("Statuses")
    private val keyFilter = TextField("Feature key")
    private val pagination = HorizontalLayout()
    private var currentPage = 0

    companion object {
        private const val PAGE_SIZE = 20
        private const val VISIBLE_PAGE_BUTTONS = 7
        private val HISTORY_DATE_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy:MM:dd hh:mm xx")
            .withZone(ZoneOffset.UTC)
    }

    init {
        setSizeFull()
        add(H1("Featurify"), Paragraph("PostgreSQL-backed feature configuration"))

        statusFilter.setItems(*FeatureStatus.entries.toTypedArray())
        statusFilter.setItemLabelGenerator { it.name }
        statusFilter.setValue(setOf(FeatureStatus.ACTIVE))
        statusFilter.isClearButtonVisible = true
        statusFilter.width = "240px"

        keyFilter.placeholder = "At least 3 characters"
        keyFilter.minLength = 3
        keyFilter.maxLength = 255
        keyFilter.isClearButtonVisible = true
        keyFilter.valueChangeMode = ValueChangeMode.LAZY
        keyFilter.valueChangeTimeout = 300
        keyFilter.addValueChangeListener {
            currentPage = 0
            refreshFeatures()
        }

        tenantSelect.setItemLabelGenerator {
            "${it.displayName} (${it.key})${if (it.defaultTenant) " · default" else ""}"
        }
        tenantSelect.addValueChangeListener {
            currentPage = 0
            refreshFeatures()
        }
        val createTenantButton = Button("New tenant") { openTenantDialog() }
        createFeatureButton.addClickListener { openCreateFeatureDialog() }
        createFeatureButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)

        editButton.isEnabled = false
        archiveButton.isEnabled = false
        historyButton.isEnabled = false
        editButton.addClickListener { selected()?.let(::openEditDialog) }
        archiveButton.addClickListener { selected()?.let(::openArchiveDialog) }
        historyButton.addClickListener { selected()?.let(::openHistoryDialog) }
        statusFilter.addValueChangeListener {
            currentPage = 0
            refreshFeatures()
        }

        grid.addColumn { it.group ?: "Global" }.setHeader("Group").setAutoWidth(true)
        grid.addColumn { it.key }.setHeader("Key").setAutoWidth(true).setFlexGrow(1)
        grid.addColumn { it.type }.setHeader("Type").setAutoWidth(true)
        grid.addColumn { it.value }.setHeader("Value").setAutoWidth(true)
        grid.addColumn { it.status }.setHeader("Status").setAutoWidth(true)
        grid.addColumn { it.description }.setHeader("Description").setFlexGrow(2)
        grid.setSizeFull()
        grid.asSingleSelect().addValueChangeListener { event ->
            val selected = event.value
            editButton.isEnabled = selected?.status == FeatureStatus.ACTIVE
            archiveButton.isEnabled = selected?.status == FeatureStatus.ACTIVE
            historyButton.isEnabled = selected != null
        }

        pagination.alignItems = Alignment.CENTER

        add(
            HorizontalLayout(
                tenantSelect,
                createTenantButton,
                createFeatureButton,
                editButton,
                archiveButton,
                historyButton,
            ),
            HorizontalLayout(statusFilter, keyFilter),
            grid,
            pagination,
        )
        expand(grid)
        refreshTenants()
    }

    private fun refreshTenants(selectTenantKey: String? = tenantSelect.value?.key) {
        val tenants = service.listTenants()
        tenantSelect.setItems(tenants)
        tenantSelect.value = tenants.firstOrNull { it.key == selectTenantKey }
            ?: tenants.firstOrNull { it.defaultTenant }
            ?: tenants.firstOrNull()
        createFeatureButton.isEnabled = tenantSelect.value != null
        refreshFeatures()
    }

    private fun refreshFeatures() {
        val tenant = tenantSelect.value
        if (tenant == null) {
            grid.setItems(emptyList())
            renderPagination(0, 0)
            createFeatureButton.isEnabled = false
            return
        }

        val query = keyFilter.value.trim()
        val invalidQuery = query.isNotEmpty() && query.length < 3
        keyFilter.isInvalid = invalidQuery
        keyFilter.errorMessage = "Enter at least 3 characters"
        if (invalidQuery) {
            grid.deselectAll()
            grid.setItems(emptyList())
            renderPagination(0, 0, "Enter at least 3 characters")
            return
        }

        var result = service.listForAdmin(tenant.key, pageRequest(), statusFilter.value, query)
        if (result.totalPages > 0 && currentPage >= result.totalPages) {
            currentPage = result.totalPages - 1
            result = service.listForAdmin(tenant.key, pageRequest(), statusFilter.value, query)
        }
        grid.deselectAll()
        grid.setItems(result.content)
        renderPagination(result.totalPages, result.totalElements)
        createFeatureButton.isEnabled = true
    }

    private fun pageRequest() = PageRequest.of(currentPage, PAGE_SIZE, Sort.by("groupKey", "key").ascending())

    private fun renderPagination(totalPages: Int, totalElements: Long, emptyMessage: String = "No features") {
        pagination.removeAll()
        if (totalPages == 0) {
            pagination.add(Paragraph(emptyMessage))
            return
        }

        val previous = Button("Previous") {
            currentPage--
            refreshFeatures()
        }.apply { isEnabled = currentPage > 0 }
        val next = Button("Next") {
            currentPage++
            refreshFeatures()
        }.apply { isEnabled = currentPage < totalPages - 1 }

        val firstPage = (currentPage - VISIBLE_PAGE_BUTTONS / 2)
            .coerceIn(0, (totalPages - VISIBLE_PAGE_BUTTONS).coerceAtLeast(0))
        val lastPage = (firstPage + VISIBLE_PAGE_BUTTONS).coerceAtMost(totalPages)
        val pageButtons = (firstPage until lastPage).map { pageIndex ->
            Button((pageIndex + 1).toString()) {
                currentPage = pageIndex
                refreshFeatures()
            }.apply {
                if (pageIndex == currentPage) addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            }
        }
        val summary = Paragraph("Page ${currentPage + 1} of $totalPages · $totalElements features")
        pagination.add(previous)
        pagination.add(*pageButtons.toTypedArray())
        pagination.add(next, summary)
    }

    private fun openTenantDialog() {
        val dialog = Dialog("Create tenant")
        val tenantKey = TextField("Tenant key")
        val displayName = TextField("Display name")
        val defaultTenant = Checkbox("Default tenant")
        tenantKey.isRequired = true
        displayName.isRequired = true
        dialog.add(VerticalLayout(tenantKey, displayName, defaultTenant))
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(Button("Create") {
            runUiAction {
                service.createTenant(
                    CreateTenantRequest(
                        key = tenantKey.value,
                        displayName = displayName.value,
                        defaultTenant = defaultTenant.value,
                    ),
                )
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
        val group = TextField("Group").apply {
            placeholder = "Global"
            helperText = "Optional; leave empty for a tenant-wide feature"
            maxLength = 255
        }
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
        dialog.add(VerticalLayout(key, group, type, description, booleanValue, enumValue, enumOptions))
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
                        group = group.value.trim().takeIf { it.isNotEmpty() },
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
        val dialog = Dialog("Edit ${featureName(feature)}")
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
                    feature.group,
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
        val dialog = Dialog("Archive ${featureName(feature)}?")
        dialog.add(Paragraph("Archived features disappear from the public API and can be shown by selecting ARCHIVED in the status filter."))
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(Button("Archive") {
            runUiAction {
                service.archive(tenant.key, feature.key, feature.group, feature.version)
                dialog.close()
                refreshFeatures()
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_ERROR) })
        dialog.open()
    }

    private fun openHistoryDialog(feature: AdminFeatureResponse) {
        val tenant = tenantSelect.value ?: return
        val dialog = Dialog("History: ${featureName(feature)}")
        dialog.width = "850px"
        val history = Grid(service.history(tenant.key, feature.key, feature.group))
        history.addColumn { HISTORY_DATE_FORMATTER.format(it.changedAt) }.setHeader("Changed at").setAutoWidth(true)
        history.addColumn { it.operation }.setHeader("Operation").setAutoWidth(true)
        history.addColumn { it.oldValue }.setHeader("Old value")
        history.addColumn { it.newValue }.setHeader("New value")
        history.addColumn { it.changedBy }.setHeader("User").setAutoWidth(true)
        dialog.add(history)
        dialog.footer.add(Button("Close") { dialog.close() })
        dialog.open()
    }

    private fun selected(): AdminFeatureResponse? = grid.asSingleSelect().value

    private fun featureName(feature: AdminFeatureResponse) =
        feature.group?.let { "$it/${feature.key}" } ?: feature.key

    private fun runUiAction(action: () -> Unit) {
        try {
            action()
        } catch (exception: RuntimeException) {
            Notification.show(exception.message ?: "Operation failed", 5000, Notification.Position.MIDDLE)
                .addThemeVariants(NotificationVariant.LUMO_ERROR)
        }
    }
}
