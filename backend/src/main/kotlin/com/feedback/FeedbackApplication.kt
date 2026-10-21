package com.feedback

import com.feedback.mock.MockDataCommand
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType

@SpringBootApplication
@ComponentScan(
    basePackages = ["com.feedback"],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.CUSTOM,
            classes = [TypeExcludeFilter::class]
        ),
        ComponentScan.Filter(
            type = FilterType.CUSTOM,
            classes = [AutoConfigurationExcludeFilter::class]
        ),
        ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = ["com\\.feedback\\.tools\\..*Configuration"]
        )
    ]
)
/** 平台启动入口；显式保留 Spring Boot 测试切片与自动配置的默认扫描排除规则。 */
class FeedbackApplication

/** 启动正式应用；命令行命中 mock 数据命令时仅执行离线命令并立即退出。 */
fun main(args: Array<String>) {
    if (MockDataCommand.supports(args)) {
        MockDataCommand.run(args)
        return
    }
    runApplication<FeedbackApplication>(*args)
}
