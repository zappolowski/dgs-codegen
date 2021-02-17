/*
 *
 *  Copyright 2020 Netflix, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.netflix.graphql.dgs.codegen.generators.kotlin

import com.netflix.graphql.dgs.codegen.CodeGenConfig
import com.netflix.graphql.dgs.codegen.KotlinCodeGenResult
import com.netflix.graphql.dgs.codegen.filterSkipped
import com.netflix.graphql.dgs.codegen.shouldSkip
import com.squareup.kotlinpoet.*
import graphql.language.*


class KotlinDataTypeGenerator(private val config: CodeGenConfig, private val document: Document): AbstractKotlinDataTypeGenerator(config.packageName + ".types", config) {
    fun generate(definition: ObjectTypeDefinition, extensions: List<ObjectTypeExtensionDefinition>): KotlinCodeGenResult {
        if(definition.shouldSkip()) {
            return KotlinCodeGenResult()
        }

        val fields = definition.fieldDefinitions
                .filterSkipped()
                .filter(ReservedKeywordFilter.filterInvalidNames)
            .map { Field(it.name, typeUtils.findReturnType(it.type), typeUtils.isNullable(it.type)) }
                .plus(extensions.flatMap { it.fieldDefinitions }
                        .filterSkipped()
                        .map { Field(it.name, typeUtils.findReturnType(it.type), typeUtils.isNullable(it.type)) })
        val interfaces = definition.implements
        return generate(definition.name, fields, interfaces, false, document)
    }

    override fun getPackageName(): String {
        return config.packageName + ".types"
    }
}


class KotlinInputTypeGenerator(private val config: CodeGenConfig, private val document: Document): AbstractKotlinDataTypeGenerator(config.packageName + ".types", config) {
    fun generate(definition: InputObjectTypeDefinition, extensions: List<InputObjectTypeExtensionDefinition>): KotlinCodeGenResult {

        val fields = definition.inputValueDefinitions
                .filter(ReservedKeywordFilter.filterInvalidNames)
                .map {
                    val type = typeUtils.findReturnType(it.type)
                    val defaultValue = it.defaultValue?.let { defVal ->
                        when (defVal) {
                            is BooleanValue -> CodeBlock.of("%L", defVal.isValue)
                            is IntValue -> CodeBlock.of("%L", defVal.value)
                            is StringValue -> CodeBlock.of("%S", defVal.value)
                            is FloatValue -> CodeBlock.of("%L", defVal.value)
                            is EnumValue -> CodeBlock.of("%M", MemberName(type as ClassName, defVal.name))
                            is ArrayValue -> if(defVal.values.isEmpty()) CodeBlock.of("emptyList()") else CodeBlock.of("listOf(%L)", defVal.values.map { v ->
                                when(v) {
                                    is BooleanValue -> CodeBlock.of("%L", v.isValue)
                                    is IntValue -> CodeBlock.of("%L", v.value)
                                    is StringValue -> CodeBlock.of("%S", v.value)
                                    is FloatValue -> CodeBlock.of("%Lf", v.value)
                                    is EnumValue -> CodeBlock.of("%M", MemberName((type as ParameterizedTypeName).typeArguments[0] as ClassName, v.name))
                                    else -> ""
                                }
                            }.joinToString())
                            else -> CodeBlock.of("%L", defVal)
                        }
                    }
                    Field(it.name, type, typeUtils.isNullable(it.type), defaultValue)
                }.plus(extensions.flatMap { it.inputValueDefinitions }.map { Field(it.name, typeUtils.findReturnType(it.type), typeUtils.isNullable(it.type)) })
        val interfaces = emptyList<Type<*>>()
        return generate(definition.name, fields, interfaces, true, document)
    }

    override fun getPackageName(): String {
        return config.packageName + ".types"
    }
}

internal data class Field(val name: String, val type: com.squareup.kotlinpoet.TypeName, val nullable: Boolean, val default: CodeBlock? = null)

abstract class AbstractKotlinDataTypeGenerator(private val packageName: String, private val config: CodeGenConfig) {
    protected val typeUtils = KotlinTypeUtils(packageName, config)

    internal fun generate(name: String, fields: List<Field>, interfaces: List<Type<*>>, isInputType: Boolean, document: Document): KotlinCodeGenResult {
        val kotlinType = TypeSpec.classBuilder(name)

        if(fields.isNotEmpty()) {
            kotlinType.addModifiers(KModifier.DATA)
        }

        val constructorBuilder = FunSpec.constructorBuilder()

        val interfaceTypes = document.getDefinitionsOfType(InterfaceTypeDefinition::class.java)
        if (interfaceTypes.isNotEmpty()) {
            kotlinType.addAnnotation(disableJsonTypeInfoAnnotation())
        }

        fields.forEach { field ->
            val returnType = if(field.nullable) field.type.copy(nullable = true) else field.type
            val parameterSpec = ParameterSpec.builder(field.name, returnType)
                    .addAnnotation(jsonPropertyAnnotation(field.name))

            if (field.default != null) {
                parameterSpec.defaultValue(field.default)
            } else {
                when (returnType) {
                    STRING -> if (field.nullable) parameterSpec.defaultValue("null")
                    INT -> if (field.nullable) parameterSpec.defaultValue("null")
                    FLOAT -> if (field.nullable) parameterSpec.defaultValue("null")
                    DOUBLE -> if (field.nullable) parameterSpec.defaultValue("null")
                    BOOLEAN -> if (field.nullable) parameterSpec.defaultValue("null")
                    else -> if (field.nullable) parameterSpec.defaultValue("null")
                }
            }

            val interfaceNames = interfaces.map { it as NamedNode<*> }.map { it.name }.toSet()
            val implementedInterfaces = interfaceTypes.filter { interfaceNames.contains(it.name) }
            val interfaceFields = implementedInterfaces.flatMap { it.fieldDefinitions }.map { it.name }.toSet()

            if (interfaceFields.contains(field.name)) {
                parameterSpec.addModifiers(KModifier.OVERRIDE)
            }

            constructorBuilder.addParameter(parameterSpec.build())
            val propertySpecBuilder = PropertySpec.builder(field.name, returnType)
            propertySpecBuilder.initializer(field.name)
            kotlinType.addProperty(propertySpecBuilder.build())
        }

        val unionTypes = document.getDefinitionsOfType(UnionTypeDefinition::class.java).filter { union ->
            union.memberTypes.map { it as graphql.language.TypeName }.map { it.name }.contains(name)
        }

        interfaces.plus(unionTypes).forEach {
            if(it is NamedNode<*>) {
                kotlinType.addSuperinterface(ClassName.bestGuess("${getPackageName()}.${it.name}"))
            }
        }

        kotlinType.primaryConstructor(constructorBuilder.build())
        if (isInputType) {
            kotlinType.addFunction(FunSpec.builder("toString")
                    .returns(STRING)
                    .addCode(addToString(fields, kotlinType))
                    .addModifiers(KModifier.PUBLIC)
                    .addModifiers(KModifier.OVERRIDE)
                    .build())
        }
        val typeSpec = kotlinType.build()

        val fileSpec = FileSpec.builder(getPackageName(), typeSpec.name!!).addType(typeSpec).build()

        return KotlinCodeGenResult(listOf(fileSpec))
    }

    private fun addToString(fields: List<Field>, kotlinType: TypeSpec.Builder): String {
        val toStringBody = StringBuilder("return \"{\" + ")
        fields.mapIndexed { index, field ->
            when (val fieldTypeName = field.type) {
                is ParameterizedTypeName -> {
                    val innerType = fieldTypeName.typeArguments[0]
                    if (typeUtils.isStringInput(innerType)) {
                        val name = if (innerType is ClassName ) {
                            "serializeListOf" + innerType.simpleName
                        } else {
                            "serializeListOf$innerType"
                        }
                        addToStringForListOfStrings(name,field, kotlinType)
                        """
                            "${field.name}:" + ${name}(${field.name}) + "${if (index < fields.size - 1) "," else ""}" +
                        """.trimIndent()
                    } else {
                        defaultString(field, index, fields)
                    }
                }

                is ClassName -> {
                    if (typeUtils.isStringInput(fieldTypeName)) {
                        quotedString(field, index, fields)
                    } else {
                        defaultString(field, index, fields)
                    }
                }
                else -> {
                    defaultString(field, index, fields)
                }
            }
        }.forEach { toStringBody.append(it)}

        return toStringBody.append("""
            "}"
        """.trimIndent()).toString()
    }

    private fun defaultString(field: Field, index: Int, fields: List<Field>): String {
        return """
            "${field.name}:" + ${field.name} + "${if (index < fields.size - 1) "," else ""}" +
            """.trimIndent()
    }

    private fun quotedString(field: Field, index: Int, fields: List<Field>): String {
        return if (field.nullable) {
            """
            "${field.name}:" + "${'$'}{if(${field.name} != null) "\"" else ""}" + ${field.name} + "${'$'}{if(${field.name} != null) "\"" else ""}" + "${if (index < fields.size - 1) "," else ""}" +
            """.trimIndent()
        } else {
            """
            "${field.name}:" + "\"" + ${field.name} + "\"" + "${if (index < fields.size - 1) "," else ""}" +
          """.trimIndent()
        }
    }

    private fun addToStringForListOfStrings(name: String, field: Field, kotlinType: TypeSpec.Builder) {
        if (kotlinType.funSpecs.any { it.name == name }) return

        val methodBuilder = FunSpec.builder(name)
                .addModifiers(KModifier.PRIVATE)
                .addParameter("inputList", field.type)
                .returns(STRING.copy(nullable = true))

        val toStringBody = StringBuilder()
        if (field.nullable) {
            toStringBody.append("""
                if (inputList == null) {
                    return null
                }
                
            """.trimIndent()
            )
        }
        toStringBody.append(
            """
                val builder = java.lang.StringBuilder()
                builder.append("[")
                if (! inputList.isEmpty()) {
                    val result = inputList.joinToString() {"\"" + it + "\""}
                    builder.append(result)
                }
                builder.append("]")
                return  builder.toString()
            """.trimIndent()
        )


        methodBuilder.addStatement(toStringBody.toString())
        kotlinType.addFunction(methodBuilder.build())
    }

    abstract fun getPackageName(): String
}