package org.robolectric.annotation

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Config(val sdk: IntArray = [], val application: KClass<*> = Any::class, val qualifiers: String = "")
