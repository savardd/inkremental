package trikita.anvilgen

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.squareup.javapoet.*
import groovy.lang.Closure
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.lang.Deprecated
import java.lang.reflect.Method
import java.net.URL
import java.net.URLClassLoader
import java.util.jar.JarEntry
import java.util.jar.JarFile
import javax.lang.model.element.Modifier
import kotlin.Boolean
import kotlin.Char
import kotlin.Int
import kotlin.String
import kotlin.Unit
import kotlin.let

open class DSLGeneratorTask : DefaultTask() {

    var configuration: Configuration? = null
    lateinit var jarFiles: List<File>
    lateinit var nullabilitySourceFiles: List<File>
    var dependencies: List<File> = listOf()

    lateinit var javadocContains: String
    lateinit var outputDirectory: String
    lateinit var outputClassName: String
    lateinit var packageName: String
    var superclass: ClassName? = null

    var isSourceSdk: Boolean = false

    lateinit var nullabilityHolder : NullabilityHolder

    @TaskAction
    fun generate() {
        val configuration = configuration
        if(configuration != null) {
            val resolved = configuration.resolvedConfiguration
            val libs = resolved.firstLevelModuleDependencies.flatMap { it.moduleArtifacts }.map { it.file }.toList()
            val deps = resolved.resolvedArtifacts.map { it.file }.toList()

            project.logger.info("Generator task resolved $configuration\nLibs:\n" +
                    libs.map { it.absolutePath }.sorted().joinToString(separator = "\n") { "    $it" } +
                    "\nDeps ${deps.size}:\n" +
                    deps.map { it.absolutePath }.sorted().joinToString(separator = "\n") { "    $it" })

            jarFiles = libs
            nullabilitySourceFiles = libs
            dependencies = deps + dependencies
        }

        nullabilityHolder = NullabilityHolder(isSourceSdk)

        var attrsBuilder = TypeSpec.classBuilder(outputClassName)
                .addJavadoc("DSL for creating views and settings their attributes.\n" +
                        "This file has been generated by " +
                        "{@code gradle $name}.\n" +
                        "$javadocContains.\n" +
                        "Please, don't edit it manually unless for debugging.\n")
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)

        if (superclass != null) {
            attrsBuilder = attrsBuilder.superclass(superclass)
        }

        attrsBuilder.addSuperinterface(ClassName.get("trikita.anvil", "Anvil", "AttributeSetter"))
        attrsBuilder.addStaticBlock(CodeBlock.of(
                "Anvil.registerAttributeSetter(new \$L());\n", outputClassName))

        val attrSwitch = MethodSpec.methodBuilder("set")
                .addParameter(ClassName.get("android.view", "View"), "v")
                .addParameter(ClassName.get("java.lang", "String"), "name")
                .addParameter(ParameterSpec.builder(TypeName.OBJECT, "arg")
                        .addModifiers(Modifier.FINAL).build())
                .addParameter(ParameterSpec.builder(TypeName.OBJECT, "old")
                        .addModifiers(Modifier.FINAL).build())
                .returns(TypeName.BOOLEAN)
                .addModifiers(Modifier.PUBLIC)

        val attrCases = CodeBlock.builder().beginControlFlow("switch (name)")

        val attrs = mutableListOf<Attr>()

        forEachView { view ->
            processViews(attrsBuilder, view)
            forEachMethod(view) { m, name, arg, isListener, isNullable ->
                val attr: Attr?
                if (isListener) {
                    attr = listener(name, m, arg)
                } else {
                    attr = setter(name, m, arg, isNullable)
                }
                if (attr != null) {
                    attrs.add(attr)
                }
            }
        }

        finalizeAttrs(attrs, attrsBuilder, attrCases)

        attrCases.endControlFlow()

        attrSwitch.addCode(attrCases.build()).addCode("return false;\n")

        attrsBuilder.addMethod(attrSwitch.build())

        JavaFile.builder(packageName, attrsBuilder.build())
                .build()
                .writeTo(project.file("src/$outputDirectory/java"))
    }

    fun forEachView(cb: (Class<*>) -> Unit) {
        val urls = jarFiles.map { URL("jar", "", "file:${it.absolutePath}!/")  }.toMutableList()
        for (dep in dependencies) {
            urls.add(URL("jar", "", "file:${dep.absolutePath}!/"))
        }
        val loader = URLClassLoader(urls.toTypedArray(), javaClass.classLoader)
        val viewClass = loader.loadClass("android.view.View")

        val nullabilityClassLoader = URLClassLoader(nullabilitySourceFiles.map { URL("jar", "", "file:${it.absolutePath}!/") }.toTypedArray(), javaClass.classLoader)

        val jarEntriesList = mutableListOf<JarEntry>()
        jarFiles.map { JarFile(it).entries() }.forEach {
            jarEntriesList.addAll(it.toList())
        }

        jarEntriesList.sortBy { it.name }
        for (e in jarEntriesList) {
            if (e.name.endsWith(".class")) {
                val className = e.name.replace(".class", "").replace("/", ".")
                // Skip inner classes
                if (className.contains('$')) {
                    continue
                }
                try {
                    val c = loader.loadClass(className)

                    nullabilityHolder.fillClassNullabilityInfo(nullabilityClassLoader, e.name)

                    if (viewClass.isAssignableFrom(c) &&
                            java.lang.reflect.Modifier.isPublic(c.modifiers)) {
                        cb(c)
                    }
                } catch (ignored: NoClassDefFoundError) {
                    // Simply skip this class.
                    ignored.printStackTrace()
                }
            }
        }
    }

    fun forEachMethod(c: Class<*>, cb: (Method, String, Class<*>, Boolean, Boolean?) -> Unit) {
        val declaredMethods = c.declaredMethods.clone()
        declaredMethods.sortBy { it.name }
        for (m in declaredMethods) {
            if (!java.lang.reflect.Modifier.isPublic(m.modifiers) || m.isSynthetic || m.isBridge) {
                continue
            }

            val parameterType = getMethodParameterType(m) ?: continue
            val isNullable = nullabilityHolder.isParameterNullable(m)

            val formattedMethodName = formatMethodName(m.name, m.parameterCount)
            formattedMethodName?.let {
                if (formattedMethodName.isListener) {
                    cb(m, formattedMethodName.formattedName, parameterType, formattedMethodName.isListener, true)
                } else {
                    cb(m, formattedMethodName.formattedName, parameterType, formattedMethodName.isListener, isNullable)
                }
            }
        }
    }

    fun getMethodParameterType(m: Method): Class<*>? {
        if (m.parameterTypes.size == 0) {
            return null
        }

        val parameterType = m.parameterTypes[0]
        if (!java.lang.reflect.Modifier.isPublic(parameterType.modifiers)) {
            // If the parameter is not public then the method is inaccessible for us.
            return null
        } else if (m.annotations != null) {
            for (a in m.annotations) {
                // Don't process deprecated methods.
                if (a.annotationClass.equals(Deprecated::class)) {
                    return null
                }
            }
        } else if (m.declaringClass.canonicalName == "android.view.View") {
            return parameterType
        }

        // Check if the method overrode from a super class.
        var supClass = m.declaringClass.superclass
        while (true) {
            if (supClass == null) {
                break
            }
            try {
                supClass.getMethod(m.name, *m.parameterTypes)
                return null
            } catch (ignored: NoSuchMethodException) {
                // Intended to occur
            }

            if (supClass.canonicalName == "android.view.View") {
                break
            }
            supClass = supClass.superclass
        }
        return parameterType
    }

    //
    // Views generator functions:
    // For each view generates a function that calls v(C), where C is a view
    // class, e.g. FrameLayout.class => frameLayout() { v(FrameLayout.class) }
    //
    fun processViews(builder: TypeSpec.Builder, view: Class<*>) {
        val className = view.canonicalName
        var name = view.simpleName
        val extension = project.extensions.getByName("anvilgen") as AnvilGenPluginExtension

        val quirk = extension.quirks[className]
        if (quirk != null) {
            val alias = quirk["__viewAlias"]
            // if the whole view class is banned - do nothing
            if (alias == false) {
                return
            } else if (alias != null) {
                name = alias as String
            }
        }
        name = toCase(name, { c -> Character.toLowerCase(c) })
        val baseDsl = ClassName.get("trikita.anvil", "BaseDSL")
        val result = ClassName.get("trikita.anvil", "BaseDSL", "ViewClassResult")
        builder.addMethod(MethodSpec.methodBuilder(name)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(result)
                .addStatement("return \$T.v(\$T.class)", baseDsl, view)
                .build())
        builder.addMethod(MethodSpec.methodBuilder(name)
                .addParameter(ParameterSpec.builder(ClassName.get("trikita.anvil",
                        "Anvil", "Renderable"), "r").build())
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeName.VOID.box())
                .addStatement("return \$T.v(\$T.class, r)", baseDsl, view)
                .build())
    }

    //
    // Attrs generator functions
    //
    fun listener(name: String,
                 m: Method,
                 listenerClass: Class<*>): Attr? {
        val viewClass = m.declaringClass.canonicalName
        val listener = TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(listenerClass)
        val declaredMethods = listenerClass.declaredMethods.clone()
        declaredMethods.sortBy { it.name }
        declaredMethods.forEach { lm ->
            val methodBuilder = MethodSpec.methodBuilder(lm.name)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(lm.returnType)

            var args = ""
            lm.parameterTypes.forEachIndexed { i, v ->
                methodBuilder.addParameter(v, "a$i")
                args += (if (i != 0) ", " else "") + "a$i"
            }

            if (lm.returnType.equals(Void.TYPE)) {
                methodBuilder
                        .addStatement("((\$T) arg).\$L($args)", listenerClass, lm.name)
                        .addStatement("\$T.render()", ClassName.get("trikita.anvil", "Anvil"))
            } else {
                methodBuilder
                        .addStatement("\$T r = ((\$T) arg).\$L($args)", lm.returnType, listenerClass, lm.name)
                        .addStatement("\$T.render()", ClassName.get("trikita.anvil", "Anvil"))
                        .addStatement("return r")
            }

            listener.addMethod(methodBuilder.build())
        }

        val attr = Attr(name, listenerClass, m)
        if (viewClass == "android.view.View") {
            attr.code.beginControlFlow("if (arg != null)", m.declaringClass)
                    .addStatement("v.${m.name}(\$L)", listener.build())
                    .nextControlFlow("else")
                    .addStatement("v.${m.name}((\$T) null)", listenerClass)
                    .endControlFlow()
                    .addStatement("return true")
            attr.unreachableBreak = true
        } else {
            attr.code.beginControlFlow("if (v instanceof \$T && arg instanceof \$T)", m.declaringClass, listenerClass)
                    .beginControlFlow("if (arg != null)", m.declaringClass)
                    .addStatement("((\$T) v).${m.name}(\$L)", m.declaringClass,
                            listener.build())
                    .nextControlFlow("else")
                    .addStatement("((\$T) v).${m.name}((\$T) null)", m.declaringClass,
                            listenerClass)
                    .endControlFlow()
                    .addStatement("return true")
                    .endControlFlow()
        }
        return attr
    }

    fun setter(name: String,
               m: Method,
               argClass: Class<*>,
               isNullable : Boolean?): Attr? {

        val viewClass = m.declaringClass.canonicalName
        val attr = Attr(name, argClass, m)

        val extension = project.extensions.getByName("anvilgen") as AnvilGenPluginExtension
        val quirks = extension.quirks[viewClass]
        if (quirks != null) {
            val closure = quirks["${m.name}:${argClass.canonicalName}"]
            if (closure != null) {
                return (closure as Closure<Attr?>).call(attr)
            } else {
                val nameClosure = quirks[m.name]
                if (nameClosure != null) {
                    return (nameClosure as Closure<Attr?>).call(attr)
                }
            }
        }
        val argBoxed = TypeName.get(argClass).box()
        if (viewClass == "android.view.View") {
            attr.code.beginControlFlow("if (arg instanceof \$T)", argBoxed)
                    .addStatement("v.${m.name}((\$T) arg)", argClass)
                    .addStatement("return true")
                    .endControlFlow()
        } else {
            val checkArgLiteral = if (isNullable == true) {
                "(arg == null || arg instanceof \$T)"
            } else {
                "arg instanceof \$T"
            }

            attr.code.beginControlFlow("if (v instanceof \$T && $checkArgLiteral)", m.declaringClass, argBoxed)
                .addStatement("((\$T) v).${m.name}((\$T) arg)", m.declaringClass, argClass)
                .addStatement("return true")
                .endControlFlow()
        }
        return attr
    }

    fun addWrapperMethod(builder: TypeSpec.Builder, name: String, argClass: Class<*>, className: String) {
        val baseDsl = ClassName.get("trikita.anvil", "BaseDSL")

        val nullableAnnotation: AnnotationSpec? = when (nullabilityHolder.isParameterNullable(className, name, argClass.typeName)) {
            true -> AnnotationSpec
                        .builder(Nullable::class.java)
                        .build()
            false -> AnnotationSpec
                        .builder(NonNull::class.java)
                        .build()
            else -> null
        }

        val parameterBuilder = ParameterSpec.builder(argClass, "arg")
        if (nullableAnnotation != null) {
            parameterBuilder.addAnnotation(nullableAnnotation)
        }

        val wrapperMethod = MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addParameter(parameterBuilder.build())
            .returns(TypeName.VOID.box())
            .addStatement("return \$T.attr(\$S, arg)", baseDsl, name)
        builder.addMethod(wrapperMethod.build())
    }

    fun finalizeAttrs(attrs: List<Attr>, dsl: TypeSpec.Builder, cases: CodeBlock.Builder) {
        attrs.sortedBy { it.name }.groupBy { it.name }.forEach {
            var all = it.value.sortedBy { it.param.name }
            var filered = all.filter { a ->
                !all.any { b ->
                    a != b && a.param == b.param &&
                            a.setter.declaringClass.isAssignableFrom(b.setter.declaringClass)
                }
            }

            cases.add("case \$S:\n", it.key)
            cases.indent()
            filered.filter { it.setter.declaringClass.canonicalName != "android.view.View" }.forEach {
                cases.add(it.code.build())
            }

            val common = filered.firstOrNull { it.setter.declaringClass.canonicalName == "android.view.View" }
            if (common != null) {
                cases.add(common.code.build())
            }
            if (common == null || !common.unreachableBreak) {
                cases.add("break;\n")
            }
            cases.unindent()
        }

        attrs.sortedBy { it.name }.groupBy { it.name }.forEach {
            val name = it.key
            val className = it.value.firstOrNull()?.setter?.declaringClass?.canonicalName ?: ""
            it.value.sortedBy { it.param.name }.groupBy { it.param }.forEach {
                addWrapperMethod(dsl, name, it.key, className)
            }
        }
    }

    fun toCase(s: String, fn: (Char) -> Char): String {
        return fn(s[0]).toString() + s.substring(1)
    }

    data class Attr(val name: String,
                    val param: Class<*>,
                    val setter: Method,
                    var unreachableBreak: Boolean = false,
                    val code: CodeBlock.Builder = CodeBlock.builder())
}

data class FormattedMethod(val formattedName : String, val isListener : Boolean)

fun formatMethodName(originalMethodName : String, parameterCount : Int) : FormattedMethod? {
    return if (originalMethodName.matches(Regex("^setOn.*Listener$"))) {
        FormattedMethod(
            "on" + originalMethodName.substring(5, originalMethodName.length - 8),
            true
        )
    } else if (originalMethodName.startsWith("set")
        && originalMethodName.length > 3
        && originalMethodName[3].isUpperCase()
        && parameterCount == 1) {
            FormattedMethod(
                Character.toLowerCase(originalMethodName[3]).toString() + originalMethodName.substring(
                    4
                ), false
            )
    } else null
}
