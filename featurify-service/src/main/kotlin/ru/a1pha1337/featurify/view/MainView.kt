package ru.a1pha1337.featurify.view

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasValidation
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.checkbox.CheckboxGroup
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.combobox.MultiSelectComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H1
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.notification.NotificationVariant
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.page.Page
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.router.PageTitle
import com.vaadin.flow.router.Route
import jakarta.annotation.security.PermitAll
import jakarta.validation.Validator
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.dto.AccessTokenResponse
import ru.a1pha1337.featurify.dto.AdminFeatureResponse
import ru.a1pha1337.featurify.dto.CreateAccessTokenRequest
import ru.a1pha1337.featurify.dto.CreateFeatureGroupRequest
import ru.a1pha1337.featurify.dto.CreateFeatureRequest
import ru.a1pha1337.featurify.dto.CreateNamespaceRequest
import ru.a1pha1337.featurify.dto.FeatureGroupResponse
import ru.a1pha1337.featurify.dto.NamespaceResponse
import ru.a1pha1337.featurify.dto.PatchFeatureRequest
import ru.a1pha1337.featurify.service.AccessTokenService
import ru.a1pha1337.featurify.service.ConflictException
import ru.a1pha1337.featurify.service.DomainValidationException
import ru.a1pha1337.featurify.service.FeatureToggleService
import ru.a1pha1337.featurify.service.NotFoundException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Route("")
@PageTitle("Featurify")
@PermitAll
class MainView(
    private val service: FeatureToggleService,
    private val validator: Validator,
    private val accessTokens: AccessTokenService,
) : VerticalLayout() {
    private val namespaceSelect = ComboBox<NamespaceResponse>("Namespace")
    private val grid = Grid<AdminFeatureResponse>()
    private val createFeatureButton = Button("New feature")
    private val createGroupButton = Button("New group")
    private val editButton = Button("Edit")
    private val deleteButton = Button("Delete")
    private val tokensButton = Button("Access tokens")
    private val deleteNamespaceButton = Button("Delete namespace")
    private val groupsButton = Button("Manage groups")
    private val historyButton = Button("History")
    private val groupSelect = ComboBox<GroupChoice>("Group")
    private val keyFilter = TextField("Feature key")
    private val pagination = HorizontalLayout()
    private val resultSummary = Span()
    private val selectionSummary = Span("Select a feature to manage it")
    private val emptyState = Div()
    private var updatingControls = false

    private val allGroups = GroupChoice(null, "All groups", all = true)
    private val globalGroup = GroupChoice(null, "Global")
    private var currentPage = 0
    private var groups: List<FeatureGroupResponse> = emptyList()
    private var clientZoneId: ZoneId = ZoneOffset.UTC
    private var localDateTimeFormatter =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm z")
            .withZone(clientZoneId)
    private val dateTimeRefreshers = mutableListOf<() -> Unit>()

    companion object {
        private const val PAGE_SIZE = 20
        private const val VISIBLE_PAGE_BUTTONS = 7
    }

    init {
        setSizeFull()
        addClassName("main-view")
        isPadding = false
        isSpacing = false

        val brand =
            Div(Span("F").apply { addClassName("brand-mark") }, H1("Featurify"))
                .apply { addClassName("brand") }
        namespaceSelect.setItemLabelGenerator { if (it.defaultNamespace) it.key else it.displayName }
        namespaceSelect.isAllowCustomValue = false
        namespaceSelect.addValueChangeListener {
            if (!updatingControls) {
                runUiAction {
                    currentPage = 0
                    refreshGroups(preserveSelection = false)
                    refreshFeatures()
                }
            }
        }
        val header =
            Div(
                brand,
                Div(
                    namespaceSelect,
                    Button("New namespace") { openNamespaceDialog() },
                    tokensButton,
                    deleteNamespaceButton,
                ).apply { addClassName("namespace-controls") },
            ).apply { addClassName("app-header") }

        keyFilter.placeholder = "Search by key…"
        keyFilter.maxLength = 255
        keyFilter.helperText = "Enter at least 3 characters"
        keyFilter.isClearButtonVisible = true
        keyFilter.valueChangeMode = ValueChangeMode.LAZY
        keyFilter.valueChangeTimeout = 300
        keyFilter.addValueChangeListener { filtersChanged() }
        groupSelect.setItemLabelGenerator { it.label }
        groupSelect.addValueChangeListener { filtersChanged() }

        tokensButton.addClickListener { runUiAction { openAccessTokensDialog() } }
        deleteNamespaceButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY)
        deleteNamespaceButton.addClickListener { openDeleteNamespaceDialog() }
        createFeatureButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        createFeatureButton.addClickListener { runUiAction { openCreateFeatureDialog() } }
        createGroupButton.addClickListener { openCreateGroupDialog() }
        groupsButton.addClickListener { runUiAction { openGroupsDialog() } }
        editButton.addClickListener { selected()?.let(::openEditDialog) }
        deleteButton.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY)
        deleteButton.addClickListener { selected()?.let(::openDeleteDialog) }
        historyButton.addClickListener { selected()?.let { runUiAction { openHistoryDialog(it) } } }
        updateSelection(null)

        grid
            .addComponentColumn { feature ->
                Div(
                    Span(feature.key + if (feature.managed) " � Kubernetes" else "").apply { addClassName("feature-key") },
                    Span(feature.description.ifBlank { "No description" }).apply { addClassName("feature-description") },
                ).apply { addClassName("feature-name") }
            }.setHeader("Feature")
            .setFlexGrow(2)
            .setWidth("300px")
        grid
            .addColumn { it.group ?: "Global" }
            .setHeader("Group")
            .setWidth("170px")
            .setFlexGrow(1)
        grid
            .addColumn {
                when (it.type) {
                    FeatureType.BOOLEAN -> "Boolean"
                    FeatureType.ENUM -> "Enum"
                    FeatureType.VECTOR -> "Vector"
                }
            }.setHeader("Type")
            .setWidth("110px")
            .setFlexGrow(0)
        grid
            .addComponentColumn { feature -> valueEditor(feature) }
            .setHeader("Value")
            .setWidth("210px")
            .setFlexGrow(1)
            .setKey("value")
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
        val filters =
            Div(keyFilter, groupSelect, Button("Reset filters") { resetFilters() })
                .apply { addClassName("filters") }
        val selection =
            Div(
                selectionSummary,
                Div(editButton, historyButton, deleteButton)
                    .apply { addClassName("selection-actions") },
            ).apply { addClassName("selection-bar") }
        val table =
            VerticalLayout(selection, grid, emptyState, pagination).apply {
                addClassName("table-panel")
                isPadding = false
                isSpacing = false
                setSizeFull()
                expand(grid)
            }
        val workspace =
            VerticalLayout(toolbar, filters, table).apply {
                addClassName("workspace")
                isPadding = false
                setSizeFull()
                expand(table)
            }
        add(header, workspace)
        expand(workspace)
        refreshNamespaces()
        addAttachListener { resolveClientZone(it.ui.page) }
    }

    private fun resolveClientZone(page: Page) {
        page.retrieveExtendedClientDetails { details ->
            val zone =
                details.timeZoneId
                    ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                    ?: ZoneOffset.UTC
            if (zone != clientZoneId) {
                clientZoneId = zone
                localDateTimeFormatter =
                    DateTimeFormatter
                        .ofPattern("yyyy-MM-dd HH:mm z")
                        .withZone(clientZoneId)
                grid.dataProvider.refreshAll()
                dateTimeRefreshers.toList().forEach { it() }
            }
        }
    }

    private fun formatDateTime(value: Instant): String = localDateTimeFormatter.format(value)

    private fun valueEditor(feature: AdminFeatureResponse): Component {
        val namespaceKey = namespaceSelect.value?.key ?: return Span()
        val valueManaged = feature.valueManaged
        var current = feature
        if (feature.type == FeatureType.VECTOR) {
            val elements =
                VerticalLayout().apply {
                    isPadding = false
                    isSpacing = false
                }
            vectorValues(feature).forEach { (name, _) ->
                val toggle = featureSwitch("Toggle ${featureName(feature)} element $name")
                toggle.isEnabled = !valueManaged
                markManagedValue(toggle, valueManaged)

                fun render() {
                    val enabled = vectorValues(current).getValue(name)
                    toggle.text = "$name: ${if (enabled) "Enabled" else "Disabled"}"
                    toggle.element.setAttribute("aria-checked", enabled.toString())
                }
                render()
                toggle.addClickListener {
                    runUiAction {
                        current =
                            service.patchFeature(
                                namespaceKey,
                                current.key,
                                current.group,
                                PatchFeatureRequest(current.version, vectorValues = mapOf(name to !vectorValues(current).getValue(name))),
                            )
                        render()
                        refreshFeatures()
                    }
                }
                elements.add(toggle)
            }
            return elements
        }
        if (feature.type == FeatureType.BOOLEAN) {
            val toggle = featureSwitch("Toggle ${featureName(feature)}")
            toggle.isEnabled = !valueManaged
            markManagedValue(toggle, valueManaged)

            fun render() {
                toggle.text = if (current.value == true) "Enabled" else "Disabled"
                toggle.element.setAttribute("aria-checked", (current.value == true).toString())
            }
            render()
            toggle.addClickListener {
                runUiAction {
                    current =
                        service.patchFeature(
                            namespaceKey,
                            current.key,
                            current.group,
                            PatchFeatureRequest(current.version, booleanValue = current.value != true),
                        )
                    render()
                    refreshFeatures()
                }
            }
            return toggle
        }
        return ComboBox<String>().apply {
            addClassName("inline-enum")
            markManagedValue(this, valueManaged)
            setAriaLabel("Value for ${featureName(feature)}")
            setItems(feature.enumOptions ?: emptyList())
            value = feature.value as String
            isAllowCustomValue = false
            isClearButtonVisible = false
            isEnabled = !valueManaged
            addValueChangeListener { event ->
                if (event.isFromClient && event.value == null) value = current.value as String
                if (event.isFromClient && event.value != null && event.value != current.value) {
                    runUiAction {
                        try {
                            current =
                                service.patchFeature(
                                    namespaceKey,
                                    current.key,
                                    current.group,
                                    PatchFeatureRequest(current.version, enumValue = event.value),
                                )
                        } finally {
                            value = current.value as String
                        }
                        refreshFeatures()
                    }
                }
            }
        }
    }

    private fun markManagedValue(
        component: Component,
        managed: Boolean,
    ) {
        if (managed) {
            component.addClassName("managed-value")
            component.element.setAttribute("title", "Managed by Kubernetes")
        }
    }

    private fun featureSwitch(ariaLabel: String): Button =
        Button().apply {
            addClassName("feature-switch")
            icon = Span(Span().apply { addClassName("switch-thumb") }).apply { addClassName("switch-track") }
            element.setAttribute("role", "switch")
            element.setAttribute("aria-label", ariaLabel)
        }

    @Suppress("UNCHECKED_CAST")
    private fun vectorValues(feature: AdminFeatureResponse): Map<String, Boolean> =
        if (feature.type == FeatureType.VECTOR) feature.value as Map<String, Boolean> else emptyMap()

    private fun filtersChanged() {
        if (!updatingControls) {
            runUiAction {
                currentPage = 0
                refreshFeatures()
            }
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
        editButton.isEnabled = selected && feature?.valueManaged != true
        deleteButton.isEnabled = selected && feature?.managed != true
        historyButton.isEnabled = feature != null
        selectionSummary.text = feature?.let { "Selected: ${featureName(it)}" } ?: "Select a feature to manage it"
    }

    private fun refreshNamespaces(selectNamespaceKey: String? = namespaceSelect.value?.key) {
        val namespaces = service.listNamespaces()
        updatingControls = true
        try {
            namespaceSelect.setItems(namespaces)
            namespaceSelect.value = namespaces.firstOrNull { it.key == selectNamespaceKey }
                ?: namespaces.firstOrNull { it.defaultNamespace } ?: namespaces.firstOrNull()
        } finally {
            updatingControls = false
        }
        currentPage = 0
        refreshGroups(preserveSelection = false)
        refreshFeatures()
    }

    private fun refreshGroups(preserveSelection: Boolean = true) {
        val previous = groupSelect.value.takeIf { preserveSelection }
        groups = namespaceSelect.value?.let { service.listGroups(it.key) } ?: emptyList()
        val choices =
            listOf(allGroups, globalGroup) +
                groups.map {
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
        groupSelect.value =
            if (key == null) {
                globalGroup
            } else {
                val group = groups.first { it.key == key }
                GroupChoice(group.key, "${group.displayName} (${group.key})")
            }
    }

    private fun refreshFeatures() {
        grid.deselectAll()
        updateSelection(null)
        val namespace = namespaceSelect.value
        tokensButton.isEnabled = namespace != null
        deleteNamespaceButton.isEnabled =
            namespace != null &&
            !namespace.defaultNamespace &&
            namespace.key != "default" &&
            namespace.managedBy == null
        listOf(createFeatureButton, createGroupButton, groupsButton).forEach { it.isEnabled = namespace != null }
        createFeatureButton.isEnabled = namespace != null && !namespace.exclusive
        createGroupButton.isEnabled = namespace != null && !namespace.exclusive
        namespaceSelect.helperText = namespace?.managedBy?.let { "Managed by Kubernetes: $it" } ?: ""
        groupSelect.isEnabled = namespace != null
        val query = keyFilter.value.trim()
        val invalidQuery = query.isNotEmpty() && query.length < 3
        keyFilter.isInvalid = invalidQuery
        keyFilter.errorMessage = "Enter at least 3 characters"
        if (namespace == null || invalidQuery) {
            grid.setItems(emptyList())
            renderPagination(0, 0)
            showEmpty(
                if (namespace == null) "Create a namespace to get started" else "Keep typing to search",
                if (namespace == null) "Use New namespace to create your workspace." else "Feature search requires at least 3 characters.",
            )
            return
        }
        val scope = groupSelect.value ?: allGroups

        fun load() = service.listForAdmin(namespace.key, pageRequest(), query, scope.key, !scope.all && scope.key == null)

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

    private fun showEmpty(
        title: String,
        message: String,
    ) {
        grid.isVisible = false
        emptyState.isVisible = true
        emptyState.removeAll()
        emptyState.add(H2(title), Paragraph(message))
    }

    private fun pageRequest() = PageRequest.of(currentPage, PAGE_SIZE, Sort.by("groupId", "key").ascending())

    private fun renderPagination(
        totalPages: Int,
        totalElements: Long,
    ) {
        pagination.removeAll()
        resultSummary.text = "$totalElements features · ${groups.size} groups"
        if (totalPages == 0) {
            return
        }

        val previous =
            Button("Previous") {
                currentPage--
                refreshFeatures()
            }.apply { isEnabled = currentPage > 0 }
        val next =
            Button("Next") {
                currentPage++
                refreshFeatures()
            }.apply { isEnabled = currentPage < totalPages - 1 }

        val firstPage =
            (currentPage - VISIBLE_PAGE_BUTTONS / 2)
                .coerceIn(0, (totalPages - VISIBLE_PAGE_BUTTONS).coerceAtLeast(0))
        val lastPage = (firstPage + VISIBLE_PAGE_BUTTONS).coerceAtMost(totalPages)
        val pageButtons =
            (firstPage until lastPage).map { pageIndex ->
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

    private fun openAccessTokensDialog() {
        val namespace = namespaceSelect.value ?: return
        val dialog = newDialog("Access tokens: ${namespace.key}")
        dialog.width = "720px"
        val name =
            TextField("Token name").apply {
                isRequired = true
                maxLength = 255
            }
        val generated =
            TextArea("New token - copy and save it now").apply {
                isReadOnly = true
                isVisible = false
                helperText = "This secret is shown only here. After closing, generate a new token if you lose it."
                width = "100%"
            }
        val tokenGrid =
            Grid<AccessTokenResponse>().apply {
                addColumn { it.name }.setHeader("Name").setFlexGrow(1)
                addColumn { formatDateTime(it.createdAt) }.setHeader("Created").setAutoWidth(true)
                height = "240px"
            }
        val refreshTokenDates = { tokenGrid.dataProvider.refreshAll() }
        dateTimeRefreshers += refreshTokenDates
        dialog.addOpenedChangeListener { if (!it.isOpened) dateTimeRefreshers.remove(refreshTokenDates) }

        fun refresh() {
            tokenGrid.setItems(accessTokens.list(namespace.key))
        }
        tokenGrid
            .addComponentColumn { token ->
                Button("Revoke") {
                    val confirm = newDialog("Revoke ${token.name}?")
                    confirm.add(Paragraph("Backends using this token will lose access immediately."))
                    confirm.footer.add(
                        Button("Cancel") { confirm.close() },
                        Button("Revoke token") {
                            runUiAction {
                                accessTokens.revoke(namespace.key, token.id)
                                generated.clear()
                                generated.isVisible = false
                                refresh()
                                confirm.close()
                                success("Token revoked")
                            }
                        }.apply { addThemeVariants(ButtonVariant.LUMO_ERROR) },
                    )
                    confirm.open()
                }.apply { addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY) }
            }.setHeader("Action")
            .setWidth("110px")
            .setFlexGrow(0)
        refresh()
        val generate =
            Button("Generate token") {
                runUiAction {
                    val created =
                        accessTokens.create(
                            namespace.key,
                            validated(
                                CreateAccessTokenRequest(name.value.trim()),
                                mapOf("name" to name),
                            ),
                        )
                    generated.value = created.token
                    generated.isVisible = true
                    name.clear()
                    refresh()
                }
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
        dialog.add(
            form(
                Paragraph("Each token grants read access to features in this namespace through gRPC."),
                tokenGrid,
                name,
                generate,
                generated,
            ),
        )
        dialog.footer.add(Button("Close") { dialog.close() })
        dialog.addOpenedChangeListener { if (!it.isOpened) generated.clear() }
        dialog.open()
    }

    private fun openDeleteNamespaceDialog() {
        val namespace = namespaceSelect.value ?: return
        if (namespace.defaultNamespace || namespace.key == "default") return
        val dialog = newDialog("Delete namespace ${namespace.key}?")
        dialog.add(Paragraph("This namespace, all its groups, features and history will be permanently deleted. This cannot be undone."))
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(
            Button("Delete namespace") {
                runUiAction {
                    service.deleteNamespace(namespace.key)
                    dialog.close()
                    refreshNamespaces()
                    success("Namespace deleted")
                }
            }.apply { addThemeVariants(ButtonVariant.LUMO_ERROR) },
        )
        dialog.open()
    }

    private fun openNamespaceDialog() {
        val dialog = newDialog("Create namespace")
        val namespaceKey = TextField("Namespace key")
        val displayName = TextField("Display name")
        namespaceKey.isRequired = true
        displayName.isRequired = true
        dialog.add(form(namespaceKey, displayName))
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(
            Button("Create") {
                runUiAction {
                    service.createNamespace(
                        validated(
                            CreateNamespaceRequest(
                                key = namespaceKey.value.trim(),
                                displayName = displayName.value.trim(),
                            ),
                            mapOf("key" to namespaceKey, "displayName" to displayName),
                        ),
                    )
                    dialog.close()
                    refreshNamespaces(namespaceKey.value.trim())
                    success("Namespace created")
                }
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) },
        )
        dialog.open()
    }

    private fun openCreateFeatureDialog() {
        val namespace = namespaceSelect.value ?: return
        val dialog = newDialog("Create feature")
        refreshGroups()
        val key =
            TextField("Key").apply {
                isRequired = true
                maxLength = 255
                helperText =
                    "camelCase, PascalCase, kebab-case or dotted names; start with a letter"
            }
        val group =
            groupPicker("Group").apply {
                value = groupChoices().firstOrNull { it.key == groupSelect.value?.key } ?: globalGroup
            }
        val type =
            ComboBox<FeatureType>("Type").apply {
                setItems(*FeatureType.entries.toTypedArray())
                value = FeatureType.BOOLEAN
            }
        val description = TextArea("Description").apply { maxLength = 2000 }
        val booleanValue = Checkbox("Enabled")
        val enumValue = ComboBox<String>("Current enum value").apply { isRequired = true }
        val enumOptions =
            MultiSelectComboBox<String>("Allowed values").apply {
                isAllowCustomValue = true
                isRequired = true
                isClearButtonVisible = true
                helperText = "Type a value and press Enter. Remove values with the cross."
                setItems(emptyList<String>())
            }
        val vectorEnabled = CheckboxGroup<String>("Enabled elements")
        val availableOptions = linkedSetOf<String>()
        enumOptions.addCustomValueSetListener { event ->
            val option = event.detail.trim()
            enumOptions.isInvalid = option.isEmpty() ||
                option.length > 255 ||
                (option !in enumOptions.value && enumOptions.value.size >= 100)
            enumOptions.errorMessage = "Use 1-255 characters per value and at most 100 values"
            if (!enumOptions.isInvalid) {
                val current = enumValue.value
                val selected = LinkedHashSet(enumOptions.value).apply { add(option) }
                availableOptions.add(option)
                enumOptions.setItems(availableOptions)
                enumOptions.value = selected
                if (current in selected) enumValue.value = current
            }
        }
        enumOptions.addValueChangeListener {
            val current = enumValue.value
            val options = availableOptions.filter { it in enumOptions.value }
            val enabled = vectorEnabled.value.intersect(options.toSet())
            vectorEnabled.setItems(options)
            vectorEnabled.value = enabled
            enumValue.setItems(options)
            enumValue.value = current?.takeIf { it in options } ?: options.firstOrNull()
        }

        fun updateFields() {
            val isEnum = type.value == FeatureType.ENUM
            booleanValue.isVisible = type.value == FeatureType.BOOLEAN
            enumValue.isVisible = isEnum
            enumOptions.isVisible = isEnum || type.value == FeatureType.VECTOR
            enumOptions.label = if (type.value == FeatureType.VECTOR) "Elements" else "Allowed values"
            vectorEnabled.isVisible = type.value == FeatureType.VECTOR
        }
        type.addValueChangeListener { updateFields() }
        updateFields()
        dialog.add(form(key, group, type, description, booleanValue, enumOptions, enumValue, vectorEnabled))
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(
            Button("Create") {
                runUiAction {
                    val selectedType = type.value
                    val options = availableOptions.filter { it in enumOptions.value }
                    if (selectedType != FeatureType.BOOLEAN && options.isEmpty()) {
                        enumOptions.isInvalid = true
                        enumOptions.errorMessage = "Add at least one allowed value"
                        return@runUiAction
                    }
                    service.createFeature(
                        namespace.key,
                        validated(
                            CreateFeatureRequest(
                                key = key.value.trim(),
                                type = selectedType,
                                group = group.value?.key,
                                description = description.value,
                                booleanValue = booleanValue.value.takeIf { selectedType == FeatureType.BOOLEAN },
                                enumValue = enumValue.value.takeIf { selectedType == FeatureType.ENUM },
                                enumOptions = options.takeIf { selectedType == FeatureType.ENUM } ?: emptyList(),
                                vectorValues =
                                    if (selectedType ==
                                        FeatureType.VECTOR
                                    ) {
                                        options.associateWith { it in vectorEnabled.value }
                                    } else {
                                        emptyMap()
                                    },
                            ),
                            mapOf("key" to key, "type" to type, "description" to description),
                        ),
                    )
                    dialog.close()
                    refreshFeatures()
                    success("Changes saved")
                }
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) },
        )
        dialog.open()
    }

    private fun openCreateGroupDialog() {
        val namespace = namespaceSelect.value ?: return
        val dialog = newDialog("Create group")
        val key =
            TextField("Group key").apply {
                isRequired = true
                maxLength = 255
                placeholder = "checkout"
                helperText = "camelCase, PascalCase, kebab-case or dotted names; start with a letter"
            }
        val displayName =
            TextField("Display name").apply {
                isRequired = true
                maxLength = 255
                placeholder = "Checkout"
            }
        dialog.add(form(Paragraph("New group in ${namespace.displayName}"), key, displayName))
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(
            Button("Create group") {
                runUiAction {
                    val created =
                        service.createGroup(
                            namespace.key,
                            validated(
                                CreateFeatureGroupRequest(key.value.trim(), displayName.value.trim()),
                                mapOf("key" to key, "displayName" to displayName),
                            ),
                        )
                    refreshGroups()
                    resetFilters()
                    selectGroup(created.key)
                    dialog.close()
                    success("Group '${created.displayName}' created")
                }
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) },
        )
        dialog.open()
        key.focus()
    }

    private fun openGroupsDialog() {
        if (namespaceSelect.value == null) return
        refreshGroups()
        val dialog = newDialog("Manage groups")
        dialog.width = "800px"
        val groupGrid = Grid<FeatureGroupResponse>()
        groupGrid
            .addColumn { it.displayName }
            .setHeader("Name")
            .setFlexGrow(1)
            .setWidth("180px")
        groupGrid.addColumn { it.key }.setHeader("Key").setWidth("160px")
        groupGrid
            .addComponentColumn { group ->
                Button("Delete") {
                    dialog.close()
                    openDeleteGroupDialog(group)
                }.apply {
                    isEnabled = !group.managed
                    addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY)
                }
            }.setHeader("Action")
            .setWidth("120px")
        groupGrid.setItems(groups)
        groupGrid.height = "350px"
        if (groups.isEmpty()) {
            dialog.add(Paragraph("No groups yet. Create a group to organize your features."))
        } else {
            dialog.add(groupGrid)
        }
        dialog.footer.add(Button("Close") { dialog.close() })
        dialog.footer.add(
            Button("New group") {
                dialog.close()
                openCreateGroupDialog()
            }.apply {
                isEnabled = namespaceSelect.value?.exclusive != true
                addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            },
        )
        dialog.open()
    }

    private fun groupChoices() =
        listOf(globalGroup) +
            groups
                .map { GroupChoice(it.key, "${it.displayName} (${it.key})") }

    private fun groupPicker(label: String) =
        ComboBox<GroupChoice>(label).apply {
            setItemLabelGenerator { it.label }
            setItems(groupChoices())
            isRequired = true
            value = globalGroup
            helperText = "Global stores features outside a group"
        }

    private fun openDeleteGroupDialog(group: FeatureGroupResponse) {
        val namespace = namespaceSelect.value ?: return
        val dialog = newDialog("Delete group ${group.key}?")
        dialog.add(Paragraph("This group and all its features will be permanently deleted. This cannot be undone."))
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(
            Button("Delete group") {
                runUiAction {
                    service.deleteGroup(namespace.key, group.key, group.version)
                    dialog.close()
                    refreshGroups()
                    refreshFeatures()
                    success("Group deleted")
                }
            }.apply { addThemeVariants(ButtonVariant.LUMO_ERROR) },
        )
        dialog.open()
    }

    private fun openEditDialog(feature: AdminFeatureResponse) {
        val namespace = namespaceSelect.value ?: return
        refreshGroups()
        val dialog = newDialog("Edit ${featureName(feature)}")
        val group =
            groupPicker("Group").apply {
                value = groupChoices().firstOrNull { it.key == feature.group }
            }
        val description =
            TextArea("Description").apply {
                value = feature.description
                maxLength = 2000
            }
        val booleanValue =
            Checkbox("Enabled").apply {
                value = feature.value as? Boolean ?: false
                isVisible = feature.type == FeatureType.BOOLEAN
            }
        val enumValue =
            ComboBox<String>("Value").apply {
                setItems(feature.enumOptions ?: emptyList())
                value = feature.value as? String
                isVisible = feature.type == FeatureType.ENUM
            }
        val vectorEnabled =
            CheckboxGroup<String>("Enabled elements").apply {
                val elements = vectorValues(feature)
                setItems(elements.keys)
                value = elements.filterValues { it }.keys
                isVisible = feature.type == FeatureType.VECTOR
            }
        group.isEnabled = !feature.managed
        description.isReadOnly = feature.managed
        dialog.add(form(group, description, booleanValue, enumValue, vectorEnabled))
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(
            Button("Save") {
                runUiAction {
                    val destination = group.value ?: return@runUiAction
                    val saved =
                        service.editFeature(
                            namespace.key,
                            feature.key,
                            feature.group,
                            destination.key,
                            PatchFeatureRequest(
                                version = feature.version,
                                description = description.value,
                                booleanValue = booleanValue.value.takeIf { feature.type == FeatureType.BOOLEAN },
                                enumValue = enumValue.value.takeIf { feature.type == FeatureType.ENUM },
                                vectorValues =
                                    if (feature.type ==
                                        FeatureType.VECTOR
                                    ) {
                                        vectorValues(feature).keys.associateWith { it in vectorEnabled.value }
                                    } else {
                                        null
                                    },
                            ),
                        )
                    dialog.close()
                    if (groupSelect.value?.all == false) selectGroup(saved.group)
                    refreshFeatures()
                    success("Changes saved")
                }
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) },
        )
        dialog.open()
    }

    private fun openDeleteDialog(feature: AdminFeatureResponse) {
        val namespace = namespaceSelect.value ?: return
        val dialog = newDialog("Delete ${featureName(feature)}?")
        dialog.add(Paragraph("This feature and its history will be permanently deleted. This cannot be undone."))
        dialog.footer.add(Button("Cancel") { dialog.close() })
        dialog.footer.add(
            Button("Delete") {
                runUiAction {
                    service.deleteFeature(namespace.key, feature.key, feature.group, feature.version)
                    dialog.close()
                    refreshFeatures()
                    success("Changes saved")
                }
            }.apply { addThemeVariants(ButtonVariant.LUMO_ERROR) },
        )
        dialog.open()
    }

    private fun openHistoryDialog(feature: AdminFeatureResponse) {
        val namespace = namespaceSelect.value ?: return
        val dialog = newDialog("History: ${featureName(feature)}")
        dialog.width = "850px"
        val history = Grid(service.history(namespace.key, feature.key, feature.group))
        history.addColumn { formatDateTime(it.changedAt) }.setHeader("Changed at").setAutoWidth(true)
        history.addColumn { it.operation }.setHeader("Operation").setAutoWidth(true)
        history.addColumn { it.oldValue }.setHeader("Old value")
        history.addColumn { it.newValue }.setHeader("New value")
        history.addColumn { it.changedBy }.setHeader("User").setAutoWidth(true)
        val refreshHistoryDates = { history.dataProvider.refreshAll() }
        dateTimeRefreshers += refreshHistoryDates
        dialog.addOpenedChangeListener { if (!it.isOpened) dateTimeRefreshers.remove(refreshHistoryDates) }
        dialog.add(history)
        dialog.footer.add(Button("Close") { dialog.close() })
        dialog.open()
    }

    private fun selected(): AdminFeatureResponse? = grid.asSingleSelect().value

    private fun featureName(feature: AdminFeatureResponse) = feature.group?.let { "$it/${feature.key}" } ?: feature.key

    private fun newDialog(title: String) =
        Dialog(title).apply {
            width = "520px"
            maxWidth = "calc(100vw - 32px)"
            addClassName("feature-dialog")
            isCloseOnOutsideClick = false
        }

    private fun form(vararg fields: Component) =
        VerticalLayout(*fields).apply {
            isPadding = false
            isSpacing = true
            defaultHorizontalComponentAlignment = Alignment.STRETCH
        }

    private fun <T : Any> validated(
        request: T,
        fields: Map<String, HasValidation> = emptyMap(),
    ): T {
        fields.values.forEach { it.isInvalid = false }
        val violations = validator.validate(request)
        if (violations.isNotEmpty()) {
            val details = violations.map { it.propertyPath.toString() to it.message }
            details.forEach { (field, message) ->
                fields[field]?.let {
                    it.isInvalid = true
                    it.errorMessage = message
                }
            }
            throw DomainValidationException("Check the highlighted fields", details)
        }
        return request
    }

    private fun success(message: String) {
        Notification
            .show(message, 3500, Notification.Position.BOTTOM_START)
            .addThemeVariants(NotificationVariant.LUMO_SUCCESS)
    }

    private fun runUiAction(action: () -> Unit) {
        try {
            action()
        } catch (exception: RuntimeException) {
            val message =
                when (exception) {
                    is DomainValidationException -> exception.violations.joinToString("; ") { "${it.first}: ${it.second}" }
                    is ConflictException, is NotFoundException -> exception.message ?: "Refresh the list and try again"
                    else -> {
                        LoggerFactory
                            .getLogger(MainView::class.java)
                            .error("Feature management action failed", exception)
                        if (generateSequence<Throwable>(exception) { it.cause }.any { it is DataIntegrityViolationException }) {
                            "This key may already exist. Check the group, then try again."
                        } else {
                            "Could not complete the action. Refresh the page and try again."
                        }
                    }
                }
            Notification
                .show(message, 8000, Notification.Position.BOTTOM_START)
                .addThemeVariants(NotificationVariant.LUMO_ERROR)
        }
    }
}
