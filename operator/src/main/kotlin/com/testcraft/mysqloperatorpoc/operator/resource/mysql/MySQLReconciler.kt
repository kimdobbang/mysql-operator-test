package com.testcraft.mysqloperatorpoc.operator.resource.mysql

import com.testcraft.mysqloperatorpoc.operator.common.buildTunedConfig
import com.testcraft.mysqloperatorpoc.operator.common.renderMyCnf
import com.testcraft.mysqloperatorpoc.operator.common.sha256Hex
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.dependent.MySQLConfigMap
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.dependent.MySQLSecret
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.dependent.MySQLService
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.dependent.MySQLStatefulSet
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.apps.StatefulSet
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
    private val client: KubernetesClient,
    private val statusService: MySQLStatusService,
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

        val computation = statusService.computeStatus(primary, ready, serviceName, now)
        primary.status = computation.status.copy(lastAppliedConfigHash = configHash)
        if (computation.annotationsToRemove.isNotEmpty()) {
            client.resources(MySQLInstance::class.java)
                .inNamespace(metadata.namespace)
                .withName(metadata.name)
                .edit { current ->
                    val meta = requireNotNull(current.metadata)
                    val annotations = meta.annotations?.toMutableMap() ?: mutableMapOf()
                    computation.annotationsToRemove.forEach { annotations.remove(it) }
                    meta.annotations = annotations
                    current
                }
        }
        metadata.managedFields = null
        return UpdateControl.patchStatus(primary)
            .rescheduleAfter(java.time.Duration.ofSeconds(10))
    }

    private fun buildDesiredConfig(resource: MySQLInstance): Map<String, String> {
        val tuned = buildTunedConfig(resource.spec.resources.limits.memory)
        return tuned + resource.spec.mysqlConfig
    }

}
