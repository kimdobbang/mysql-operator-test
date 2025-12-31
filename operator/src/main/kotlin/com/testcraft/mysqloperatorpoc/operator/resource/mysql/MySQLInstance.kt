package com.testcraft.mysqloperatorpoc.operator.resource.mysql

import io.fabric8.kubernetes.api.model.Namespaced
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.model.annotation.Group
import io.fabric8.kubernetes.model.annotation.Kind
import io.fabric8.kubernetes.model.annotation.Version

@Group("testcraft.com")
@Version("v1")
@Kind("MySQLInstance")
class MySQLInstance : CustomResource<MySQLSpec, MySQLStatus>(), Namespaced {
    override fun initSpec(): MySQLSpec = MySQLSpec()

    override fun initStatus(): MySQLStatus = MySQLStatus()
}
