package com.feedback.testinfra

import io.restassured.RestAssured.given
import io.restassured.http.ContentType
import io.restassured.specification.RequestSpecification

object TestAuth {
    fun adminRequest(): RequestSpecification {
        val login = given()
            .contentType(ContentType.JSON)
            .body(mapOf("username" to "d9-admin", "password" to "D9-Integration-Only-Password!"))
            .post("/auth/login")
            .then().statusCode(200).body("code", org.hamcrest.Matchers.equalTo(200))
            .extract().response()
        return given()
            .cookie("feedback_session", login.cookie("feedback_session"))
            .cookie("XSRF-TOKEN", login.cookie("XSRF-TOKEN"))
            .header("X-XSRF-TOKEN", login.cookie("XSRF-TOKEN"))
    }
}
