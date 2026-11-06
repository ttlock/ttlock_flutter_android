package com.ttlock.ttlock_flutter

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * Gson 2.6.2 没有 [com.google.gson.reflect.TypeToken.getParameterized]。
 * 匿名 `object : TypeToken<List<...>>() {}` 在 R8 full mode 下会丢失泛型签名，
 * Release 里构造时抛 IllegalStateException。这里把类型参数直接放进 [ParameterizedType]，
 * 不再依赖 class 文件里的 Signature。
 */
internal fun gsonListType(elementType: Type): Type =
    GsonParameterizedType(List::class.java, arrayOf(elementType))

internal fun gsonMapType(keyType: Type, valueType: Type): Type =
    GsonParameterizedType(Map::class.java, arrayOf(keyType, valueType))

internal val gsonStringAnyMapListType: Type =
    gsonListType(gsonMapType(String::class.java, Any::class.java))

private class GsonParameterizedType(
    private val raw: Class<*>,
    private val typeArguments: Array<Type>,
) : ParameterizedType {
    override fun getActualTypeArguments(): Array<Type> = typeArguments.clone()
    override fun getRawType(): Type = raw
    override fun getOwnerType(): Type? = null
}
