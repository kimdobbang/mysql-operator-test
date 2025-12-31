package com.testcraft.mysqloperatorpoc.operator.resource.mysql.dependent

import com.testcraft.mysqloperatorpoc.operator.resource.mysql.MySQLInstance
import com.testcraft.mysqloperatorpoc.operator.common.MANAGED_LABEL_SELECTOR
import com.testcraft.mysqloperatorpoc.operator.common.createOwnerReferences
import com.testcraft.mysqloperatorpoc.operator.common.managedLabels
import com.testcraft.mysqloperatorpoc.operator.common.resourceNameWithSuffix
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.javaoperatorsdk.operator.api.config.informer.Informer
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent

@KubernetesDependent(informer = Informer(labelSelector = MANAGED_LABEL_SELECTOR))
class MySQLSecret : CRUDKubernetesDependentResource<Secret, MySQLInstance>() {
    override fun desired(primary: MySQLInstance?, context: Context<MySQLInstance?>?): Secret? {
        val resource = requireNotNull(primary)
        val spec = resource.spec

        return SecretBuilder()
            .withNewMetadata()
                .withName(resource.resourceNameWithSuffix(SECRET_SUFFIX))
                .withNamespace(resource.metadata.namespace)
                .withOwnerReferences(resource.createOwnerReferences())
                .withLabels<String, String>(managedLabels())
            .endMetadata()
            .withStringData<String, String>(
                mapOf(
                    SECRET_KEY_PASSWORD to spec.rootPassword,
                )
            )
            .build()
    }

    companion object {
        const val SECRET_KEY_PASSWORD = "password"
        private const val SECRET_SUFFIX = "mysql-secret"
    }
}
