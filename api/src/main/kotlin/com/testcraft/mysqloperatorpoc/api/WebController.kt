package com.testcraft.mysqloperatorpoc.api

import com.testcraft.mysqloperatorpoc.operator.common.ImageSpec
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.CloneSourceSpec
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.InitStrategy
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.MySQLInstance
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.MySQLSpec
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.ResourceQuantity
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.ResourceSpec
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.StorageSpec
import io.fabric8.kubernetes.client.KubernetesClient
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class WebController(
    private val client: KubernetesClient,
    private val opsService: MySQLInstanceOpsService,
    private val validationService: MySQLInstanceValidationService,
    private val crdService: CrdService,
    private val sourceBootstrapService: SourceMySQLBootstrapService,
) {
    private data class VerifyView(
        val tablesText: String,
        val countText: String,
        val headers: List<String>,
        val rows: List<List<String>>,
        val message: String,
    )

    private fun resolveNamespace(input: String?): String =
        input?.takeIf { it.isNotBlank() } ?: "default"

    private fun parseVerifyOutput(output: String?): VerifyView? {
        if (output.isNullOrBlank()) {
            return null
        }
        val lines = output
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it == "mysqld is alive" }

        if (lines.isEmpty()) {
            return null
        }

        val countIndex = lines.indexOf("count")
        val rawTables = if (countIndex >= 0) lines.subList(0, countIndex) else lines
        val tables = rawTables.filterNot {
            it.startsWith("Database not found") ||
                it.startsWith("No tables found") ||
                it.startsWith("No table found") ||
                it.startsWith("Table not found")
        }
        val countValue = if (countIndex >= 0) lines.getOrNull(countIndex + 1) else null
        val rowLines = if (countIndex >= 0) lines.drop(countIndex + 2) else emptyList()

        val headers = if (rowLines.isNotEmpty()) rowLines.first().split(Regex("\\s+")) else emptyList()
        val rows = if (rowLines.size > 1) rowLines.drop(1).map { it.split(Regex("\\s+")) } else emptyList()

        val message = lines.firstOrNull {
            it.startsWith("Database not found") ||
                it.startsWith("No tables found") ||
                it.startsWith("No table found") ||
                it.startsWith("Table not found")
        } ?: ""

        val tablesText = if (tables.isEmpty()) "N/A" else tables.joinToString(", ")
        val countText = countValue ?: "N/A"

        return VerifyView(
            tablesText = tablesText,
            countText = countText,
            headers = headers,
            rows = rows,
            message = message,
        )
    }

    @GetMapping("/")
    /** Render the demo UI with current CR status and list. */
    fun index(
        @RequestParam(required = false) name: String?,
        @RequestParam(required = false) namespace: String?,
        @RequestParam(required = false) message: String?,
        @RequestParam(required = false) verifyOutput: String?,
        model: Model,
    ): String {
        val resourceName = name ?: "mysql-demo"
        val resolvedNamespace = resolveNamespace(namespace)
        val resource = runCatching {
            client.resources(MySQLInstance::class.java)
                .inNamespace(resolvedNamespace)
                .withName(resourceName)
                .get()
        }.getOrNull()
        val instances = runCatching {
            client.resources(MySQLInstance::class.java)
                .inNamespace(resolvedNamespace)
                .list()
                .items
        }.getOrDefault(emptyList())
        val statefulSets = runCatching {
            client.apps().statefulSets().inNamespace(resolvedNamespace).list().items
        }.getOrDefault(emptyList())
        val statefulSetByName = statefulSets
            .filter { it.metadata?.name != null }
            .associateBy { it.metadata.name }
        val currentStatefulSet = resource?.metadata?.name?.let { statefulSetByName[it] }
            ?: statefulSetByName[resourceName]

        model.addAttribute("name", resourceName)
        model.addAttribute("namespace", resolvedNamespace)
        model.addAttribute("message", message ?: "")
        model.addAttribute("status", resource?.status)
        model.addAttribute("spec", resource?.spec)
        model.addAttribute("instances", instances)
        model.addAttribute("statefulSets", statefulSetByName)
        model.addAttribute("currentStatefulSet", currentStatefulSet)
        model.addAttribute("verifyOutput", verifyOutput ?: "")
        model.addAttribute("verifyView", parseVerifyOutput(verifyOutput))
        return "index"
    }

    @PostMapping("/ui/create")
    /** Create or replace a MySQLInstance from UI inputs. */
    fun create(
        @RequestParam name: String,
        @RequestParam namespace: String,
        @RequestParam(required = false) replicas: Int?,
        @RequestParam(required = false) cpu: String?,
        @RequestParam(required = false) memory: String?,
        @RequestParam(required = false) storage: String?,
        @RequestParam(required = false) imageTag: String?,
    ): String {
        crdService.ensureMySQLInstanceCrd()
        val resolvedNamespace = resolveNamespace(namespace)
        val existing = client.resources(MySQLInstance::class.java)
            .inNamespace(resolvedNamespace)
            .withName(name)
            .get()
        val baseSpec = existing?.spec ?: MySQLSpec()
        val resources = ResourceSpec(
            limits = ResourceQuantity(
                cpu = cpu?.takeIf { it.isNotBlank() } ?: baseSpec.resources.limits.cpu,
                memory = memory?.takeIf { it.isNotBlank() } ?: baseSpec.resources.limits.memory,
            ),
            requests = baseSpec.resources.requests,
        )
        val spec = baseSpec.copy(
            image = baseSpec.image.copy(tag = imageTag?.takeIf { it.isNotBlank() } ?: baseSpec.image.tag),
            replicas = replicas ?: baseSpec.replicas,
            resources = resources,
            storage = StorageSpec(size = storage?.takeIf { it.isNotBlank() } ?: baseSpec.storage.size),
        )
        val validation = validationService.validateForApply(name, resolvedNamespace, spec)
        if (validation.errors.isNotEmpty()) {
            val errorMessage = validation.errors.joinToString(" | ")
            return "redirect:/?name=${name}&namespace=${resolvedNamespace}&message=apply-blocked:${errorMessage}"
        }

        val bootstrapMessage = sourceBootstrapService.ensureSourceMySQL(resolvedNamespace)

        val resource = MySQLInstance().apply {
            metadata.name = name
            metadata.namespace = resolvedNamespace
            this.spec = spec
        }

        client.resources(MySQLInstance::class.java)
            .inNamespace(resolvedNamespace)
            .resource(resource)
            .createOrReplace()

        val warningMessage = listOfNotNull(
            validation.warnings.joinToString(" | ").takeIf { it.isNotBlank() },
            bootstrapMessage?.takeIf { it.isNotBlank() },
        ).joinToString(" | ")
        val suffix = if (warningMessage.isBlank()) "created" else "created:${warningMessage}"
        return "redirect:/?name=${name}&namespace=${resolvedNamespace}&message=${suffix}"
    }

    @PostMapping("/ui/restart")
    /** Trigger rolling restart from UI. */
    fun restart(
        @RequestParam name: String,
        @RequestParam namespace: String,
    ): String {
        val resolvedNamespace = resolveNamespace(namespace)
        opsService.triggerRestart(name, resolvedNamespace)

        return "redirect:/?name=${name}&namespace=${resolvedNamespace}&message=restart-triggered"
    }

    @PostMapping("/ui/reset")
    /** Trigger reset action from UI. */
    fun reset(
        @RequestParam name: String,
        @RequestParam namespace: String,
        @RequestParam action: String,
    ): String {
        val resolvedNamespace = resolveNamespace(namespace)
        opsService.triggerReset(name, resolvedNamespace, action)

        return "redirect:/?name=${name}&namespace=${resolvedNamespace}&message=reset-${action}"
    }

    @PostMapping("/ui/clone")
    /** Trigger clone from UI. */
    fun clone(
        @RequestParam name: String,
        @RequestParam namespace: String,
        @RequestParam initStrategy: String,
        @RequestParam sourceHost: String,
        @RequestParam sourcePort: Int,
        @RequestParam sourceUser: String,
        @RequestParam sourcePassword: String,
        @RequestParam sourceDatabase: String,
    ): String {
        val resolvedNamespace = resolveNamespace(namespace)
        client.resources(MySQLInstance::class.java)
            .inNamespace(resolvedNamespace)
            .withName(name)
            .edit { current ->
                val currentSpec = current.spec ?: MySQLSpec()
                val metadata = requireNotNull(current.metadata)
                val annotations = metadata.annotations?.toMutableMap() ?: mutableMapOf()
                annotations["action.mysql.sandbox/clone"] = System.currentTimeMillis().toString()
                metadata.annotations = annotations
                current.spec = currentSpec.copy(
                    initStrategy = InitStrategy.valueOf(initStrategy),
                    cloneSource = CloneSourceSpec(
                        host = sourceHost,
                        port = sourcePort,
                        username = sourceUser,
                        password = sourcePassword,
                        database = sourceDatabase,
                    )
                )
                current
            }

        return "redirect:/?name=${name}&namespace=${resolvedNamespace}&message=clone-started"
    }

    @PostMapping("/ui/verify")
    /** Run verification query and show output. */
    fun verify(
        @RequestParam name: String,
        @RequestParam namespace: String,
        @RequestParam(required = false) database: String?,
        @RequestParam(required = false) table: String?,
        model: Model,
    ): String {
        val resolvedNamespace = resolveNamespace(namespace)
        val db = database?.takeIf { it.isNotBlank() } ?: "testcraft"
        val tbl = when {
            table == null -> "users"
            table.isBlank() -> ""
            else -> table
        }
        val output = opsService.verifyData(name, resolvedNamespace, db, tbl)
        return index(name, resolvedNamespace, "verify-complete", output, model)
    }
}
