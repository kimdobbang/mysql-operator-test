package com.testcraft.mysqloperatorpoc.operator.resource.mysql.dependent

import com.testcraft.mysqloperatorpoc.operator.resource.mysql.MySQLInstance
import com.testcraft.mysqloperatorpoc.operator.common.MANAGED_LABEL_SELECTOR
import com.testcraft.mysqloperatorpoc.operator.common.appLabelValue
import com.testcraft.mysqloperatorpoc.operator.common.appLabels
import com.testcraft.mysqloperatorpoc.operator.common.createOwnerReferences
import com.testcraft.mysqloperatorpoc.operator.common.managedLabels
import io.fabric8.kubernetes.api.model.IntOrString
import io.fabric8.kubernetes.api.model.Service
import io.fabric8.kubernetes.api.model.ServiceBuilder
import io.javaoperatorsdk.operator.api.config.informer.Informer
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent

@KubernetesDependent(informer = Informer(labelSelector = MANAGED_LABEL_SELECTOR))
class MySQLService : CRUDKubernetesDependentResource<Service, MySQLInstance>() {
    override fun desired(primary: MySQLInstance?, context: Context<MySQLInstance?>?): Service? {
        val resource = requireNotNull(primary)
        val metadata = requireNotNull(resource.metadata)
        val servicePort = resource.spec.port
        val appName = resource.appLabelValue()

        return ServiceBuilder()
            .withNewMetadata()
                .withName(metadata.name)
                .withNamespace(metadata.namespace)
                .withOwnerReferences(resource.createOwnerReferences())
                .withLabels<String, String>(managedLabels() + appLabels(appName))
            .endMetadata()
            .withNewSpec()
                .withClusterIP("None")
                .withPublishNotReadyAddresses(true)
                .addNewPort()
                    .withPort(servicePort)
                    .withTargetPort(IntOrString(servicePort))
                .endPort()
                .withSelector<String, String>(appLabels(appName))
            .endSpec()
            .build()
    }
}
