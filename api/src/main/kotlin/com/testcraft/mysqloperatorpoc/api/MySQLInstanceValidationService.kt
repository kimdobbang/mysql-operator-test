package com.testcraft.mysqloperatorpoc.api

import com.testcraft.mysqloperatorpoc.operator.resource.mysql.MySQLSpec
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.storage.StorageClass
import io.fabric8.kubernetes.client.KubernetesClient
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class MySQLInstanceValidationService(
    private val client: KubernetesClient,
) {
    data class ValidationResult(
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
    )

    fun validateForApply(name: String, namespace: String, spec: MySQLSpec): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (spec.replicas < 1) {
            errors.add("replicas는 1 이상이어야 합니다.")
        }

        val storageClass = resolveStorageClass(spec.storage.storageClassName)
        if (storageClass == null) {
            val storageLabel = spec.storage.storageClassName ?: "default"
            errors.add("StorageClass를 찾을 수 없습니다: ${storageLabel}")
        }

        val pvcResult = validateStorageResize(name, namespace, spec, storageClass)
        errors.addAll(pvcResult)

        val resourceWarning = warnIfResourceOvercommit(spec)
        if (resourceWarning.isNotBlank()) {
            warnings.add(resourceWarning)
        }

        return ValidationResult(errors = errors, warnings = warnings)
    }

    private fun validateStorageResize(
        name: String,
        namespace: String,
        spec: MySQLSpec,
        storageClass: StorageClass?,
    ): List<String> {
        val desiredBytes = parseBytes(spec.storage.size) ?: return emptyList()
        val pvcs = runCatching {
            client.persistentVolumeClaims()
                .inNamespace(namespace)
                .list()
                .items
        }.getOrDefault(emptyList())
            .filter { it.metadata?.name?.startsWith("mysql-data-${name}-") == true }

        if (pvcs.isEmpty()) {
            return emptyList()
        }

        val currentMax = pvcs.mapNotNull { pvc ->
            parseBytes(quantityToString(pvc.spec?.resources?.requests?.get("storage")))
        }.maxOrNull() ?: return emptyList()
        val currentSize = quantityToString(pvcs.firstOrNull()?.spec?.resources?.requests?.get("storage"))
            ?: "unknown"

        return when {
            desiredBytes < currentMax -> listOf(
                "Storage 축소는 지원되지 않습니다 (current=${currentSize}, requested=${spec.storage.size})."
            )
            desiredBytes > currentMax && storageClass?.allowVolumeExpansion != true -> listOf(
                "StorageClass 확장 불가로 인해 스토리지 확장을 적용할 수 없습니다 " +
                    "(current=${currentSize}, requested=${spec.storage.size})."
            )
            else -> emptyList()
        }
    }

    private fun warnIfResourceOvercommit(spec: MySQLSpec): String {
        val requests = spec.resources.requests ?: spec.resources.limits
        val cpuPerPod = parseCpuMillis(requests.cpu)
        val memoryPerPod = parseBytes(requests.memory)
        if (cpuPerPod == null && memoryPerPod == null) {
            return ""
        }

        val nodes = runCatching { client.nodes().list().items }.getOrDefault(emptyList())
        if (nodes.isEmpty()) {
            return ""
        }

        val allocCpu = nodes.mapNotNull { parseCpuMillis(quantityToString(it.status?.allocatable?.get("cpu"))) }
            .fold(BigDecimal.ZERO, BigDecimal::add)
        val allocMem = nodes.mapNotNull { parseBytes(quantityToString(it.status?.allocatable?.get("memory"))) }
            .fold(BigDecimal.ZERO, BigDecimal::add)

        val replicas = BigDecimal(spec.replicas)
        val cpuTotal = cpuPerPod?.multiply(replicas)
        val memTotal = memoryPerPod?.multiply(replicas)

        val warnings = mutableListOf<String>()
        if (cpuTotal != null && allocCpu > BigDecimal.ZERO && cpuTotal > allocCpu) {
            warnings.add(
                "CPU 요청 합계(${formatCpu(cpuTotal)})가 노드 allocatable 합계(${formatCpu(allocCpu)})보다 큽니다."
            )
        }
        if (memTotal != null && allocMem > BigDecimal.ZERO && memTotal > allocMem) {
            warnings.add(
                "메모리 요청 합계(${formatBytes(memTotal)})가 노드 allocatable 합계(${formatBytes(allocMem)})보다 큽니다."
            )
        }

        if (warnings.isEmpty()) {
            return ""
        }

        return "배치 실패 가능: " + warnings.joinToString(" ")
    }

    private fun resolveStorageClass(storageClassName: String?): StorageClass? {
        return runCatching {
            if (!storageClassName.isNullOrBlank()) {
                return client.storage().storageClasses().withName(storageClassName).get()
            }
            client.storage().storageClasses()
                .list()
                .items
                .firstOrNull { it.metadata?.annotations?.get("storageclass.kubernetes.io/is-default-class") == "true" }
        }.getOrNull()
    }

    private fun quantityToString(quantity: Quantity?): String? {
        if (quantity == null) return null
        val amount = quantity.amount ?: return null
        val format = quantity.format ?: ""
        return amount + format
    }

    private fun parseCpuMillis(value: String?): BigDecimal? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        return if (trimmed.endsWith("m")) {
            trimmed.dropLast(1).toBigDecimalOrNull()
        } else {
            trimmed.toBigDecimalOrNull()?.multiply(BigDecimal(1000))
        }
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

    private fun formatCpu(value: BigDecimal): String {
        val rounded = value.setScale(0, RoundingMode.HALF_UP).stripTrailingZeros()
        return "${rounded.toPlainString()}m"
    }

    private fun formatBytes(value: BigDecimal): String {
        val gib = BigDecimal(1024).pow(3)
        val mib = BigDecimal(1024).pow(2)
        val kib = BigDecimal(1024)
        return when {
            value >= gib -> "${value.divide(gib, 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()}Gi"
            value >= mib -> "${value.divide(mib, 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()}Mi"
            value >= kib -> "${value.divide(kib, 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()}Ki"
            else -> "${value.setScale(0, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()}B"
        }
    }
}
