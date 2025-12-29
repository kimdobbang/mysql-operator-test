package com.testcraft.mysqloperatorpoc.operator.resource.mysql

import com.testcraft.mysqloperatorpoc.operator.common.buildTunedConfig
import com.testcraft.mysqloperatorpoc.operator.common.createOwnerReferences
import com.testcraft.mysqloperatorpoc.operator.common.renderMyCnf
import com.testcraft.mysqloperatorpoc.operator.common.sha256Hex
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.dependent.MySQLConfigMap
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.dependent.MySQLSecret
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.dependent.MySQLService
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.dependent.MySQLStatefulSet
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.StatefulSet
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration
import io.javaoperatorsdk.operator.api.reconciler.MaxReconciliationInterval
import io.javaoperatorsdk.operator.api.reconciler.Reconciler
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl
import io.javaoperatorsdk.operator.api.reconciler.Workflow
import io.javaoperatorsdk.operator.api.reconciler.dependent.Dependent
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@Workflow(
    dependents = [
        Dependent(type = MySQLService::class),
        Dependent(type = MySQLSecret::class),
        Dependent(type = MySQLStatefulSet::class),
    ]
)
@ControllerConfiguration(generationAwareEventProcessing = false)
@MaxReconciliationInterval(interval = 10, timeUnit = java.util.concurrent.TimeUnit.SECONDS)
class MySQLReconciler(
    private val client: KubernetesClient
) : Reconciler<MySQLInstance> {

    override fun reconcile(resource: MySQLInstance?, context: Context<MySQLInstance?>?): UpdateControl<MySQLInstance?> {
        val primary = requireNotNull(resource)
        val ctx = requireNotNull(context)
        val metadata = requireNotNull(primary.metadata)
        val now = Instant.now()

        val service = ctx.getSecondaryResource(Service::class.java).orElse(null)
        val statefulSet = ctx.getSecondaryResource(StatefulSet::class.java).orElse(null)
        val ready = (statefulSet?.status?.readyReplicas ?: 0) > 0
        val serviceName = service?.metadata?.name ?: metadata.name

        val desiredConfigMap = MySQLConfigMap.build(primary)
        client.configMaps().inNamespace(metadata.namespace).resource(desiredConfigMap).serverSideApply()

        val desiredConfig = buildDesiredConfig(primary)
        val configHash = sha256Hex(renderMyCnf(desiredConfig))

        var phase = if (ready) "Ready" else "Creating"
        var message = if (ready) "Running" else "Waiting for StatefulSet readiness"
        var resetPhase = primary.status?.resetPhase
        var lastResetTime = primary.status?.lastResetTime
        var clonePhase = primary.status?.clonePhase
        var lastCloneTime = primary.status?.lastCloneTime

        val annotations = metadata.annotations?.toMutableMap() ?: mutableMapOf()
        var resourceChanged = false
        if (annotations.containsKey(RESTART_ANNOTATION)) {
            restartStatefulSet(metadata.namespace, metadata.name)
            annotations.remove(RESTART_ANNOTATION)
            message = "Restart triggered"
            resourceChanged = true
        }

        if (annotations.containsKey(RESET_ANNOTATION)) {
            val action = annotations[RESET_ANNOTATION]
            val resetResult = runReset(primary, action)
            resetPhase = resetResult.phase
            lastResetTime = now
            message = resetResult.message
            if (resetResult.phase == "FAILED") {
                phase = "Error"
            }
            annotations.remove(RESET_ANNOTATION)
            resourceChanged = true
        }

        val cloneResult = handleClone(primary, ready)
        clonePhase = cloneResult.phase
        lastCloneTime = cloneResult.lastCloneTime ?: lastCloneTime

        primary.status = MySQLStatus(
            ready = ready,
            phase = phase,
            message = message,
            serviceName = serviceName,
            lastAppliedConfigHash = configHash,
            clonePhase = clonePhase,
            lastCloneTime = lastCloneTime,
            resetPhase = resetPhase,
            lastResetTime = lastResetTime,
        )

        primary.metadata.annotations = annotations.ifEmpty { null }
        primary.metadata.managedFields = null
        val update = if (resourceChanged) {
            UpdateControl.patchResourceAndStatus(primary)
        } else {
            UpdateControl.patchStatus(primary)
        }

        return update.rescheduleAfter(java.time.Duration.ofSeconds(10))
    }

    private fun buildDesiredConfig(resource: MySQLInstance): Map<String, String> {
        val tuned = buildTunedConfig(resource.spec.resources.limits.memory)
        return tuned + resource.spec.mysqlConfig
    }

    private fun runReset(resource: MySQLInstance, action: String?): ResetResult {
        // Reset은 PoC에서 실제 동작 없이 상태만 기록
        return if (action.isNullOrBlank()) {
            ResetResult("FAILED", "Reset action missing")
        } else {
            ResetResult("SKIPPED", "Reset skipped (PoC)")
        }
    }

    private fun handleClone(resource: MySQLInstance, ready: Boolean): CloneResult {
        if (resource.spec.initStrategy == InitStrategy.EMPTY) {
            return CloneResult(resource.status?.clonePhase, resource.status?.lastCloneTime)
        }
        if (!ready) {
            return CloneResult("WAITING", resource.status?.lastCloneTime)
        }
        val source = resource.spec.cloneSource ?: return CloneResult("SKIPPED", resource.status?.lastCloneTime)

        val metadata = requireNotNull(resource.metadata)
        val namespace = metadata.namespace
        val jobName = "${metadata.name}-clone"
        val jobClient = client.batch().v1().jobs().inNamespace(namespace).withName(jobName)
        val existing = jobClient.get()

        if (existing == null) {
            val job = buildCloneJob(resource, source, jobName)
            jobClient.createOrReplace(job)
            return CloneResult("RUNNING", Instant.now())
        }

        val status = existing.status
        return when {
            (status?.succeeded ?: 0) > 0 -> CloneResult("SUCCESS", Instant.now())
            (status?.failed ?: 0) > 0 -> CloneResult("FAILED", Instant.now())
            else -> CloneResult("RUNNING", resource.status?.lastCloneTime)
        }
    }

    private fun buildCloneJob(resource: MySQLInstance, source: CloneSourceSpec, name: String): Job {
        val metadata = requireNotNull(resource.metadata)
        val schemaOnly = resource.spec.initStrategy == InitStrategy.SCHEMA_CLONE
        val noDataFlag = if (schemaOnly) "--no-data" else ""
        val targetHost = "${metadata.name}.${metadata.namespace}.svc.cluster.local"
        val targetDb = resource.spec.database

        val command = """
            set -e
            mysqldump -h ${source.host} -P ${source.port} -u ${source.username} -p${'$'}SRC_PASSWORD $noDataFlag ${source.database} \
              | mysql -h $targetHost -P ${resource.spec.port} -u $ROOT_USER -p${'$'}TGT_PASSWORD $targetDb
        """.trimIndent()

        return JobBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(metadata.namespace)
                .withOwnerReferences(resource.createOwnerReferences())
            .endMetadata()
            .withNewSpec()
                .withNewTemplate()
                    .withNewSpec()
                        .addNewContainer()
                            .withName("mysql-clone")
                            .withImage("mysql:8.0")
                            .withCommand("sh", "-c", command)
                            .addNewEnv()
                                .withName("SRC_PASSWORD")
                                .withValue(source.password)
                            .endEnv()
                            .addNewEnv()
                                .withName("TGT_PASSWORD")
                                .withValue(resource.spec.rootPassword)
                            .endEnv()
                        .endContainer()
                        .withRestartPolicy("Never")
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build()
    }

    private fun restartStatefulSet(namespace: String, name: String) {
        client.apps().statefulSets().inNamespace(namespace).withName(name).edit { sts ->
            val template = sts.spec.template
            val existing = template.metadata.annotations?.toMutableMap() ?: mutableMapOf()
            existing[RESTARTED_AT_ANNOTATION] = Instant.now().toString()
            template.metadata.annotations = existing
            sts
        }
    }

    data class CloneResult(
        val phase: String?,
        val lastCloneTime: Instant?,
    )

    data class ResetResult(
        val phase: String,
        val message: String,
    )

    companion object {
        private const val ROOT_USER = "root"
        private const val RESET_ANNOTATION = "action.mysql.sandbox/reset"
        private const val RESTART_ANNOTATION = "action.mysql.sandbox/restart"
        private const val RESTARTED_AT_ANNOTATION = "mysql.sandbox/restartedAt"
    }
}
