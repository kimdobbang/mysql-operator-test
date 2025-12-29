package com.testcraft.mysqloperatorpoc.operator.common

import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder
import io.fabric8.kubernetes.client.CustomResource

const val LABEL_MANAGED_KEY = "managed"
const val LABEL_MANAGED_VALUE = "true"
const val LABEL_APP_KEY = "app"
const val MANAGED_LABEL_SELECTOR = "managed=true"

fun CustomResource<*, *>.createOwnerReferences(): OwnerReference = OwnerReferenceBuilder()
    .withApiVersion(apiVersion)
    .withKind(kind)
    .withName(metadata.name)
    .withUid(metadata.uid)
    .withBlockOwnerDeletion(true)
    .withController(true)
    .build()

fun CustomResource<*, *>.resourceNameWithSuffix(suffix: String): String = "${metadata.name}-$suffix"

fun CustomResource<*, *>.appLabelValue(): String = metadata.name

fun managedLabels(): Map<String, String> = mapOf(LABEL_MANAGED_KEY to LABEL_MANAGED_VALUE)

fun appLabels(appName: String): Map<String, String> = mapOf(LABEL_APP_KEY to appName)


