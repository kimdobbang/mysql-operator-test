package com.testcraft.mysqloperatorpoc.operator.common

data class ImageSpec(
    val registry: String = "docker.io",
    val imageName: String = "mysql",
    val tag: String = "8.0",
)
