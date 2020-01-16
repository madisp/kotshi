package se.ansman.kotshi.generators

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName.Companion.member
import com.squareup.kotlinpoet.NameAllocator
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.plusParameter
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.metadata.ImmutableKmClass
import com.squareup.kotlinpoet.metadata.isInner
import com.squareup.kotlinpoet.metadata.isInternal
import com.squareup.kotlinpoet.metadata.isLocal
import com.squareup.kotlinpoet.metadata.isPublic
import com.squareup.kotlinpoet.metadata.specs.ClassInspector
import com.squareup.kotlinpoet.metadata.specs.internal.ClassInspectorUtil
import com.squareup.kotlinpoet.metadata.specs.toTypeSpec
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import se.ansman.kotshi.GeneratedAdapter
import se.ansman.kotshi.JsonDefaultValue
import se.ansman.kotshi.KotshiJsonAdapterFactory
import se.ansman.kotshi.KotshiUtils
import se.ansman.kotshi.NamedJsonAdapter
import se.ansman.kotshi.ProcessingError
import se.ansman.kotshi.SerializeNulls
import se.ansman.kotshi.applyEachIndexed
import se.ansman.kotshi.applyIf
import se.ansman.kotshi.maybeAddGeneratedAnnotation
import se.ansman.kotshi.nullable
import java.io.IOException
import javax.annotation.processing.Filer
import javax.lang.model.SourceVersion
import javax.lang.model.element.TypeElement
import javax.lang.model.util.Elements

abstract class AdapterGenerator(
    classInspector: ClassInspector,
    protected val elements: Elements,
    protected val element: TypeElement,
    protected val metadata: ImmutableKmClass,
    protected val globalConfig: GlobalConfig
) {
    protected val nameAllocator = NameAllocator().apply {
        newName("options")
        newName("value")
        newName("writer")
        newName("reader")
        newName("stringBuilder")
        newName("it")
    }

    protected val elementTypeSpec = metadata.toTypeSpec(classInspector)
    protected val className = ClassInspectorUtil.createClassName(metadata.name)
    private val typeVariables = elementTypeSpec.typeVariables
        // Removes the variance
        .map { TypeVariableName(it.name, *it.bounds.toTypedArray()) }

    protected val typeName = if (typeVariables.isEmpty()) {
        className
    } else {
        className.parameterizedBy(typeVariables)
    }

    protected val value = ParameterSpec.builder("value", typeName.nullable()).build()

    fun generateAdapter(
        sourceVersion: SourceVersion,
        filer: Filer
    ): GeneratedAdapter {
        when {
            metadata.isInner ->
                throw ProcessingError("@JsonSerializable can't be applied to inner classes", element)
            metadata.isLocal ->
                throw ProcessingError("@JsonSerializable can't be applied to local classes", element)
            !metadata.isPublic && !metadata.isInternal ->
                throw ProcessingError("Classes annotated with @JsonSerializable must public or internal", element)
        }

        val adapterClassName = ClassName(className.packageName, "Kotshi${className.simpleNames.joinToString("_")}JsonAdapter")

        val typeSpec = TypeSpec.classBuilder(adapterClassName)
            .addModifiers(KModifier.INTERNAL)
            .addOriginatingElement(element)
            .maybeAddGeneratedAnnotation(elements, sourceVersion)
            .addTypeVariables(typeVariables)
            .superclass(namedJsonAdapter.plusParameter(typeName))
            .addSuperclassConstructorParameter("%S", "KotshiJsonAdapter(${className.simpleNames.joinToString(".")})")
            .apply { addMethods() }
            .build()

        FileSpec.builder(adapterClassName.packageName, adapterClassName.simpleName)
            .addComment("Code generated by Kotshi. Do not edit.")
            .addType(typeSpec)
            .build()
            .writeTo(filer)

        return GeneratedAdapter(
            targetType = className,
            className = adapterClassName,
            typeVariables = typeVariables,
            requiresMoshi = typeSpec.primaryConstructor
                ?.parameters
                ?.any { it.name == "moshi" }
                ?: false
        )
    }

    protected abstract fun TypeSpec.Builder.addMethods()

    protected fun TypeSpec.Builder.maybeAddCompanion(jsonNames: Collection<String>): TypeSpec.Builder =
        applyIf(jsonNames.isNotEmpty()) {
            addType(TypeSpec.companionObjectBuilder()
                .addModifiers(KModifier.PRIVATE)
                .addOptions(jsonNames)
                .build())
        }

    protected fun TypeSpec.Builder.addOptions(jsonNames: Collection<String>): TypeSpec.Builder =
        addProperty(PropertySpec.builder("options", jsonReaderOptions, KModifier.PRIVATE)
            .addAnnotation(jvmStatic)
            .initializer(CodeBlock.Builder()
                .add("%T.of(«", jsonReaderOptions)
                .applyIf(jsonNames.size > 1) { add("\n") }
                .applyEachIndexed(jsonNames) { index, name ->
                    if (index > 0) {
                        add(",\n")
                    }
                    add("%S", name)
                }
                .applyIf(jsonNames.size > 1) { add("\n") }
                .add("»)")
                .build())
            .build())
}

val kotshiUtilsByteValue = KotshiUtils::class.member("byteValue")
val kotshiUtilsValue = KotshiUtils::class.member("value")
val kotshiUtilsNextFloat = KotshiUtils::class.member("nextFloat")
val kotshiUtilsNextByte = KotshiUtils::class.member("nextByte")
val kotshiUtilsNextShort = KotshiUtils::class.member("nextShort")
val kotshiUtilsNextChar = KotshiUtils::class.member("nextChar")
val kotshiUtilsAppendNullableError = KotshiUtils::class.member("appendNullableError")
val kotshiUtilsCreateJsonQualifierImplementation = KotshiUtils::class.member("createJsonQualifierImplementation")

val jvmStatic = JvmStatic::class.java.asClassName()
val namedJsonAdapter = NamedJsonAdapter::class.java.asClassName()
val jsonAdapter = JsonAdapter::class.java.asClassName()
val jsonAdapterFactory = JsonAdapter.Factory::class.java.asClassName()
val jsonDefaultValue = JsonDefaultValue::class.java.asClassName()
val jsonDataException = JsonDataException::class.java.asClassName()
val jsonReaderOptions = JsonReader.Options::class.java.asClassName()
val jsonReaderToken = JsonReader.Token::class.java.asClassName()
val ioException = IOException::class.java.asClassName()
val stringBuilder = ClassName("kotlin.text", "StringBuilder")
val jsonWriter = JsonWriter::class.java.asClassName()
val jsonReader = JsonReader::class.java.asClassName()

val writerParameter = ParameterSpec.builder("writer", jsonWriter).build()
val readerParameter = ParameterSpec.builder("reader", jsonReader).build()
val moshiParameter = ParameterSpec.builder("moshi", Moshi::class.java).build()

data class GlobalConfig(
    val useAdaptersForPrimitives: Boolean,
    val serializeNulls: SerializeNulls
) {
    constructor(factory: KotshiJsonAdapterFactory) : this(
        useAdaptersForPrimitives = factory.useAdaptersForPrimitives,
        serializeNulls = factory.serializeNulls
    )

    companion object {
        val DEFAULT = GlobalConfig(
            useAdaptersForPrimitives = false,
            serializeNulls = SerializeNulls.DEFAULT
        )
    }
}