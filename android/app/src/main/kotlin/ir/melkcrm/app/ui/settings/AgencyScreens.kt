package ir.melkcrm.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.melkcrm.app.AppContainer
import ir.melkcrm.app.R
import ir.melkcrm.app.ui.LocalApp
import ir.melkcrm.app.ui.LocalFormat
import ir.melkcrm.app.ui.Msg
import ir.melkcrm.app.ui.UiFormat
import ir.melkcrm.app.ui.components.Banner
import ir.melkcrm.app.ui.components.ConfirmDialog
import ir.melkcrm.app.ui.components.EnumDropdown
import ir.melkcrm.app.ui.components.FormColumn
import ir.melkcrm.app.ui.components.MoneyInput
import ir.melkcrm.app.ui.components.NumberInput
import ir.melkcrm.app.ui.components.PasswordInput
import ir.melkcrm.app.ui.components.ScreenScaffold
import ir.melkcrm.app.ui.components.SectionCard
import ir.melkcrm.app.ui.components.SwitchRow
import ir.melkcrm.app.ui.components.TextInput
import ir.melkcrm.app.ui.msgText
import ir.melkcrm.app.ui.nav.Nav
import ir.melkcrm.client.forms.CommissionRuleForm
import ir.melkcrm.client.forms.FormResult
import ir.melkcrm.client.forms.Input
import ir.melkcrm.client.forms.Parsed
import ir.melkcrm.client.remote.RemoteException
import ir.melkcrm.core.api.CreateUserRequest
import ir.melkcrm.core.api.UpdateUserRequest
import ir.melkcrm.core.commission.CommissionCalculator
import ir.melkcrm.core.commission.CommissionRule
import ir.melkcrm.core.matching.MatchComponent
import ir.melkcrm.core.matching.MatchThresholds
import ir.melkcrm.core.matching.MatchWeights
import ir.melkcrm.core.matching.MatchingConfig
import ir.melkcrm.core.model.AgencySettings
import ir.melkcrm.core.model.AgencyUser
import ir.melkcrm.core.model.CommissionParty
import ir.melkcrm.core.model.DealType
import ir.melkcrm.core.model.UserRole
import ir.melkcrm.core.permissions.Permission
import ir.melkcrm.core.validation.PasswordPolicy
import kotlinx.coroutines.launch

/**
 * Saves agency configuration through the session (the server validates and enforces who may change it).
 * Returns true when saved; failures are reported in the snackbar.
 */
private suspend fun AppContainer.saveAgencyConfig(settings: AgencySettings, matching: MatchingConfig): Boolean = try {
    crm.session.updateAgencyConfig(settings, matching)
    say(Msg.Res(R.string.saved))
    true
} catch (e: RemoteException) {
    say(if (e.isForbidden) Msg.Res(R.string.no_permission) else Msg.Code("srv_", e.code))
    false
} catch (e: IllegalArgumentException) {
    say(Msg.Wrapped(R.string.weights_invalid, Msg.Text(e.message.orEmpty())))
    false
}

private fun parseInt(text: String, min: Int, max: Int): Int? = (Input.int(text, min, max) as? Parsed.Ok)?.value

// ---------------------------------------------------------------------------------------- matching weights

@Composable
fun MatchingSettingsScreen(nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val agency by app.crm.session.agency.collectAsStateWithLifecycle()
    val user = app.crm.session.user
    val canEdit = agency.policy.canConfigureMatching(user)
    val scope = rememberCoroutineScope()
    val current = agency.matching
    var weights by remember(current) { mutableStateOf(MatchComponent.entries.associateWith { fmt.num(current.weights.of(it)) }) }
    var excellent by remember(current) { mutableStateOf(fmt.num(current.thresholds.excellent)) }
    var strong by remember(current) { mutableStateOf(fmt.num(current.thresholds.strong)) }
    var possible by remember(current) { mutableStateOf(fmt.num(current.thresholds.possible)) }
    var tolerance by remember(current) { mutableStateOf(fmt.num(current.overBudgetTolerancePercent)) }
    var saving by remember { mutableStateOf(false) }

    val parsed = MatchComponent.entries.associateWith { parseInt(weights[it].orEmpty(), 0, 100) }
    val w = if (parsed.values.any { it == null }) null else MatchWeights(
        budget = parsed.getValue(MatchComponent.BUDGET)!!, location = parsed.getValue(MatchComponent.LOCATION)!!,
        layout = parsed.getValue(MatchComponent.LAYOUT)!!, features = parsed.getValue(MatchComponent.FEATURES)!!,
        timing = parsed.getValue(MatchComponent.TIMING)!!, flexibility = parsed.getValue(MatchComponent.FLEXIBILITY)!!,
        readiness = parsed.getValue(MatchComponent.READINESS)!!,
    )
    val ex = parseInt(excellent, 1, 100)
    val st = parseInt(strong, 1, 99)
    val po = parseInt(possible, 1, 99)
    val tol = parseInt(tolerance, 0, 50)
    val problems = buildList {
        if (w == null) add(R.string.weights_whole_numbers)
        else {
            if (w.total != 100) add(R.string.weights_must_total)
            if (w.readiness > 15) add(R.string.weights_readiness_cap)
        }
        if (ex == null || st == null || po == null || !(po < st && st < ex)) add(R.string.thresholds_order)
        if (tol == null) add(R.string.tolerance_range)
    }

    ScreenScaffold(title = stringResource(R.string.settings_matching), onBack = { nav.back() }) { padding ->
        FormColumn(padding) {
            if (!canEdit) Banner(stringResource(R.string.settings_admin_only))
            Text(stringResource(R.string.matching_weights_note), style = MaterialTheme.typography.bodySmall)
            SectionCard(stringResource(R.string.weights_section)) {
                for (c in MatchComponent.entries) {
                    NumberInput(weights[c].orEmpty(), { v -> weights = weights + (c to v) }, fmt.label(c), enabled = canEdit)
                }
                Text(
                    stringResource(R.string.weights_total, w?.total ?: 0),
                    color = if (w?.total == 100) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            SectionCard(stringResource(R.string.thresholds_section)) {
                NumberInput(excellent, { excellent = it }, stringResource(R.string.threshold_excellent), enabled = canEdit)
                NumberInput(strong, { strong = it }, stringResource(R.string.threshold_strong), enabled = canEdit)
                NumberInput(possible, { possible = it }, stringResource(R.string.threshold_possible), enabled = canEdit)
                Text(stringResource(R.string.thresholds_note), style = MaterialTheme.typography.bodySmall)
            }
            SectionCard(null) {
                NumberInput(tolerance, { tolerance = it }, stringResource(R.string.over_budget_tolerance), enabled = canEdit)
                Text(stringResource(R.string.over_budget_tolerance_note), style = MaterialTheme.typography.bodySmall)
            }
            for (p in problems) Text(stringResource(p), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            if (canEdit) {
                Button(
                    onClick = {
                        if (w == null || ex == null || st == null || po == null || tol == null) return@Button
                        saving = true
                        scope.launch {
                            val config = current.copy(weights = w, thresholds = MatchThresholds(ex, st, po), overBudgetTolerancePercent = tol)
                            if (app.saveAgencyConfig(agency.settings, config)) app.crm.matching.recalculateAll()
                            saving = false
                        }
                    },
                    enabled = problems.isEmpty() && !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.save)) }
                Text(stringResource(R.string.matching_recalc_note), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    val d = MatchingConfig()
                    weights = MatchComponent.entries.associateWith { fmt.num(d.weights.of(it)) }
                    excellent = fmt.num(d.thresholds.excellent)
                    strong = fmt.num(d.thresholds.strong)
                    possible = fmt.num(d.thresholds.possible)
                    tolerance = fmt.num(d.overBudgetTolerancePercent)
                }) { Text(stringResource(R.string.weights_reset)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------- commission rules

/** A one-line description of a rule's rates, e.g. "1% of price · tax 10%". */
private fun ruleSummary(r: CommissionRule, fmt: UiFormat, labels: Map<String, String>): String = listOfNotNull(
    r.priceBasisPoints.takeIf { it > 0 }?.let { labels.getValue("price") + " " + percent(it, fmt) },
    r.tiers.takeIf { it.isNotEmpty() }?.let { labels.getValue("tiers") + " " + fmt.num(it.size) },
    r.depositBasisPoints.takeIf { it > 0 }?.let { labels.getValue("deposit") + " " + percent(it, fmt) },
    r.monthlyRentBasisPoints.takeIf { it > 0 }?.let { labels.getValue("rent") + " " + percent(it, fmt) },
    r.fixedAmount?.let { labels.getValue("fixed") + " " + fmt.money(it) },
    r.minimum?.let { labels.getValue("min") + " " + fmt.money(it) },
    r.maximum?.let { labels.getValue("max") + " " + fmt.money(it) },
    r.taxBasisPoints.takeIf { it > 0 }?.let { labels.getValue("tax") + " " + percent(it, fmt) },
).joinToString(" · ")

private fun percent(bp: Int, fmt: UiFormat) = fmt.num(bp / 100.0) + (if (fmt.fa) "٪" else "%")

@Composable
fun CommissionRulesScreen(nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val agency by app.crm.session.agency.collectAsStateWithLifecycle()
    val user = app.crm.session.user
    val canEdit = agency.policy.has(user, Permission.MANAGE_COMMISSION_RULES)
    val scope = rememberCoroutineScope()
    val rules = agency.settings.commissionRules
    var editing by remember { mutableStateOf<CommissionRule?>(null) }
    var deleting by remember { mutableStateOf<CommissionRule?>(null) }
    val labels = mapOf(
        "price" to stringResource(R.string.commission_price_rate), "tiers" to stringResource(R.string.commission_tiers),
        "deposit" to stringResource(R.string.commission_deposit_rate), "rent" to stringResource(R.string.commission_rent_rate),
        "fixed" to stringResource(R.string.commission_fixed), "min" to stringResource(R.string.commission_min),
        "max" to stringResource(R.string.commission_max), "tax" to stringResource(R.string.commission_tax),
    )

    fun saveRules(list: List<CommissionRule>, after: () -> Unit = {}) {
        scope.launch { if (app.saveAgencyConfig(agency.settings.copy(commissionRules = list), agency.matching)) after() }
    }

    ScreenScaffold(title = stringResource(R.string.commission_rules_title), onBack = { nav.back() }) { padding ->
        FormColumn(padding) {
            Text(stringResource(R.string.commission_rules_note), style = MaterialTheme.typography.bodySmall)
            if (!canEdit) Banner(stringResource(R.string.settings_admin_only))
            val e = editing
            if (e != null) {
                RuleEditor(e, onCancel = { editing = null }) { rule ->
                    val list = if (rules.any { it.id == rule.id }) rules.map { if (it.id == rule.id) rule else it } else rules + rule
                    saveRules(list) { editing = null }
                }
            } else {
                SectionCard(null) {
                    if (rules.isEmpty()) Text(stringResource(R.string.commission_rules_empty), style = MaterialTheme.typography.bodyMedium)
                    for (r in rules) {
                        ListItem(
                            modifier = if (canEdit) Modifier.clickable { editing = r } else Modifier,
                            headlineContent = { Text(r.name + if (r.active) "" else " (" + stringResource(R.string.inactive) + ")") },
                            overlineContent = { Text(fmt.label(r.dealType) + " · " + stringResource(R.string.commission_party) + ": " + fmt.label(r.party)) },
                            supportingContent = { Text(ruleSummary(r, fmt, labels)) },
                            trailingContent = if (canEdit) {
                                { TextButton(onClick = { deleting = r }) { Text(stringResource(R.string.delete)) } }
                            } else {
                                null
                            },
                        )
                    }
                    if (canEdit) {
                        Button(
                            onClick = {
                                editing = CommissionRule(id = java.util.UUID.randomUUID().toString(), agencyId = user.agencyId, name = "", dealType = DealType.SALE, party = CommissionParty.BUYER)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.commission_rule_new)) }
                    }
                }
            }
        }
    }

    val d = deleting
    if (d != null) {
        ConfirmDialog(
            title = stringResource(R.string.delete),
            text = stringResource(R.string.commission_rule_delete_note, d.name),
            confirm = stringResource(R.string.delete),
            onConfirm = {
                deleting = null
                saveRules(rules.filter { it.id != d.id })
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun RuleEditor(base: CommissionRule, onCancel: () -> Unit, onSave: (CommissionRule) -> Unit) {
    val fmt = LocalFormat.current
    var form by remember(base.id) { mutableStateOf(CommissionRuleForm.from(base, fmt.unit, fmt.digits)) }
    var errors by remember(base.id) { mutableStateOf(emptyMap<String, String>()) }
    var example by remember(base.id) { mutableStateOf("") }
    SectionCard(stringResource(if (base.name.isEmpty()) R.string.commission_rule_new else R.string.commission_rule)) {
        TextInput(form.name, { v -> form = form.copy(name = v) }, stringResource(R.string.commission_rule_name), error = errors["name"])
        EnumDropdown(stringResource(R.string.field_deal_type), DealType.entries, form.dealType, { v -> if (v != null) form = form.copy(dealType = v) }, allowNone = false)
        EnumDropdown(stringResource(R.string.commission_party), CommissionParty.entries, form.party, { v -> if (v != null) form = form.copy(party = v) }, allowNone = false)
        when (form.dealType) {
            DealType.SALE -> NumberInput(form.pricePercent, { v -> form = form.copy(pricePercent = v) }, stringResource(R.string.commission_price_rate), error = errors["priceBasisPoints"], decimal = true, suffix = "%")
            DealType.RENT -> {
                NumberInput(form.depositPercent, { v -> form = form.copy(depositPercent = v) }, stringResource(R.string.commission_deposit_rate), error = errors["depositBasisPoints"], decimal = true, suffix = "%")
                NumberInput(form.rentPercent, { v -> form = form.copy(rentPercent = v) }, stringResource(R.string.commission_rent_rate), error = errors["monthlyRentBasisPoints"], decimal = true, suffix = "%")
            }
            DealType.FULL_DEPOSIT -> NumberInput(form.depositPercent, { v -> form = form.copy(depositPercent = v) }, stringResource(R.string.commission_deposit_rate), error = errors["depositBasisPoints"], decimal = true, suffix = "%")
        }
        if (base.tiers.isNotEmpty()) Text(stringResource(R.string.commission_tiers_note, base.tiers.size), style = MaterialTheme.typography.bodySmall)
        MoneyInput(form.fixed, { v -> form = form.copy(fixed = v) }, stringResource(R.string.commission_fixed), error = errors["fixedAmount"])
        MoneyInput(form.minimum, { v -> form = form.copy(minimum = v) }, stringResource(R.string.commission_min), error = errors["minimum"])
        MoneyInput(form.maximum, { v -> form = form.copy(maximum = v) }, stringResource(R.string.commission_max), error = errors["maximum"])
        NumberInput(form.taxPercent, { v -> form = form.copy(taxPercent = v) }, stringResource(R.string.commission_tax), error = errors["taxBasisPoints"], decimal = true, suffix = "%")
        SwitchRow(stringResource(R.string.user_active), form.active, { v -> form = form.copy(active = v) })

        // Worked example so the agent can check the rule before saving; it is an estimate only.
        MoneyInput(example, { example = it }, stringResource(if (form.dealType == DealType.SALE) R.string.commission_example_price else R.string.commission_example_deposit))
        val built = form.build(base, fmt.unit)
        val amount = (Input.money(example, fmt.unit) as? Parsed.Ok)?.value
        if (built is FormResult.Ok && amount != null) {
            val est = when (form.dealType) {
                DealType.SALE -> CommissionCalculator.estimate(built.value, price = amount)
                else -> CommissionCalculator.estimate(built.value, deposit = amount)
            }
            Text(stringResource(R.string.commission_example_result, fmt.money(est.base), fmt.money(est.tax), fmt.money(est.total)), style = MaterialTheme.typography.bodyMedium)
        }
        Row {
            Button(onClick = {
                when (val b = form.build(base, fmt.unit)) {
                    is FormResult.Errors -> errors = b.errors
                    is FormResult.Ok -> {
                        errors = emptyMap()
                        onSave(b.value)
                    }
                }
            }, modifier = Modifier.weight(1f).padding(end = 4.dp)) { Text(stringResource(R.string.save)) }
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).padding(start = 4.dp)) { Text(stringResource(R.string.cancel)) }
        }
    }
}

// ---------------------------------------------------------------------------------------- users and agency policy

@Composable
fun UsersScreen(nav: Nav) {
    val app = LocalApp.current
    val fmt = LocalFormat.current
    val agency by app.crm.session.agency.collectAsStateWithLifecycle()
    val session by app.crm.session.session.collectAsStateWithLifecycle()
    val user = session.user
    val scope = rememberCoroutineScope()
    val canManageSettings = agency.policy.has(user, Permission.MANAGE_SETTINGS)
    val manageAccounts = session.signedIn && !session.authExpired && agency.policy.canManageUsers(user)
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(UserRole.AGENT) }
    var team by remember { mutableStateOf("") }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var accountError by remember { mutableStateOf<Msg?>(null) }
    var editing by remember { mutableStateOf<AgencyUser?>(null) }

    fun saveLocal(users: List<AgencyUser>) {
        scope.launch { app.crm.session.setLocalUsers(users.filter { it.id != user.id }) }
    }

    ScreenScaffold(title = stringResource(R.string.settings_users), onBack = { nav.back() }) { padding ->
        FormColumn(padding) {
            Text(stringResource(if (session.signedIn) R.string.users_server_note else R.string.users_local_note), style = MaterialTheme.typography.bodySmall)
            SectionCard(null) {
                for (u in agency.users) {
                    val editable = manageAccounts && u.id != user.id
                    ListItem(
                        modifier = if (editable) Modifier.clickable { editing = u } else Modifier,
                        headlineContent = { Text(u.displayName + if (u.id == user.id) " (" + stringResource(R.string.you) + ")" else "") },
                        supportingContent = {
                            Text(listOfNotNull(fmt.label(u.role), u.teamId?.let { stringResource(R.string.user_team) + ": " + it }, if (u.active) null else stringResource(R.string.inactive)).joinToString(" · "))
                        },
                        trailingContent = if (!session.signedIn && u.id != user.id) {
                            {
                                androidx.compose.material3.Switch(checked = u.active, onCheckedChange = { v ->
                                    saveLocal(agency.users.map { if (it.id == u.id) it.copy(active = v) else it })
                                })
                            }
                        } else {
                            null
                        },
                    )
                }
                if (session.signedIn) {
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                app.crm.session.refreshAgency()
                                app.say(Msg.Res(R.string.users_refreshed))
                            } catch (e: RemoteException) {
                                app.say(Msg.Code("srv_", e.code))
                            }
                        }
                    }) { Text(stringResource(R.string.refresh)) }
                }
            }

            if (manageAccounts) {
                SectionCard(stringResource(R.string.account_create)) {
                    TextInput(name, { name = it }, stringResource(R.string.user_display_name))
                    TextInput(login, { login = it }, stringResource(R.string.settings_login))
                    PasswordInput(
                        password, { password = it }, stringResource(R.string.account_initial_password),
                        supporting = stringResource(R.string.password_rule, PasswordPolicy.MIN_LENGTH),
                    )
                    EnumDropdown(stringResource(R.string.user_role), UserRole.entries, role, { v -> if (v != null) role = v }, allowNone = false)
                    TextInput(team, { team = it }, stringResource(R.string.user_team))
                    accountError?.let { Text(msgText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    Text(stringResource(R.string.account_create_note), style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            val problem = PasswordPolicy.problem(password)
                            if (problem != null) accountError = Msg.Code("srv_", problem)
                            else scope.launch {
                                try {
                                    app.crm.session.createUser(
                                        CreateUserRequest(login.trim(), password, name.trim(), role, team.trim().ifBlank { null }),
                                    )
                                    name = ""
                                    login = ""
                                    password = ""
                                    team = ""
                                    accountError = null
                                    app.say(Msg.Res(R.string.account_created))
                                } catch (e: RemoteException) {
                                    accountError = Msg.Code("srv_", if (e.isNetwork) "network" else e.code)
                                }
                            }
                        },
                        enabled = name.isNotBlank() && login.isNotBlank() && password.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.add)) }
                }
            }

            if (!session.signedIn) {
                SectionCard(stringResource(R.string.user_add)) {
                    TextInput(name, { name = it }, stringResource(R.string.user_display_name))
                    EnumDropdown(stringResource(R.string.user_role), UserRole.entries, role, { v -> if (v != null) role = v }, allowNone = false)
                    TextInput(team, { team = it }, stringResource(R.string.user_team))
                    Button(
                        onClick = {
                            val u = AgencyUser(
                                id = java.util.UUID.randomUUID().toString(), agencyId = user.agencyId, displayName = name.trim(), role = role,
                                teamId = team.trim().ifBlank { null }, createdAt = System.currentTimeMillis(),
                            )
                            saveLocal(agency.users + u)
                            name = ""
                            team = ""
                        },
                        enabled = name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.add)) }
                }
            }

            SectionCard(stringResource(R.string.agency_policy)) {
                if (!canManageSettings) Text(stringResource(R.string.settings_admin_only), style = MaterialTheme.typography.bodySmall)
                val s = agency.settings
                SwitchRow(stringResource(R.string.policy_see_inventory), s.agentsSeeAgencyInventory, { v ->
                    scope.launch { app.saveAgencyConfig(s.copy(agentsSeeAgencyInventory = v), agency.matching) }
                }, enabled = canManageSettings)
                SwitchRow(stringResource(R.string.policy_see_customers), s.agentsSeeAgencyCustomers, { v ->
                    scope.launch { app.saveAgencyConfig(s.copy(agentsSeeAgencyCustomers = v), agency.matching) }
                }, supporting = stringResource(R.string.policy_see_customers_note), enabled = canManageSettings)
                SwitchRow(stringResource(R.string.policy_agents_export), s.agentsCanExport, { v ->
                    scope.launch { app.saveAgencyConfig(s.copy(agentsCanExport = v), agency.matching) }
                }, enabled = canManageSettings)
                Text(stringResource(R.string.policy_enforced_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    editing?.let { target ->
        AccountDialog(target, onDismiss = { editing = null }) { request ->
            scope.launch {
                try {
                    app.crm.session.updateUser(target.id, request)
                    editing = null
                    app.say(Msg.Res(R.string.saved))
                } catch (e: RemoteException) {
                    app.say(Msg.Code("srv_", if (e.isNetwork) "network" else e.code))
                }
            }
        }
    }
}

/** Edits another server account. The server re-checks the permission and refuses changes to one's own role. */
@Composable
private fun AccountDialog(target: AgencyUser, onDismiss: () -> Unit, onSave: (UpdateUserRequest) -> Unit) {
    var name by remember(target.id) { mutableStateOf(target.displayName) }
    var role by remember(target.id) { mutableStateOf(target.role) }
    var team by remember(target.id) { mutableStateOf(target.teamId.orEmpty()) }
    var active by remember(target.id) { mutableStateOf(target.active) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_edit)) },
        text = {
            Column {
                TextInput(name, { name = it }, stringResource(R.string.user_display_name))
                EnumDropdown(stringResource(R.string.user_role), UserRole.entries, role, { v -> if (v != null) role = v }, allowNone = false)
                TextInput(team, { team = it }, stringResource(R.string.user_team))
                SwitchRow(stringResource(R.string.user_active), active, { active = it }, supporting = stringResource(R.string.account_deactivate_note))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(UpdateUserRequest(name.trim(), role, team.trim().ifBlank { null }, active)) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
