package com.testcraft.mysqloperatorpoc.api

import com.testcraft.mysqloperatorpoc.operator.common.ImageSpec
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.CloneSourceSpec
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.InitStrategy
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.ResourceSpec
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.StorageSpec

data class MySQLInstanceCreateRequest(
    val name: String,
    val namespace: String? = null,
    val image: ImageSpec? = null,
    val replicas: Int? = null,
    val port: Int? = null,
    val database: String? = null,
    val rootPassword: String? = null,
    val resources: ResourceSpec? = null,
    val storage: StorageSpec? = null,
    val mysqlConfig: Map<String, String>? = null,
    val initStrategy: InitStrategy? = null,
    val cloneSource: CloneSourceSpec? = null,
)

data class ResetRequest(
    val action: String? = null,
)

data class CloneRequest(
    val initStrategy: InitStrategy,
    val cloneSource: CloneSourceSpec,
)
