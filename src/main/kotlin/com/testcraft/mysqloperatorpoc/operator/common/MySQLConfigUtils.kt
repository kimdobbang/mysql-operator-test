package com.testcraft.mysqloperatorpoc.operator.common

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.Locale

fun parseMemoryToBytes(memory: String?): Long? {
    if (memory == null) return null
    val trimmed = memory.trim()
    if (trimmed.isEmpty()) return null

    val unit = trimmed.takeLast(2).uppercase(Locale.US)
    val factor = when (unit) {
        "KI" -> 1024L
        "MI" -> 1024L * 1024L
        "GI" -> 1024L * 1024L * 1024L
        "TI" -> 1024L * 1024L * 1024L * 1024L
        else -> null
    }

    return if (factor != null) {
        val number = trimmed.dropLast(2).toBigDecimalOrNull() ?: return null
        number.multiply(BigDecimal(factor)).toLong()
    } else {
        trimmed.toLongOrNull()
    }
}

fun bytesToMySqlSize(bytes: Long): String {
    val mib = bytes.toDouble() / (1024.0 * 1024.0)
    val gib = mib / 1024.0
    return if (gib >= 1.0) {
        formatSize(gib, "G")
    } else {
        formatSize(mib, "M")
    }
}

fun buildTunedConfig(memoryLimit: String?): Map<String, String> {
    val memoryBytes = parseMemoryToBytes(memoryLimit) ?: return emptyMap()
    val bufferPoolBytes = (memoryBytes * 0.75).toLong()
    val bufferPoolGi = bufferPoolBytes / (1024.0 * 1024.0 * 1024.0)
    val instances = bufferPoolGi.coerceAtLeast(1.0).coerceAtMost(8.0).toInt()
    val chunkSizeBytes = if (bufferPoolBytes >= 1024L * 1024L * 1024L) {
        128L * 1024L * 1024L
    } else {
        64L * 1024L * 1024L
    }

    return mapOf(
        "innodb_buffer_pool_size" to bytesToMySqlSize(bufferPoolBytes),
        "innodb_buffer_pool_instances" to instances.toString(),
        "innodb_buffer_pool_chunk_size" to bytesToMySqlSize(chunkSizeBytes),
    )
}

fun renderMyCnf(config: Map<String, String>): String {
    val builder = StringBuilder()
    builder.append("[mysqld]\n")
    config.toSortedMap().forEach { (key, value) ->
        builder.append(key).append("=").append(value).append("\n")
    }
    return builder.toString()
}

fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

private fun formatSize(value: Double, suffix: String): String {
    val rounded = BigDecimal(value).setScale(1, RoundingMode.HALF_UP)
    val normalized = if (rounded.stripTrailingZeros().scale() <= 0) {
        rounded.setScale(0, RoundingMode.HALF_UP)
    } else {
        rounded
    }
    return normalized.toPlainString() + suffix
}
