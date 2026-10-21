package com.feedback.common

object SafeErrorDetail {
    fun code(error:Throwable):String=generateSequence(error){it.cause}.last().javaClass.simpleName.take(100)
    fun summary(error:Throwable,stage:String):String="$stage:${code(error)}".take(500)
}
