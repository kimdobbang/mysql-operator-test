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
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim
import io.fabric8.kubernetes.api.model.apps.StatefulSet
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder
import io.javaoperatorsdk.operator.api.reconciler.Context
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource

class MySQLStatefulSet : CRUDKubernetesDependentResource<StatefulSet, MySQLInstance>() {

    override fun desired(primary: MySQLInstance?, context: Context<MySQLInstance?>?): StatefulSet? {
        val resource = requireNotNull(primary)
        val spec = resource.spec
        val existing = context?.getSecondaryResource(StatefulSet::class.java)?.orElse(null)
        val existingClaims = existing?.spec?.volumeClaimTemplates?.takeIf { it.isNotEmpty() }
        val appName = resource.metadata.name
        val secretName = resource.resourceNameWithSuffix(SECRET_SUFFIX)
        val resources = buildResources(spec.resources)

        val builder = StatefulSetBuilder()
            .withNewMetadata()
                .withName(resource.metadata.name)
                .withNamespace(resource.metadata.namespace)
                .withOwnerReferences(resource.createOwnerReferences())
                .withLabels<String, String>(managedLabels() + appLabels(appName))
            .endMetadata()
            .withNewSpec()
                .withReplicas(spec.replicas)
                .withServiceName(resource.metadata.name)
                .withNewSelector()
                    .withMatchLabels<String, String>(appLabels(appName))
                .endSelector()
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
                                .withSubPath("data")
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

        applyVolumeClaims(builder, existingClaims, spec.storage.size, spec.storage.storageClassName)

        return builder.build()
    }

    private fun applyVolumeClaims(
        builder: StatefulSetBuilder,
        existingClaims: List<PersistentVolumeClaim>?,
        storageSize: String,
        storageClassName: String?,
    ) {
        val specBuilder = builder.editSpec()
        if (existingClaims != null) {
            specBuilder.withVolumeClaimTemplates(existingClaims)
        } else {
            specBuilder
                .addNewVolumeClaimTemplate()
                    .withNewMetadata()
                        .withName(DATA_VOLUME_NAME)
                    .endMetadata()
                    .withNewSpec()
                        .withAccessModes("ReadWriteOnce")
                        .withStorageClassName(storageClassName)
                        .withNewResources()
                            .addToRequests("storage", Quantity(storageSize))
                        .endResources()
                    .endSpec()
                .endVolumeClaimTemplate()
        }
        specBuilder.endSpec()
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
