package ru.a1pha1337.featurify.view

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasValidation
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Span
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
import jakarta.validation.Validator
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import ru.a1pha1337.featurify.service.ConflictException
import ru.a1pha1337.featurify.service.DomainValidationException
import ru.a1pha1337.featurify.service.NotFoundException
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
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.service.FeatureToggleService
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Route("")
@PageTitle("Featurify")
@PermitAll
class MainView(private val service: FeatureToggleService, private val validator: Validator) : VerticalLayout() {
    private val tenantSelect = ComboBox<TenantResponse>("Tenant")
    private val grid = Grid<AdminFeatureResponse>()
    private val createFeatureButton = Button("New feature")
    private val createGroupButton = Button("New group")
    private val editButton = Button("Edit")
    private val moveButton = Button("Move to group")
    private val deleteButton = Button("Delete")
    private val deleteTenantButton = Button("Delete tenant")
    private val groupsButton = Button("Manage groups")
    private val historyButton = Button("History")
    private val groupSelect = ComboBox<GroupChoice>("Group")
    private val keyFilter = TextField("Feature key")
    private val pagination = HorizontalLayout()
    private val resultSummary = Span()
    private val selectionSummary = Span("Select a feature to manage it")
    private val emptyState = Div()
    private var updatingControls = false
    private data class GroupChoice(val key: String?, val label: String, val all: Boolean = false)
    private val allGroups = GroupChoice(null, "All groups", all = true)
    private val globalGroup = GroupChoice(null, "Global")
    private var currentPage = 0
    private var groups: List<FeatureGroupResponse> = emptyList()

    companion object {
        private const val PAGE_SIZE = 20
        private const val VISIBLE_PAGE_BUTTONS = 7
        private val HISTORY_DATE_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC)
    }

    init {
        setSizeFull()
        addClassName("main-view")
        isPadding = false
        isSpacing = false

        val brand = Div(Span("F").apply { addClassName("brand-mark") }, H1("Featurify"))
            .apply { addClassName("brand") }
        tenantSelect.setItemLabelGenerator { if (it.defaultTenant) it.key else it.displayName }
        tenantSelect.isAllowCustomValue = false
        tenantSelect.addValueChangeListener {
            if (!updatingControls) runUiAction {
                currentPage = 0
                refreshGroups(preserveSelection = false)
                refreshFeatures()
            }
        }
        val header = Div(brand, Div(tenantSelect, Button("New tenant") { openTenantDialog() }, deleteTenantButton)
            .apply { addClassName("tenant-controls") }).apply { addClassName("app-header") }

        keyFilter.placeholder = "Search by key…"
        keyFilter.maxLength = 255
        keyFilter.helperText = "Enter at least 3 characters"
        keyFilter.isClearButtonVisible = true
        keyFilter.valueChangeMode = ValueChangeMode.LAZY
        keyFilter.valueChangeTimeout = 300
        keyFilter.addValueChangeListener { filtersChanged() }
        groupSelect.setItemLabelGenerator { it.label }
        groupSelect.addValueChangeListener { filtersChanged() }

        deleteTenantButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY)
        deleteTenantButton.addClickListener { openDeleteTenantDialog() }
        createFeatureButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        createFeatureButton.addClickListener { runUiAction { openCreateFeatureDialog() } }
        createGroupButton.addClickListener { openCreateGroupDialog() }
        groupsButton.addClickListener { runUiAction { openGroupsDialog() } }
        editButton.addClickListener { selected()?.let(::openEditDialog) }
        moveButton.addClickListener { selected()?.let { runUiAction { openMoveDialog(it) } } }
        deleteButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY)
        deleteButton.addClickListener { selected()?.let(::openDeleteDialog) }
        historyButton.addClickListener { selected()?.let { runUiAction { openHistoryDialog(it) } } }
        updateSelection(null)

        grid.addComponentColumn { feature ->
            Div(Span(feature.key).apply { addClassName("feature-key") },
                Span(feature.description.ifBlank { "No description" }).apply { addClassName("feature-description") })
                .apply { addClassName("feature-name") }
        }.setHeader("Feature").setFlexGrow(2).setWidth("300px")
        grid.addColumn { it.group ?: "Global" }.setHeader("Group").setWidth("170px").setFlexGrow(1)
        grid.addColumn { if (it.type == FeatureType.BOOLEAN) "Boolean" else "Enum" }
            .setHeader("Type").setWidth("110px").setFlexGrow(0)
        grid.addComponentColumn { feature ->
            val label = if (feature.type == FeatureType.BOOLEAN) {
                if (feature.value == true) "Enabled" else "Disabled"
            } else feature.value.toString()
            Span(label).apply { addClassName("value-chip"); element.setAttribute("title", label) }
        }.setHeader("Value").setWidth("150px").setFlexGrow(1)
        grid.setSizeFull()
        grid.addClassName("feature-grid")
        grid.asSingleSelect().addValueChangeListener { updateSelection(it.value) }
        emptyState.addClassName("empty-state")
        emptyState.element.setAttribute("role", "status")
        resultSummary.addClassName("result-summary")
        selectionSummary.addClassName("selection-summary")
        selectionSummary.element.setAttribute("aria-live", "polite")
        pagination.addClassName("pagination")
        pagination.alignItems = Alignment.CENTER
        pagination.width = "100%"
        pagination.style.set("flex-wrap", "wrap")

        val title = Div(H2("Features"), resultSummary).apply { addClassName("page-title") }
        val actions = Div(groupsButton, createGroupButton, createFeatureButton).apply { addClassName("page-actions") }
        val toolbar = Div(title, actions).apply { addClassName("page-toolbar") }
        val filters = Div(keyFilter, groupSelect, Button("Reset filters") { resetFilters() })
            .apply { addClassName("filters") }
        val selection = Div(selectionSummary, Div(editButton, moveButton, historyButton, deleteButton)
            .apply { addClassName("selection-actions") }).apply { addClassName("selection-bar") }
        val table = VerticalLayout(selection, grid, emptyState, pagination).apply {
            addClassName("table-panel")
            isPadding = false
            isSpacing = false
            setSizeFull()
            expand(grid)
        }
        val workspace = VerticalLayout(toolbar, filters, table).apply {
            addClassName("workspace")
            isPadding = false
            setSizeFull()
            expand(table)
        }
        add(header, workspace)
        expand(workspace)
        refreshTenants()
    }

    private fun filtersChanged() {
        if (!updatingControls) runUiAction {
            currentPage = 0
            refreshFeatures()
        }
    }

    private fun resetFilters() {
        updatingControls = true
        try {
            keyFilter.clear()
            groupSelect.value = allGroups
        } finally {
            updatingControls = false
        }
        filtersChanged()
    }

    private fun updateSelection(feature: AdminFeatureResponse?) {
        val selected = feature != null
        editButton.isEnabled = selected
        moveButton.isEnabled = selected
        deleteButton.isEnabled = selected
        historyButton.isEnabled = feature != null
        selectionSummary.text = feature?.let { "Selected: ${featureName(it)}" } ?: "Select a feature to manage it"
    }

    private fun refreshTenants(selectTenantKey: String? = tenantSelect.value?.key) {
        val tenants = service.listTenants()
        updatingControls = true
        try {
            tenantSelect.setItems(tenants)
            tenantSelect.value = tenants.firstOrNull { it.key == selectTenantKey }
                ?: tenants.firstOrNull { it.defaultTenant } ?: tenants.firstOrNull()
        } finally {
            updatingControls = false
        }
        currentPage = 0
        refreshGroups(preserveSelection = false)
        refreshFeatures()
    }

    private fun refreshGroups(preserveSelection: Boolean = true) {
        val previous = groupSelect.value.takeIf { preserveSelection }
        groups = tenantSelect.value?.let { service.listGroups(it.key) } ?: emptyList()
        val choices = listOf(allGroups, globalGroup) + groups.map {
            GroupChoice(it.key, "${it.displayName} (${it.key})")
        }
        updatingControls = true
        try {
            groupSelect.setItems(choices)
            groupSelect.value = choices.firstOrNull { it.key == previous?.key && it.all == previous?.all } ?: allGroups
        } finally {
            updatingControls = false
        }
    }

    private fun selectGroup(key: String?) {
        groupSelect.value = if (key == null) globalGroup else {
            val group = groups.first { it.key == key }
            GroupChoice(group.key, "${group.displayName} (${group.key})")
        }
    }

    private fun refreshFeatures() {
        grid.deselectAll()
        updateSelection(null)
        val tenant = tenantSelect.value
        deleteTenantButton.isEnabled = tenant != null && !tenant.defaultTenant && tenant.key != "default"
        listOf(createFeatureButton, createGroupButton, groupsButton).forEach { it.isEnabled = tenant != null }
        groupSelect.isEnabled = tenant != null
        val query = keyFilter.value.trim()
        val invalidQuery = query.isNotEmpty() && query.length < 3
        keyFilter.isInvalid = invalidQuery
        keyFilter.errorMessage = "Enter at least 3 characters"
        if (tenant == null || invalidQuery) {
            grid.setItems(emptyList())
            renderPagination(0, 0)
            showEmpty(if (tenant == null) "Create a tenant to get started" else "Keep typing to search",
                if (tenant == null) "Use New tenant to create your workspace." else "Feature search requires at least 3 characters.")
            return
        }
        val scope = groupSelect.value ?: allGroups
        fun load() = service.listForAdmin(tenant.key, pageRequest(), query, scope.key, !scope.all && scope.key == null)
        var result = load()
        if (result.totalPages > 0 && currentPage >= result.totalPages) {
            currentPage = result.totalPages - 1
            result = load()
        }
        grid.setItems(result.content)
        renderPagination(result.totalPages, result.totalElements)
        if (result.isEmpty) {
            showEmpty("No features here", "Create a feature in this group, or adjust your filters.")
        } else {
            grid.isVisible = true
            emptyState.isVisible = false
        }
    }

    private fun showEmpty(title: String, message: String) {
        grid.isVisible = false
        emptyState.isVisible = true
        emptyState.removeAll()
        emptyState.add(H2(title), Paragraph(message))
    }

    private fun pageRequest() = PageRequest.of(currentPage, PAGE_SIZE, Sort.by("groupId", "key").ascending())

    private fun renderPagination(totalPages: Int, totalElements: Long) {
        pagination.removeAll()
        resultSummary.text = "$totalElements features · ${groups.size} groups"
        if (totalPages == 0) {
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

    private fun openDeleteTenantDialog() {
        val tenant = tenantSelect.value ?: return
        if (tenant.defaultTenant || tenant.key == "default") return
        val dialog = newDialog("Delete tenant ${tenant.key}?")
        dialog.add(Paragraph("This tenant, all its groups, features and history will be permanently deleted. This cannot be undone."))
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(Button("Delete tenant") {
            runUiAction {
                service.deleteTenant(tenant.key)
                dialog.close()
                refreshTenants()
                success("Tenant deleted")
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_ERROR) })
        dialog.open()
    }

    private fun openTenantDialog() {
        val dialog = newDialog("Create tenant")
        val tenantKey = TextField("Tenant key")
        val displayName = TextField("Display name")
        tenantKey.isRequired = true
        displayName.isRequired = true
        dialog.add(form(tenantKey, displayName))
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(Button("Create") {
            runUiAction {
                service.createTenant(
                    validated(CreateTenantRequest(
                        key = tenantKey.value.trim(),
                        displayName = displayName.value.trim(),
                    ), mapOf("key" to tenantKey, "displayName" to displayName)),
                )
                dialog.close()
                refreshTenants(tenantKey.value.trim())
                success("Tenant created")
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) })
        dialog.open()
    }

    private fun openCreateFeatureDialog() {
        val tenant = tenantSelect.value ?: return
        val dialog = newDialog("Create feature")
        refreshGroups()
        val key = TextField("Key").apply { isRequired = true; maxLength = 255; helperText = "Lowercase letters, digits, dots and hyphens" }
        val group = groupPicker("Group").apply {
            value = groupChoices().firstOrNull { it.key == groupSelect.value?.key } ?: globalGroup
        }
        val type = ComboBox<FeatureType>("Type").apply {
            setItems(*FeatureType.entries.toTypedArray())
            value = FeatureType.BOOLEAN
        }
        val description = TextArea("Description").apply { maxLength = 2000 }
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
        dialog.add(form(key, group, type, description, booleanValue, enumValue, enumOptions))
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(Button("Create") {
            runUiAction {
                val selectedType = type.value
                val options = enumOptions.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                service.createFeature(
                    tenant.key,
                    validated(CreateFeatureRequest(
                        key = key.value.trim(),
                        type = selectedType,
                        group = group.value?.key,
                        description = description.value,
                        booleanValue = booleanValue.value.takeIf { selectedType == FeatureType.BOOLEAN },
                        enumValue = enumValue.value.takeIf { selectedType == FeatureType.ENUM },
                        enumOptions = options.takeIf { selectedType == FeatureType.ENUM } ?: emptyList(),
                    ), mapOf("key" to key, "type" to type, "description" to description)),
                )
                dialog.close()
                refreshFeatures()
                success("Changes saved")
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) })
        dialog.open()
    }

    private fun openCreateGroupDialog() {
        val tenant = tenantSelect.value ?: return
        val dialog = newDialog("Create group")
        val key = TextField("Group key").apply {
            isRequired = true
            maxLength = 255
            placeholder = "checkout"
            helperText = "Lowercase letters, digits, dots or hyphens; start and end with a letter or digit"
        }
        val displayName = TextField("Display name").apply { isRequired = true; maxLength = 255; placeholder = "Checkout" }
        dialog.add(form(Paragraph("New group in ${tenant.displayName}"), key, displayName))
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(Button("Create group") {
            runUiAction {
                val created = service.createGroup(tenant.key, validated(
                    CreateFeatureGroupRequest(key.value.trim(), displayName.value.trim()),
                    mapOf("key" to key, "displayName" to displayName),
                ))
                refreshGroups()
                resetFilters()
                selectGroup(created.key)
                dialog.close()
                success("Group '${created.displayName}' created")
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) })
        dialog.open()
        key.focus()
    }

    private fun openGroupsDialog() {
        if (tenantSelect.value == null) return
        refreshGroups()
        val dialog = newDialog("Manage groups")
        dialog.width = "800px"
        val groupGrid = Grid<FeatureGroupResponse>()
        groupGrid.addColumn { it.displayName }.setHeader("Name").setFlexGrow(1).setWidth("180px")
        groupGrid.addColumn { it.key }.setHeader("Key").setWidth("160px")
        groupGrid.addComponentColumn { group ->
            Button("Delete") { dialog.close(); openDeleteGroupDialog(group) }.apply {
                addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY)
            }
        }.setHeader("Action").setWidth("120px")
        groupGrid.setItems(groups)
        groupGrid.height = "350px"
        if (groups.isEmpty()) dialog.add(Paragraph("No groups yet. Create a group to organize your features."))
        else dialog.add(groupGrid)
        dialog.footer.add(Button("Close") { dialog.close() })
        dialog.footer.add(Button("New group") { dialog.close(); openCreateGroupDialog() }
            .apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) })
        dialog.open()
    }

    private fun groupChoices() = listOf(globalGroup) + groups
        .map { GroupChoice(it.key, "${it.displayName} (${it.key})") }

    private fun groupPicker(label: String) = ComboBox<GroupChoice>(label).apply {
        setItemLabelGenerator { it.label }
        setItems(groupChoices())
        isRequired = true
        value = globalGroup
        helperText = "Global stores features outside a group"
    }

    private fun openMoveDialog(feature: AdminFeatureResponse) {
        val tenant = tenantSelect.value ?: return
        refreshGroups()
        val dialog = newDialog("Move feature")
        val target = groupPicker("Target group").apply {
            value = groupChoices().firstOrNull { it.key == feature.group }
        }
        val confirm = Button("Move feature") {
            runUiAction {
                val destination = target.value ?: return@runUiAction
                val moved = service.moveFeature(tenant.key, feature.key, feature.group,
                    MoveFeatureRequest(feature.version, destination.key))
                currentPage = 0
                if (groupSelect.value?.all == false) selectGroup(moved.group)
                refreshFeatures()
                dialog.close()
                success("'${feature.key}' moved to ${destination.label}")
            }
        }.apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            isEnabled = false
        }
        target.addValueChangeListener { confirm.isEnabled = it.value != null && it.value.key != feature.group }
        dialog.add(form(Span(feature.key).apply { addClassName("feature-key") },
            Paragraph("Current group: ${feature.group ?: "Global"}"), target))
        dialog.footer.add(Button("Cancel") { dialog.close() }, confirm)
        dialog.open()
        target.focus()
    }

    private fun openDeleteGroupDialog(group: FeatureGroupResponse) {
        val tenant = tenantSelect.value ?: return
        val dialog = newDialog("Delete group ${group.key}?")
        dialog.add(Paragraph("This group and all its features will be permanently deleted. This cannot be undone."))
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(Button("Delete group") {
            runUiAction {
                service.deleteGroup(tenant.key, group.key, group.version)
                dialog.close()
                refreshGroups()
                refreshFeatures()
                success("Group deleted")
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_ERROR) })
        dialog.open()
    }

    private fun openEditDialog(feature: AdminFeatureResponse) {
        val tenant = tenantSelect.value ?: return
        val dialog = newDialog("Edit ${featureName(feature)}")
        val description = TextArea("Description").apply { value = feature.description; maxLength = 2000 }
        val booleanValue = Checkbox("Enabled").apply {
            value = feature.value as? Boolean ?: false
            isVisible = feature.type == FeatureType.BOOLEAN
        }
        val enumValue = ComboBox<String>("Value").apply {
            setItems(feature.enumOptions ?: emptyList())
            value = feature.value as? String
            isVisible = feature.type == FeatureType.ENUM
        }
        dialog.add(form(description, booleanValue, enumValue))
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
                success("Changes saved")
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) })
        dialog.open()
    }

    private fun openDeleteDialog(feature: AdminFeatureResponse) {
        val tenant = tenantSelect.value ?: return
        val dialog = newDialog("Delete ${featureName(feature)}?")
        dialog.add(Paragraph("This feature and its history will be permanently deleted. This cannot be undone."))
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(Button("Delete") {
            runUiAction {
                service.deleteFeature(tenant.key, feature.key, feature.group, feature.version)
                dialog.close()
                refreshFeatures()
                success("Changes saved")
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_ERROR) })
        dialog.open()
    }

    private fun openHistoryDialog(feature: AdminFeatureResponse) {
        val tenant = tenantSelect.value ?: return
        val dialog = newDialog("History: ${featureName(feature)}")
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

    private fun newDialog(title: String) = Dialog(title).apply {
        width = "520px"
        maxWidth = "calc(100vw - 32px)"
        addClassName("feature-dialog")
        isCloseOnOutsideClick = false
    }

    private fun form(vararg fields: Component) = VerticalLayout(*fields).apply {
        isPadding = false
        isSpacing = true
        defaultHorizontalComponentAlignment = Alignment.STRETCH
    }

    private fun <T : Any> validated(request: T, fields: Map<String, HasValidation> = emptyMap()): T {
        fields.values.forEach { it.isInvalid = false }
        val violations = validator.validate(request)
        if (violations.isNotEmpty()) {
            val details = violations.map { it.propertyPath.toString() to it.message }
            details.forEach { (field, message) -> fields[field]?.let { it.isInvalid = true; it.errorMessage = message } }
            throw DomainValidationException("Check the highlighted fields", details)
        }
        return request
    }

    private fun success(message: String) {
        Notification.show(message, 3500, Notification.Position.BOTTOM_START)
            .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
    }

    private fun runUiAction(action: () -> Unit) {
        try {
            action()
        } catch (exception: RuntimeException) {
            val message = when (exception) {
                is DomainValidationException -> exception.violations.joinToString("; ") { "${it.first}: ${it.second}" }
                is ConflictException, is NotFoundException -> exception.message ?: "Refresh the list and try again"
                else -> {
                    LoggerFactory.getLogger(MainView::class.java).error("Feature management action failed", exception)
                    if (generateSequence<Throwable>(exception) { it.cause }.any { it is DataIntegrityViolationException }) {
                        "This key may already exist. Check the group, then try again."
                    } else "Could not complete the action. Refresh the page and try again."
                }
            }
            Notification.show(message, 8000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.LUMO_ERROR)
        }
    }
}
