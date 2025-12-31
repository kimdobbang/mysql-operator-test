package com.testcraft.mysqloperatorpoc.config

import io.fabric8.kubernetes.client.CustomResource
import io.javaoperatorsdk.operator.Operator
import io.javaoperatorsdk.operator.api.reconciler.Reconciler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OperatorConfig {
    @Bean(initMethod = "start", destroyMethod = "stop")
    fun operator(reconcilers: List<Reconciler<out CustomResource<*, *>>>): Operator {
        val operator = Operator()
        reconcilers.forEach { operator.register(it) }
        return operator
    }
}
