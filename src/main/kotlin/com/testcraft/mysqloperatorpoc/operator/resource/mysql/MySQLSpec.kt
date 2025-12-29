package com.testcraft.mysqloperatorpoc.operator.resource.mysql

import com.testcraft.mysqloperatorpoc.operator.common.ImageSpec

data class MySQLSpec(
    val image: ImageSpec = ImageSpec(
        registry = "docker.io",
        imageName = "library/mysql",
        tag = "8.0",
    ),
    val port: Int = 3306,
    val database: String = "testcraft",
    val rootPassword: String = "password",
    val resources: ResourceSpec = ResourceSpec(),
    val storage: StorageSpec = StorageSpec(),
    val mysqlConfig: Map<String, String> = emptyMap(),
    val initStrategy: InitStrategy = InitStrategy.EMPTY,
    val cloneSource: CloneSourceSpec? = null,
)

data class ResourceSpec(
    val limits: ResourceQuantity = ResourceQuantity(),
    val requests: ResourceQuantity? = null,
)

data class ResourceQuantity(
    val cpu: String? = null,
    val memory: String? = null,
)

data class StorageSpec(
    val size: String = "1Gi",
    val storageClassName: String? = null,
)

data class CloneSourceSpec(
    val host: String = "",
    val port: Int = 3306,
    val username: String = "",
    val password: String = "",
    val database: String = "",
)

enum class InitStrategy {
    EMPTY,
    CLONE,
    SCHEMA_CLONE,
}
