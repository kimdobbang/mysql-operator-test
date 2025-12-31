package com.testcraft.mysqloperatorpoc.api

data class MySQLInstanceSummary(
    val name: String,
    val namespace: String,
    val ready: Boolean?,
    val phase: String?,
    val message: String?,
)

data class MySQLInstanceStatusResponse(
    val name: String,
    val namespace: String,
    val ready: Boolean?,
    val phase: String?,
    val lastPhaseTime: String?,
    val message: String?,
    val clonePhase: String?,
    val resetPhase: String?,
    val serviceName: String?,
    val lastCloneTime: String?,
    val lastResetTime: String?,
)

data class MySQLInstanceResourcesResponse(
    val name: String,
    val namespace: String,
    val statefulSetName: String?,
    val readyReplicas: Int?,
    val serviceName: String?,
    val podNames: List<String>,
    val pvcNames: List<String>,
    val cloneJobStatus: String?,
    val resetJobStatus: String?,
)
