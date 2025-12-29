package com.testcraft.mysqloperatorpoc.operator.resource.mysql.dependent

import com.testcraft.mysqloperatorpoc.operator.common.buildTunedConfig
import com.testcraft.mysqloperatorpoc.operator.common.renderMyCnf
import com.testcraft.mysqloperatorpoc.operator.common.sha256Hex
import com.testcraft.mysqloperatorpoc.operator.common.appLabelValue
import com.testcraft.mysqloperatorpoc.operator.common.appLabels
import com.testcraft.mysqloperatorpoc.operator.common.createOwnerReferences
import com.testcraft.mysqloperatorpoc.operator.common.managedLabels
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.MySQLInstance
import io.fabric8.kubernetes.api.model.ConfigMap
import io.fabric8.kubernetes.api.model.ConfigMapBuilder

object MySQLConfigMap {
    const val MY_CNF_KEY = "my.cnf"
    const val CONFIG_CHECKSUM_ANNOTATION = "mysql.sandbox/config-checksum"

    fun build(resource: MySQLInstance): ConfigMap {
        val metadata = requireNotNull(resource.metadata)
        val spec = resource.spec
        val appName = resource.appLabelValue()

        val tuned = buildTunedConfig(spec.resources.limits.memory)
        val merged = tuned + spec.mysqlConfig
        val myCnf = renderMyCnf(merged)
        val checksum = sha256Hex(myCnf)

        return ConfigMapBuilder()
            .withNewMetadata()
                .withName(metadata.name)
                .withNamespace(metadata.namespace)
                .withOwnerReferences(resource.createOwnerReferences())
                .withLabels<String, String>(managedLabels() + appLabels(appName))
                .addToAnnotations(CONFIG_CHECKSUM_ANNOTATION, checksum)
            .endMetadata()
            .addToData(MY_CNF_KEY, myCnf)
            .build()
    }
}
