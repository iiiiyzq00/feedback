package com.feedback.tools

import org.flywaydb.core.Flyway

fun main(){
    val url=System.getenv("DB_URL")?:"jdbc:mysql://127.0.0.1:3306/feedback_platform?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai"
    require(url.substringBefore('?').endsWith("/feedback_platform")){"FLYWAY_REPAIR_DATABASE_NOT_ALLOWED"}
    val user=System.getenv("DB_USERNAME")?:error("DB_USERNAME_REQUIRED")
    val password=System.getenv("DB_PASSWORD")?:error("DB_PASSWORD_REQUIRED")
    val result=Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").load().repair()
    println("FLYWAY_REPAIR_SUCCESS removed=${result.migrationsRemoved.size} aligned=${result.migrationsAligned.size} deleted=${result.migrationsDeleted.size}")
}
