package com.feedback.testinfra

import io.restassured.RestAssured
import io.restassured.response.ValidatableResponse
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.notNullValue

/** 公共 API 信封断言；业务测试各自补充自己接口的字段。 */
object ApiContract {
    fun configure(port: Int) {
        RestAssured.baseURI = "http://127.0.0.1"
        RestAssured.port = port
        RestAssured.basePath = "/api/v1"
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails()
    }

    fun success(response: ValidatableResponse, httpStatus: Int = 200): ValidatableResponse = response
        .statusCode(httpStatus)
        .body("code", equalTo(200))
        .body("message", notNullValue())

    fun error(response: ValidatableResponse, httpStatus: Int, code: Int): ValidatableResponse = response
        .statusCode(httpStatus)
        .body("code", equalTo(code))
        .body("message", notNullValue())

    fun reset() = RestAssured.reset()
}
