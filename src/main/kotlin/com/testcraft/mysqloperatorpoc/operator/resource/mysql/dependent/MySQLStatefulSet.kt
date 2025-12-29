package com.testcraft.mysqloperatorpoc.operator.resource.mysql.dependent

import com.testcraft.mysqloperatorpoc.operator.common.appLabels
import com.testcraft.mysqloperatorpoc.operator.common.createOwnerReferences
import com.testcraft.mysqloperatorpoc.operator.common.managedLabels
import com.testcraft.mysqloperatorpoc.operator.common.resourceNameWithSuffix
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.MySQLInstance
import com.testcraft.mysqloperatorpoc.operator.resource.mysql.ResourceSpec
import io.fabric8.kubernetes.api.model.Quantity
import io.fabric8.kubernetes.api.model.ResourceRequirements
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder
import io.fabric8.kubernetes.api.model.apps.StatefulSet
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource

class MySQLStatefulSet : CRUDKubernetesDependentResource<StatefulSet, MySQLInstance>() {

    override fun desired(primary: MySQLInstance?, context: Context<MySQLInstance?>?): StatefulSet? {
        val resource = requireNotNull(primary)
        val spec = resource.spec
        val appName = resource.metadata.name
        val secretName = resource.resourceNameWithSuffix(SECRET_SUFFIX)
        val resources = buildResources(spec.resources)

        return StatefulSetBuilder()
            .withNewMetadata()
                .withName(resource.metadata.name)
                .withNamespace(resource.metadata.namespace)
                .withOwnerReferences(resource.createOwnerReferences())
                .withLabels<String, String>(managedLabels() + appLabels(appName))
            .endMetadata()
            .withNewSpec()
                .withReplicas(1)
                .withServiceName(resource.metadata.name)
                .withNewSelector()
                    .withMatchLabels<String, String>(appLabels(appName))
                .endSelector()
                .addNewVolumeClaimTemplate()
                    .withNewMetadata()
                        .withName(DATA_VOLUME_NAME)
                    .endMetadata()
                    .withNewSpec()
                        .withAccessModes("ReadWriteOnce")
                        .withStorageClassName(spec.storage.storageClassName)
                        .withNewResources()
                            .addToRequests("storage", Quantity(spec.storage.size))
                        .endResources()
                    .endSpec()
                .endVolumeClaimTemplate()
                .withNewTemplate()
                    .withNewMetadata()
                        .withLabels<String, String>(appLabels(appName))
                    .endMetadata()
                    .withNewSpec()
                        .addNewContainer()
                            .withName(MYSQL_CONTAINER_NAME)
                            .withImage("${spec.image.registry}/${spec.image.imageName}:${spec.image.tag}")
                            .withResources(resources)
                            .addNewEnv()
                                .withName(ENV_MYSQL_DATABASE)
                                .withValue(spec.database)
                            .endEnv()
                            .addNewEnv()
                                .withName(ENV_MYSQL_ROOT_HOST)
                                .withValue("%")
                            .endEnv()
                            .addNewEnv()
                                .withName(ENV_MYSQL_ROOT_PASSWORD)
                                .withNewValueFrom()
                                    .withNewSecretKeyRef()
                                        .withName(secretName)
                                        .withKey(SECRET_KEY_PASSWORD)
                                    .endSecretKeyRef()
                                .endValueFrom()
                            .endEnv()
                            .addNewPort()
                                .withContainerPort(spec.port)
                            .endPort()
                            .addNewVolumeMount()
                                .withName(CONFIG_VOLUME_NAME)
                                .withMountPath(CONFIG_MOUNT_PATH)
                            .endVolumeMount()
                            .addNewVolumeMount()
                                .withName(DATA_VOLUME_NAME)
                                .withMountPath(DATA_MOUNT_PATH)
                            .endVolumeMount()
                        .endContainer()
                        .addNewVolume()
                            .withName(CONFIG_VOLUME_NAME)
                            .withNewConfigMap()
                                .withName(resource.metadata.name)
                                .addNewItem()
                                    .withKey(CONFIG_FILE_KEY)
                                    .withPath(CONFIG_FILE_KEY)
                                .endItem()
                            .endConfigMap()
                        .endVolume()
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build()
    }

    private fun buildResources(spec: ResourceSpec): ResourceRequirements {
        val builder = ResourceRequirementsBuilder()
        spec.limits.cpu?.let { builder.addToLimits("cpu", Quantity(it)) }
        spec.limits.memory?.let { builder.addToLimits("memory", Quantity(it)) }

        val requestsSpec = spec.requests ?: spec.limits
        requestsSpec.cpu?.let { builder.addToRequests("cpu", Quantity(it)) }
        requestsSpec.memory?.let { builder.addToRequests("memory", Quantity(it)) }

        return builder.build()
    }

    companion object {
        const val MYSQL_CONTAINER_NAME = "mysql"
        const val ENV_MYSQL_ROOT_PASSWORD = "MYSQL_ROOT_PASSWORD"
        const val ENV_MYSQL_ROOT_HOST = "MYSQL_ROOT_HOST"
        const val ENV_MYSQL_DATABASE = "MYSQL_DATABASE"
        const val SECRET_KEY_PASSWORD = "password"
        private const val SECRET_SUFFIX = "mysql-secret"
        private const val CONFIG_VOLUME_NAME = "mysql-config"
        private const val CONFIG_MOUNT_PATH = "/etc/mysql/conf.d"
        private const val CONFIG_FILE_KEY = "my.cnf"
        private const val DATA_VOLUME_NAME = "mysql-data"
        private const val DATA_MOUNT_PATH = "/var/lib/mysql"
    }
}
