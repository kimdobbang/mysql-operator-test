package com.testcraft.mysqloperatorpoc.api

import com.testcraft.mysqloperatorpoc.operator.resource.mysql.MySQLInstance
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.MySQLSpec
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.ResourceSpec
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.StorageSpec
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.client.KubernetesClient
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/mysqlinstances")
class MySQLInstanceController(
    private val client: KubernetesClient,
    private val opsService: MySQLInstanceOpsService,
    private val validationService: MySQLInstanceValidationService,
    private val crdService: CrdService,
) {
    private fun resolveNamespace(input: String?): String =
        input?.takeIf { it.isNotBlank() } ?: "default"

    /** List MySQLInstance CRs in a namespace. */
    @GetMapping
    fun list(@RequestParam(required = false) namespace: String?): List<MySQLInstanceSummary> {
        val resolved = resolveNamespace(namespace)
        val items = runCatching {
            client.resources(MySQLInstance::class.java)
                .inNamespace(resolved)
                .list()
                .items
        }.getOrDefault(emptyList())

        return items.map { resource ->
            val status = resource.status
            MySQLInstanceSummary(
                name = resource.metadata.name,
                namespace = resolved,
                ready = status?.ready,
                phase = status?.phase,
                message = status?.message,
            )
        }
    }

    /** Get MySQLInstance status summary. */
    @GetMapping("/{name}/status")
    fun status(
        @PathVariable name: String,
        @RequestParam(required = false) namespace: String?,
    ): MySQLInstanceStatusResponse {
        val resolved = resolveNamespace(namespace)
        val resource = client.resources(MySQLInstance::class.java)
            .inNamespace(resolved)
            .withName(name)
            .get()
            ?: return MySQLInstanceStatusResponse(
                name = name,
                namespace = resolved,
                ready = null,
                phase = null,
                lastPhaseTime = null,
                message = "Not found",
                clonePhase = null,
                resetPhase = null,
                serviceName = null,
                lastCloneTime = null,
                lastResetTime = null,
            )

        val status = resource.status
        return MySQLInstanceStatusResponse(
            name = name,
            namespace = resolved,
            ready = status?.ready,
            phase = status?.phase,
            lastPhaseTime = status?.lastPhaseTime?.toString(),
            message = status?.message,
            clonePhase = status?.clonePhase,
            resetPhase = status?.resetPhase,
            serviceName = status?.serviceName,
            lastCloneTime = status?.lastCloneTime?.toString(),
            lastResetTime = status?.lastResetTime?.toString(),
        )
    }

    /** Get related Kubernetes resource status for an instance. */
    @GetMapping("/{name}/resources")
    fun resources(
        @PathVariable name: String,
        @RequestParam(required = false) namespace: String?,
    ): MySQLInstanceResourcesResponse {
        val resolved = resolveNamespace(namespace)
        val statefulSet = runCatching {
            client.apps().statefulSets()
                .inNamespace(resolved)
                .withName(name)
                .get()
        }.getOrNull()
        val service = runCatching {
            client.services().inNamespace(resolved).withName(name).get()
        }.getOrNull()
        val pods = runCatching {
            client.pods().inNamespace(resolved).withLabel("app", name).list().items
        }.getOrDefault(emptyList())
        val pvcs = runCatching {
            client.persistentVolumeClaims().inNamespace(resolved).list().items
        }.getOrDefault(emptyList())
            .filter { it.metadata?.name?.startsWith("mysql-data-${name}-") == true }
        val cloneJob = runCatching {
            client.batch().v1().jobs().inNamespace(resolved).withName("${name}-clone").get()
        }.getOrNull()
        val resetJob = runCatching {
            client.batch().v1().jobs().inNamespace(resolved).withName("${name}-reset").get()
        }.getOrNull()

        return MySQLInstanceResourcesResponse(
            name = name,
            namespace = resolved,
            statefulSetName = statefulSet?.metadata?.name,
            readyReplicas = statefulSet?.status?.readyReplicas,
            serviceName = service?.metadata?.name,
            podNames = pods.mapNotNull { it.metadata?.name },
            pvcNames = pvcs.mapNotNull { it.metadata?.name },
            cloneJobStatus = cloneJob?.status?.let { "succeeded=${it.succeeded ?: 0}, failed=${it.failed ?: 0}" },
            resetJobStatus = resetJob?.status?.let { "succeeded=${it.succeeded ?: 0}, failed=${it.failed ?: 0}" },
        )
    }

    /** Verify target DB tables and row counts for demo validation. */
    @GetMapping("/{name}/verify")
    fun verify(
        @PathVariable name: String,
        @RequestParam(required = false) namespace: String?,
        @RequestParam(required = false) database: String?,
        @RequestParam(required = false) table: String?,
    ): Map<String, String> {
        val resolved = resolveNamespace(namespace)
        val db = database?.takeIf { it.isNotBlank() } ?: "testcraft"
        val tbl = when {
            table == null -> "users"
            table.isBlank() -> ""
            else -> table
        }
        val output = opsService.verifyData(name, resolved, db, tbl)
        return mapOf(
            "name" to name,
            "namespace" to resolved,
            "database" to db,
            "table" to tbl,
            "output" to output
        )
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    /** Create or replace a MySQLInstance CR. */
    fun create(@RequestBody request: MySQLInstanceCreateRequest): Map<String, String> {
        crdService.ensureMySQLInstanceCrd()
        val baseSpec = MySQLSpec()
        val spec = baseSpec.copy(
            image = request.image ?: baseSpec.image,
            replicas = request.replicas ?: baseSpec.replicas,
            port = request.port ?: baseSpec.port,
            database = request.database ?: baseSpec.database,
            rootPassword = request.rootPassword ?: baseSpec.rootPassword,
            resources = request.resources ?: baseSpec.resources,
            storage = request.storage ?: baseSpec.storage,
            mysqlConfig = request.mysqlConfig ?: baseSpec.mysqlConfig,
            initStrategy = request.initStrategy ?: baseSpec.initStrategy,
            cloneSource = request.cloneSource ?: baseSpec.cloneSource,
        )

        val namespace = resolveNamespace(request.namespace)
        val validation = validationService.validateForApply(request.name, namespace, spec)
        if (validation.errors.isNotEmpty()) {
            throw org.springframework.web.server.ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                validation.errors.joinToString(" | ")
            )
        }
        val resource = MySQLInstance().apply {
            if (metadata == null) {
                metadata = ObjectMeta()
            }
            metadata.name = request.name
            metadata.namespace = namespace
            this.spec = spec
        }

        client.resources(MySQLInstance::class.java)
            .inNamespace(namespace)
            .resource(resource)
            .createOrReplace()

        val response = mutableMapOf("name" to request.name, "namespace" to namespace)
        if (validation.warnings.isNotEmpty()) {
            response["warnings"] = validation.warnings.joinToString(" | ")
        }
        return response
    }

    @PutMapping("/{name}/storage")
    /** Update storage size for a MySQLInstance. */
    fun updateStorage(
        @PathVariable name: String,
        @RequestBody request: StorageSpec,
        @RequestParam(required = false) namespace: String?,
    ): Map<String, String> {
        val namespace = resolveNamespace(namespace)
        client.resources(MySQLInstance::class.java)
            .inNamespace(namespace)
            .withName(name)
            .edit { current ->
                val currentSpec = current.spec ?: MySQLSpec()
                current.spec = currentSpec.copy(storage = request)
                current
            }

        return mapOf("name" to name, "namespace" to namespace)
    }

    @PostMapping("/{name}/restart")
    /** Trigger rolling restart for a MySQLInstance. */
    fun restart(
        @PathVariable name: String,
        @RequestParam(required = false) namespace: String?,
    ): Map<String, String> {
        val namespace = resolveNamespace(namespace)
        opsService.triggerRestart(name, namespace)

        return mapOf("name" to name, "namespace" to namespace, "action" to "restart")
    }

    @PostMapping("/{name}/reset")
    /** Reset (drop DB) or delete instance based on action. */
    fun reset(
        @PathVariable name: String,
        @RequestBody request: ResetRequest,
        @RequestParam(required = false) namespace: String?,
    ): Map<String, String> {
        val namespace = resolveNamespace(namespace)
        val action = request.action ?: "truncate"
        val deleted = opsService.triggerReset(name, namespace, action)
        val actionResult = if (deleted) "delete" else "reset"

        return mapOf("name" to name, "namespace" to namespace, "action" to actionResult)
    }

    @PostMapping("/{name}/clone")
    /** Start clone based on initStrategy and cloneSource. */
    fun clone(
        @PathVariable name: String,
        @RequestBody request: CloneRequest,
        @RequestParam(required = false) namespace: String?,
    ): Map<String, String> {
        val namespace = resolveNamespace(namespace)
        client.resources(MySQLInstance::class.java)
            .inNamespace(namespace)
            .withName(name)
            .edit { current ->
                val currentSpec = current.spec ?: MySQLSpec()
                val metadata = requireNotNull(current.metadata)
                val annotations = metadata.annotations?.toMutableMap() ?: mutableMapOf()
                annotations["action.mysql.sandbox/clone"] = System.currentTimeMillis().toString()
                metadata.annotations = annotations
                current.spec = currentSpec.copy(
                    initStrategy = request.initStrategy,
                    cloneSource = request.cloneSource,
                )
                current
            }

        return mapOf("name" to name, "namespace" to namespace, "action" to "clone")
    }
}
