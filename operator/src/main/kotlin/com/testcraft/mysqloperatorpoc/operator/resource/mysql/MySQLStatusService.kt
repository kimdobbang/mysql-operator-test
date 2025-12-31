package com.testcraft.mysqloperatorpoc.operator.resource.mysql

import com.testcraft.mysqloperatorpoc.operator.common.createOwnerReferences
import com.testcraft.mysqloperatorpoc.operator.common.sha256Hex
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim
import io.fabric8.kubernetes.api.model.storage.StorageClass
import io.fabric8.kubernetes.api.model.batch.v1.Job
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

@Component
class MySQLStatusService(
    private val client: KubernetesClient,
) {
    data class StatusComputation(
        val status: MySQLStatus,
        val annotationsToRemove: Set<String> = emptySet(),
    )

    fun computeStatus(
        resource: MySQLInstance,
        ready: Boolean,
        serviceName: String,
        now: Instant,
    ): StatusComputation {
        val metadata = requireNotNull(resource.metadata)
        val namespace = requireNotNull(metadata.namespace)
        val existing = resource.status ?: MySQLStatus()
        val annotations = metadata.annotations.orEmpty()

        var status = existing
        val annotationsToRemove = mutableSetOf<String>()

        if (annotations.containsKey(ANNOTATION_RESTART)) {
            triggerRestart(metadata.name, namespace)
            annotationsToRemove.add(ANNOTATION_RESTART)
            status = status.copy(message = "Restart triggered")
        }

        val resetResult = handleReset(resource, status, annotations, now)
        status = resetResult.status
        annotationsToRemove.addAll(resetResult.annotationsToRemove)

        val cloneToken = annotations[ANNOTATION_CLONE]
        val cloneResult = handleClone(resource, status, now, cloneToken)
        status = cloneResult.status
        annotationsToRemove.addAll(cloneResult.annotationsToRemove)

        val storageWarning = applyStorageResizeIfNeeded(resource)
        if (!storageWarning.isNullOrBlank()) {
            status = status.copy(
                message = if (status.message.isBlank()) storageWarning else "${status.message} | ${storageWarning}"
            )
        }

        val phase = if (existing.phase == "Error") {
            "Error"
        } else {
            if (ready) "Ready" else "Creating"
        }
        val message = status.message.ifBlank {
            if (ready) "Running" else "Waiting for StatefulSet readiness"
        }
        val lastPhaseTime = if (existing.phase != phase) now else existing.lastPhaseTime

        return StatusComputation(
            status = status.copy(
                ready = ready,
                phase = phase,
                lastPhaseTime = lastPhaseTime,
                message = message,
                serviceName = serviceName,
            ),
            annotationsToRemove = annotationsToRemove,
        )
    }

    private fun applyStorageResizeIfNeeded(resource: MySQLInstance): String? {
        val metadata = requireNotNull(resource.metadata)
        val namespace = requireNotNull(metadata.namespace)
        val desiredSize = resource.spec.storage.size
        val desiredBytes = parseBytes(desiredSize) ?: return null
        val pvcs = client.persistentVolumeClaims()
            .inNamespace(namespace)
            .list()
            .items
            .filter { it.metadata?.name?.startsWith("mysql-data-${metadata.name}-") == true }

        if (pvcs.isEmpty()) {
            return null
        }

        val storageClass = resolveStorageClass(resource.spec.storage.storageClassName)
        val allowExpansion = storageClass?.allowVolumeExpansion == true
        val pvcSizes = pvcs.mapNotNull { pvc ->
            parseBytes(quantityToString(pvc.spec?.resources?.requests?.get("storage")))
        }
        val currentMax = pvcSizes.maxOrNull() ?: return null
        val currentSize = quantityToString(pvcs.firstOrNull()?.spec?.resources?.requests?.get("storage")) ?: "unknown"

        return when {
            allowExpansion && desiredBytes > currentMax -> {
                pvcs.forEach { pvc ->
                    val existingBytes = parseBytes(quantityToString(pvc.spec?.resources?.requests?.get("storage")))
                    if (existingBytes != null && desiredBytes > existingBytes) {
                        resizePvc(pvc, namespace, desiredSize)
                    }
                }
                null
            }
            desiredBytes < currentMax -> {
                "Storage decrease requires manual recreation (current=${currentSize}, requested=${desiredSize})."
            }
            !allowExpansion && desiredBytes != currentMax -> {
                "Storage change requires manual recreation (current=${currentSize}, requested=${desiredSize})."
            }
            else -> null
        }
    }

    private fun resolveStorageClass(storageClassName: String?): StorageClass? {
        if (!storageClassName.isNullOrBlank()) {
            return client.storage().storageClasses().withName(storageClassName).get()
        }
        return client.storage().storageClasses()
            .list()
            .items
            .firstOrNull { it.metadata?.annotations?.get("storageclass.kubernetes.io/is-default-class") == "true" }
    }

    private fun resizePvc(pvc: PersistentVolumeClaim, namespace: String, desiredSize: String) {
        val name = pvc.metadata?.name ?: return
        client.persistentVolumeClaims()
            .inNamespace(namespace)
            .withName(name)
            .edit { current ->
                val updated = current.spec?.resources?.requests?.toMutableMap() ?: mutableMapOf()
                updated["storage"] = io.fabric8.kubernetes.api.model.Quantity(desiredSize)
                current.spec?.resources?.requests = updated
                current
            }
    }

    private fun quantityToString(quantity: io.fabric8.kubernetes.api.model.Quantity?): String? {
        if (quantity == null) return null
        val amount = quantity.amount ?: return null
        val format = quantity.format ?: ""
        return amount + format
    }

    private fun parseBytes(value: String?): BigDecimal? {
        if (value.isNullOrBlank()) return null
        val match = Regex("^([0-9]*\\.?[0-9]+)([a-zA-Z]+)?$").matchEntire(value.trim()) ?: return null
        val number = match.groupValues[1].toBigDecimalOrNull() ?: return null
        val suffix = match.groupValues[2]
        if (suffix.isEmpty()) return number
        val base = when (suffix.lowercase()) {
            "ki" -> BigDecimal(1024)
            "mi" -> BigDecimal(1024).pow(2)
            "gi" -> BigDecimal(1024).pow(3)
            "ti" -> BigDecimal(1024).pow(4)
            "pi" -> BigDecimal(1024).pow(5)
            "ei" -> BigDecimal(1024).pow(6)
            "k" -> BigDecimal(1000)
            "m" -> BigDecimal(1000).pow(2)
            "g" -> BigDecimal(1000).pow(3)
            "t" -> BigDecimal(1000).pow(4)
            "p" -> BigDecimal(1000).pow(5)
            "e" -> BigDecimal(1000).pow(6)
            else -> return null
        }
        return number.multiply(base)
    }

    private fun handleReset(
        resource: MySQLInstance,
        status: MySQLStatus,
        annotations: Map<String, String>,
        now: Instant,
    ): StatusComputation {
        val metadata = requireNotNull(resource.metadata)
        val namespace = requireNotNull(metadata.namespace)
        val jobName = "${metadata.name}-reset"
        val jobClient = client.batch().v1().jobs().inNamespace(namespace).withName(jobName)
        var resetJob = jobClient.get()
        val resetAction = annotations[ANNOTATION_RESET]
        val resetToken = resetAction?.trim()?.takeIf { it.isNotBlank() }
        var next = status
        val annotationsToRemove = mutableSetOf<String>()

        if (resetToken != null && resetToken == status.lastResetToken) {
            annotationsToRemove.add(ANNOTATION_RESET)
            return StatusComputation(status, annotationsToRemove)
        }

        if (resetAction != null) {
            if (resetJob == null && (status.resetPhase == null || status.resetPhase == "FAILED")) {
                val job = buildResetJob(resource, jobName)
                jobClient.createOrReplace(job)
                next = next.copy(
                    resetPhase = "RUNNING",
                    lastResetTime = now,
                    lastResetToken = resetToken,
                    message = "Reset started (drop+create db=${resource.spec.database})",
                )
                resetJob = jobClient.get()
            } else if (resetToken != null) {
                next = next.copy(lastResetToken = resetToken)
            }
            annotationsToRemove.add(ANNOTATION_RESET)
        }

        val targetDb = resource.spec.database
        val backoffLimit = resetJob?.spec?.backoffLimit ?: 3
        val succeeded = resetJob?.status?.succeeded ?: 0
        val failed = resetJob?.status?.failed ?: 0

        val updated = when {
            succeeded > 0 -> {
                jobClient.delete()
                next.copy(
                    resetPhase = "SUCCESS",
                    lastResetTime = now,
                    clonePhase = null,
                    lastCloneTime = null,
                    lastCloneSpecHash = null,
                    lastCloneActionToken = null,
                    message = "Reset completed (drop+create db=${targetDb})",
                )
            }
            failed >= backoffLimit -> {
                jobClient.delete()
                next.copy(
                    resetPhase = "FAILED",
                    lastResetTime = now,
                    message = "Reset failed after ${failed} attempts (drop+create db=${targetDb})",
                )
            }
            resetJob == null && status.resetPhase == "RUNNING" -> {
                next.copy(
                    resetPhase = "FAILED",
                    lastResetTime = now,
                    message = "Reset job missing (drop+create db=${targetDb})",
                )
            }
            else -> next
        }
        return StatusComputation(updated, annotationsToRemove)
    }

    private fun handleClone(
        resource: MySQLInstance,
        status: MySQLStatus,
        now: Instant,
        cloneToken: String?,
    ): StatusComputation {
        val metadata = requireNotNull(resource.metadata)
        val namespace = requireNotNull(metadata.namespace)
        val jobName = "${metadata.name}-clone"
        val jobClient = client.batch().v1().jobs().inNamespace(namespace).withName(jobName)
        var cloneJob = jobClient.get()
        val cloneSource = resource.spec.cloneSource
        val shouldClone = resource.spec.initStrategy != InitStrategy.EMPTY && cloneSource != null
        var next = status
        val annotationsToRemove = mutableSetOf<String>()
        val desiredHash = computeCloneSpecHash(resource)

        if (cloneToken != null) {
            annotationsToRemove.add(ANNOTATION_CLONE)
        }

        if (cloneToken != null && status.lastCloneActionToken == cloneToken) {
            return StatusComputation(status = next, annotationsToRemove = annotationsToRemove)
        }

        if (cloneToken != null && shouldClone && desiredHash != null) {
            val sameSpec = status.clonePhase == "SUCCESS" && status.lastCloneSpecHash == desiredHash
            if (sameSpec && cloneJob == null) {
                return StatusComputation(
                    status = next.copy(message = "변경 없음: 클론 재실행 안 함"),
                    annotationsToRemove = annotationsToRemove,
                )
            }
        }

        if (cloneToken != null && shouldClone && desiredHash != null && cloneJob == null) {
            val job = buildCloneJob(resource, resource.spec.initStrategy, requireNotNull(cloneSource), jobName)
            jobClient.createOrReplace(job)
            next = next.copy(
                clonePhase = "RUNNING",
                lastCloneTime = now,
                lastCloneActionToken = cloneToken,
                message = "Clone started",
            )
            cloneJob = jobClient.get()
        }

        val targetDb = resource.spec.database
        val backoffLimit = cloneJob?.spec?.backoffLimit ?: 3
        val succeeded = cloneJob?.status?.succeeded ?: 0
        val failed = cloneJob?.status?.failed ?: 0

        val updated = when {
            succeeded > 0 -> {
                jobClient.delete()
                next.copy(
                    clonePhase = "SUCCESS",
                    lastCloneTime = now,
                    lastCloneSpecHash = desiredHash,
                    lastCloneActionToken = status.lastCloneActionToken ?: cloneToken,
                    message = "Clone completed (db=${targetDb})",
                )
            }
            failed >= backoffLimit -> {
                jobClient.delete()
                next.copy(
                    clonePhase = "FAILED",
                    lastCloneTime = now,
                    lastCloneActionToken = status.lastCloneActionToken ?: cloneToken,
                    message = "Clone failed after ${failed} attempts (db=${targetDb})",
                )
            }
            cloneJob != null && succeeded == 0 -> {
                next.copy(
                    clonePhase = "RUNNING",
                    lastCloneTime = now,
                    message = "Clone running (db=${targetDb})",
                )
            }
            cloneJob == null && status.clonePhase == "RUNNING" -> {
                next.copy(
                    clonePhase = "FAILED",
                    lastCloneTime = now,
                    lastCloneActionToken = status.lastCloneActionToken ?: cloneToken,
                    message = "Clone job missing (db=${targetDb})",
                )
            }
            else -> next
        }
        return StatusComputation(updated, annotationsToRemove)
    }

    private fun triggerRestart(name: String, namespace: String) {
        client.apps().statefulSets().inNamespace(namespace).withName(name).edit { sts ->
            val template = sts.spec.template
            val existing = template.metadata.annotations?.toMutableMap() ?: mutableMapOf()
            existing["mysql.sandbox/restartedAt"] = Instant.now().toString()
            template.metadata.annotations = existing
            sts
        }
    }

    private fun computeCloneSpecHash(resource: MySQLInstance): String? {
        val source = resource.spec.cloneSource ?: return null
        val payload = listOf(
            resource.spec.initStrategy.name,
            source.host,
            source.port.toString(),
            source.username,
            source.password,
            source.database,
        ).joinToString("|")
        return sha256Hex(payload)
    }

    private fun buildCloneJob(
        resource: MySQLInstance,
        initStrategy: InitStrategy,
        source: CloneSourceSpec,
        name: String,
    ): Job {
        val metadata = requireNotNull(resource.metadata)
        val schemaOnly = initStrategy == InitStrategy.SCHEMA_CLONE
        val noDataFlag = if (schemaOnly) "--no-data" else ""
        val targetHost = "${metadata.name}-0.${metadata.name}.${metadata.namespace}.svc.cluster.local"
        val targetDb = resource.spec.database
        val command = """
            set -e
            until mysqladmin ping -h $targetHost -P ${resource.spec.port} -uroot -p${'$'}TGT_PASSWORD --silent; do
              sleep 2
            done
            until mysqladmin ping -h ${source.host} -P ${source.port} -u ${source.username} -p${'$'}SRC_PASSWORD --silent; do
              sleep 2
            done
            SRC_DB_EXISTS=${'$'}(mysql -h ${source.host} -P ${source.port} -u ${source.username} -p${'$'}SRC_PASSWORD -N -e "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='${source.database}';")
            if [ -z "${'$'}SRC_DB_EXISTS" ]; then
              echo "Source database not found: ${source.database}"
              exit 1
            fi
            TGT_DB_EXISTS=${'$'}(mysql -h $targetHost -P ${resource.spec.port} -uroot -p${'$'}TGT_PASSWORD -N -e "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='${targetDb}';")
            if [ -z "${'$'}TGT_DB_EXISTS" ]; then
              echo "Target database not found: ${targetDb}"
              exit 1
            fi
            mysqldump -h ${source.host} -P ${source.port} -u ${source.username} -p${'$'}SRC_PASSWORD $noDataFlag ${source.database} \
              | mysql -h $targetHost -P ${resource.spec.port} -uroot -p${'$'}TGT_PASSWORD $targetDb
        """.trimIndent()

        return JobBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(metadata.namespace)
                .withOwnerReferences(resource.createOwnerReferences())
            .endMetadata()
            .withNewSpec()
                .withBackoffLimit(3)
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

    private fun buildResetJob(resource: MySQLInstance, name: String): Job {
        val metadata = requireNotNull(resource.metadata)
        val targetHost = "${metadata.name}-0.${metadata.name}.${metadata.namespace}.svc.cluster.local"
        val targetDb = resource.spec.database
        val command = """
            set -e
            until mysqladmin ping -h $targetHost -P ${resource.spec.port} -uroot -p${'$'}TGT_PASSWORD --silent; do
              sleep 2
            done
            mysql -h $targetHost -P ${resource.spec.port} -uroot -p${'$'}TGT_PASSWORD -e "DROP DATABASE IF EXISTS ${targetDb}; CREATE DATABASE ${targetDb};"
        """.trimIndent()

        return JobBuilder()
            .withNewMetadata()
                .withName(name)
                .withNamespace(metadata.namespace)
                .withOwnerReferences(resource.createOwnerReferences())
            .endMetadata()
            .withNewSpec()
                .withBackoffLimit(3)
                .withNewTemplate()
                    .withNewSpec()
                        .addNewContainer()
                            .withName("mysql-reset")
                            .withImage("mysql:8.0")
                            .withCommand("sh", "-c", command)
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

    companion object {
        private const val ANNOTATION_CLONE = "action.mysql.sandbox/clone"
        private const val ANNOTATION_RESTART = "action.mysql.sandbox/restart"
        private const val ANNOTATION_RESET = "action.mysql.sandbox/reset"
    }
}
