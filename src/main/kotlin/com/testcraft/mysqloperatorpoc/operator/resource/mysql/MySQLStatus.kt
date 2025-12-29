package com.testcraft.mysqloperatorpoc.operator.resource.mysql

import java.time.Instant

data class MySQLStatus(
    val ready: Boolean = false,
    val phase: String = "Creating",
    val message: String = "Instance is being created",
    val serviceName: String? = null,
    val lastAppliedConfigHash: String? = null,
    val clonePhase: String? = null,
    val lastCloneTime: Instant? = null,
    val resetPhase: String? = null,
    val lastResetTime: Instant? = null,
)
