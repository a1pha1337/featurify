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
import ru.a1pha1337.featurify.dto.CreateFeatureGroupRequest
import ru.a1pha1337.featurify.dto.CreateFeatureRequest
import ru.a1pha1337.featurify.dto.CreateTenantRequest
import ru.a1pha1337.featurify.dto.FeatureGroupResponse
import ru.a1pha1337.featurify.dto.MoveFeatureRequest
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
    private val createGroupButton = Button("New group")
    private val editButton = Button("Edit")
    private val moveButton = Button("Move")
    private val archiveButton = Button("Archive")
    private val archiveGroupButton = Button("Archive group")
    private val groupsButton = Button("Groups")
    private val historyButton = Button("History")
    private val groupSelect = ComboBox<FeatureGroupResponse>("Group")
    private val statusFilter = MultiSelectComboBox<FeatureStatus>("Statuses")
    private val keyFilter = TextField("Feature key")
    private val pagination = HorizontalLayout()
    private var currentPage = 0
    private var groups: List<FeatureGroupResponse> = emptyList()

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
            refreshGroups()
            refreshFeatures()
        }
        val createTenantButton = Button("New tenant") { openTenantDialog() }
        createFeatureButton.addClickListener { openCreateFeatureDialog() }
        createGroupButton.addClickListener { openCreateGroupDialog() }
        groupsButton.addClickListener { openGroupsDialog() }
        createFeatureButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        groupSelect.setItemLabelGenerator { "${it.key} — ${it.displayName}" }
        groupSelect.isClearButtonVisible = true
        groupSelect.width = "240px"
        groupSelect.addValueChangeListener { event ->
            archiveGroupButton.isEnabled = event.value?.status == FeatureStatus.ACTIVE
        }

        editButton.isEnabled = false
        moveButton.isEnabled = false
        archiveButton.isEnabled = false
        archiveGroupButton.isEnabled = false
        historyButton.isEnabled = false
        editButton.addClickListener { selected()?.let(::openEditDialog) }
        moveButton.addClickListener { selected()?.let(::openMoveDialog) }
        archiveButton.addClickListener { selected()?.let(::openArchiveDialog) }
        archiveGroupButton.addClickListener { groupSelect.value?.let(::openArchiveGroupDialog) }
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
            moveButton.isEnabled = selected?.status == FeatureStatus.ACTIVE
            archiveButton.isEnabled = selected?.status == FeatureStatus.ACTIVE
            historyButton.isEnabled = selected != null
        }

        pagination.alignItems = Alignment.CENTER

        add(
            HorizontalLayout(
                tenantSelect,
                createTenantButton,
                createFeatureButton,
                createGroupButton,
                groupsButton,
            ),
            HorizontalLayout(
                groupSelect,
                editButton,
                moveButton,
                archiveButton,
                archiveGroupButton,
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
        createGroupButton.isEnabled = tenantSelect.value != null
        groupsButton.isEnabled = tenantSelect.value != null
        refreshGroups()
        refreshFeatures()
    }

    private fun refreshGroups() {
        groups = tenantSelect.value?.let { service.listGroups(it.key) } ?: emptyList()
        groupSelect.setItems(groups.filter { it.status == FeatureStatus.ACTIVE })
        if (groupSelect.value?.let { value -> groups.none { it.id == value.id && it.status == FeatureStatus.ACTIVE } } == true) {
            groupSelect.clear()
        }
        archiveGroupButton.isEnabled = groupSelect.value?.status == FeatureStatus.ACTIVE
    }

    private fun refreshFeatures() {
        val tenant = tenantSelect.value
        if (tenant == null) {
            grid.setItems(emptyList())
            renderPagination(0, 0)
            createFeatureButton.isEnabled = false
            createGroupButton.isEnabled = false
            groupsButton.isEnabled = false
            groupSelect.clear()
            archiveGroupButton.isEnabled = false
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

    private fun pageRequest() = PageRequest.of(currentPage, PAGE_SIZE, Sort.by("groupId", "key").ascending())

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
        val group = ComboBox<FeatureGroupResponse>("Group").apply {
            setItems(groups.filter { it.status == FeatureStatus.ACTIVE })
            setItemLabelGenerator { "${it.key} — ${it.displayName}" }
            isClearButtonVisible = true
            helperText = "Optional; empty means Global"
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
                        group = group.value?.key,
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

    private fun openCreateGroupDialog() {
        val tenant = tenantSelect.value ?: return
        val dialog = Dialog("Create feature group")
        val key = TextField("Group key").apply { isRequired = true; maxLength = 255 }
        val displayName = TextField("Display name").apply { isRequired = true; maxLength = 255 }
        dialog.add(VerticalLayout(key, displayName))
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(Button("Create") {
            runUiAction {
                service.createGroup(
                    tenant.key,
                    CreateFeatureGroupRequest(key = key.value, displayName = displayName.value),
                )
                dialog.close()
                refreshGroups()
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) })
        dialog.open()
    }

    private fun openGroupsDialog() {
        if (tenantSelect.value == null) return
        refreshGroups()
        val dialog = Dialog("Feature groups")
        dialog.width = "700px"
        val groupGrid = Grid<FeatureGroupResponse>()
        groupGrid.addColumn { it.key }.setHeader("Key").setAutoWidth(true)
        groupGrid.addColumn { it.displayName }.setHeader("Name").setFlexGrow(1)
        groupGrid.addColumn { it.status }.setHeader("Status").setAutoWidth(true)
        groupGrid.addColumn { it.version }.setHeader("Version").setAutoWidth(true)
        groupGrid.setItems(groups)
        dialog.add(groupGrid)
        dialog.footer.add(Button("Close") { dialog.close() })
        dialog.open()
    }

    private fun openMoveDialog(feature: AdminFeatureResponse) {
        val tenant = tenantSelect.value ?: return
        val dialog = Dialog("Move ${featureName(feature)}")
        val target = ComboBox<FeatureGroupResponse>("Target group").apply {
            setItems(groups.filter { it.status == FeatureStatus.ACTIVE })
            setItemLabelGenerator { "${it.key} — ${it.displayName}" }
            isClearButtonVisible = true
            helperText = "Clear to move to Global"
            value = groups.firstOrNull { it.key == feature.group }
        }
        dialog.add(target)
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(Button("Move") {
            runUiAction {
                service.moveFeature(
                    tenant.key,
                    feature.key,
                    feature.group,
                    MoveFeatureRequest(version = feature.version, targetGroup = target.value?.key),
                )
                dialog.close()
                refreshFeatures()
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) })
        dialog.open()
    }

    private fun openArchiveGroupDialog(group: FeatureGroupResponse) {
        val tenant = tenantSelect.value ?: return
        val dialog = Dialog("Archive group ${group.key}?")
        dialog.add(Paragraph("All active features in this group will be archived."))
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(Button("Archive group") {
            runUiAction {
                service.archiveGroup(tenant.key, group.key, group.version)
                dialog.close()
                refreshGroups()
                refreshFeatures()
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_ERROR) })
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
