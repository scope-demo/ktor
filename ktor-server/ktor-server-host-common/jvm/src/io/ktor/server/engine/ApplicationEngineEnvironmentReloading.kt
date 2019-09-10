/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.server.engine

import io.ktor.application.*
import io.ktor.config.*
import io.ktor.http.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import org.slf4j.*
import java.io.*
import java.lang.reflect.*
import java.net.*
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.attribute.*
import java.util.concurrent.locks.*
import kotlin.concurrent.*
import kotlin.coroutines.*
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.*

/**
 * Implements [ApplicationEngineEnvironment] by loading an [Application] from a folder or jar.
 *
 * [watchPaths] specifies substrings to match against class path entries to monitor changes in folder/jar and implements hot reloading
 */
@EngineAPI
class ApplicationEngineEnvironmentReloading(
        override val classLoader: ClassLoader,
        override val log: Logger,
        override val config: ApplicationConfig,
        override val connectors: List<EngineConnectorConfig>,
        private val modules: List<Application.() -> Unit>,
        private val watchPaths: List<String> = emptyList(),
        override val parentCoroutineContext: CoroutineContext = EmptyCoroutineContext,
        override val rootPath: String = ""
) : ApplicationEngineEnvironment {

    @Suppress("UNUSED")
    @Deprecated("Binary compatibility.", level = DeprecationLevel.HIDDEN)
    constructor(
        classLoader: ClassLoader,
        log: Logger,
        config: ApplicationConfig,
        connectors: List<EngineConnectorConfig>,
        modules: List<Application.() -> Unit>,
        watchPaths: List<String> = emptyList(),
        parentCoroutineContext: CoroutineContext = EmptyCoroutineContext
    ) : this(classLoader, log, config, connectors, modules, watchPaths, parentCoroutineContext, "/")

    private var _applicationInstance: Application? = null
    private var _applicationClassLoader: ClassLoader? = null
    private val applicationInstanceLock = ReentrantReadWriteLock()
    private var packageWatchKeys = emptyList<WatchKey>()

    private val watchPatterns: List<String> =
        (config.propertyOrNull("ktor.deployment.watch")?.getList() ?: listOf()) + watchPaths

    private val moduleFunctionNames: List<String>? = run {
        val configModules = config.propertyOrNull("ktor.application.modules")?.getList()
        if (watchPatterns.isEmpty()) configModules
        else {
            val unlinkedModules = modules.map {
                val fn = (it as? KFunction<*>)?.javaMethod
                    ?: throw RuntimeException("Module function provided as lambda cannot be unlinked for reload")
                val clazz = fn.declaringClass
                val name = fn.name
                "${clazz.name}.$name"
            }
            if (configModules == null)
                unlinkedModules
            else
                configModules + unlinkedModules
        }
    }

    private val watcher by lazy { FileSystems.getDefault().newWatchService() }

    override val monitor = ApplicationEvents()

    @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
    override val application: Application
        get() = currentApplication()

    /**
     * Reload application: destroy it first and then create again
     */
    fun reload() {
        applicationInstanceLock.write {
            destroyApplication()
            val (application, classLoader) = createApplication()
            _applicationInstance = application
            _applicationClassLoader = classLoader
        }
    }

    private fun currentApplication(): Application = applicationInstanceLock.read {
        if (watchPatterns.isNotEmpty()) {
            val changes = packageWatchKeys.flatMap { it.pollEvents() }
            if (changes.isNotEmpty()) {
                log.info("Changes in application detected.")
                var count = changes.size
                while (true) {
                    Thread.sleep(200)
                    val moreChanges = packageWatchKeys.flatMap { it.pollEvents() }
                    if (moreChanges.isEmpty())
                        break
                    log.debug("Waiting for more changes.")
                    count += moreChanges.size
                }

                log.debug("Changes to $count files caused application restart.")
                changes.take(5).forEach { log.debug("...  ${it.context()}") }
                applicationInstanceLock.write {
                    destroyApplication()
                    val (application, classLoader) = createApplication()
                    _applicationInstance = application
                    _applicationClassLoader = classLoader
                }
            }
        }

        _applicationInstance ?: throw IllegalStateException("ApplicationEngineEnvironment was not started")
    }

    private fun createApplication(): Pair<Application, ClassLoader> {
        val classLoader = createClassLoader()
        val currentThread = Thread.currentThread()
        val oldThreadClassLoader = currentThread.contextClassLoader
        currentThread.contextClassLoader = classLoader
        try {
            return instantiateAndConfigureApplication(classLoader) to classLoader
        } finally {
            currentThread.contextClassLoader = oldThreadClassLoader
        }
    }

    private fun createClassLoader(): ClassLoader {
        val baseClassLoader = classLoader
        val watchPatterns = watchPatterns
        if (watchPatterns.isEmpty()) {
            log.info("No ktor.deployment.watch patterns specified, automatic reload is not active")
            return baseClassLoader
        }

        val allUrls = baseClassLoader.allURLs()
        val jre = File(System.getProperty("java.home")).parent
        val debugUrls = allUrls.map { it.file }
        log.debug("Java Home: $jre")
        log.debug("Class Loader: $baseClassLoader: ${debugUrls.filter { !it.toString().startsWith(jre) }}")

        // we shouldn't watch URL for ktor-server-core classes, even if they match patterns,
        // because otherwise it loads two ApplicationEnvironment (and other) types which do not match
        val coreUrls = listOf(
                ApplicationEnvironment::class.java, // ktor-server-core
                ApplicationEngineEnvironment::class.java, // ktor-server-host-common
                Pipeline::class.java, // ktor-parsing
                HttpStatusCode::class.java, // ktor-http
                kotlin.jvm.functions.Function1::class.java, // kotlin-stdlib
                Logger::class.java, // slf4j
                ByteReadChannel::class.java,
                Input::class.java   // kotlinx-io
        ).mapNotNullTo(HashSet()) { it.protectionDomain.codeSource.location }

        val watchUrls = allUrls.filter { url ->
            url !in coreUrls && watchPatterns.any { pattern -> url.toString().contains(pattern) } &&
                !(url.path ?: "").startsWith(jre)
        }

        if (watchUrls.isEmpty()) {
            log.info("No ktor.deployment.watch patterns match classpath entries, automatic reload is not active")
            return baseClassLoader
        }

        watchUrls(watchUrls)
        return OverridingClassLoader(watchUrls, baseClassLoader)
    }

    private fun safeRiseEvent(event: EventDefinition<Application>, application: Application) {
        try {
            monitor.raise(event, application)
        } catch (e: Throwable) {
            log.error("One or more of the handlers thrown an exception", e)
        }
    }

    private fun destroyApplication() {
        val currentApplication = _applicationInstance
        val applicationClassLoader = _applicationClassLoader
        _applicationInstance = null
        _applicationClassLoader = null
        if (currentApplication != null) {
            safeRiseEvent(ApplicationStopping, currentApplication)
            try {
                currentApplication.dispose()
                (applicationClassLoader as? OverridingClassLoader)?.close()
            } catch (e: Throwable) {
                log.error("Failed to destroy application instance.", e)
            }

            safeRiseEvent(ApplicationStopped, currentApplication)
        }
        packageWatchKeys.forEach { it.cancel() }
        packageWatchKeys = mutableListOf()
    }

    private fun watchUrls(urls: List<URL>) {
        val paths = HashSet<Path>()
        for (url in urls) {
            val path = url.path ?: continue
            val decodedPath = URLDecoder.decode(path, "utf-8")
            val folder = File(decodedPath).toPath()

            // TODO: investigate why the path gets trailing slash on Windows
            // java.nio.file.InvalidPathException: Illegal char <:> at index 2: /Z:/buildAgent/work/7cfdbf2437628a0f/ktor-server/ktor-server-host-common/target/test-classes/
            // val folder = Paths.get(URLDecoder.decode(path, "utf-8"))

            if (!Files.exists(folder))
                continue

            val visitor = object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    paths.add(dir)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val dir = file.parent
                    if (dir != null)
                        paths.add(dir)
                    return FileVisitResult.CONTINUE
                }
            }

            if (Files.isDirectory(folder)) {
                Files.walkFileTree(folder, visitor)
            }
        }

        paths.forEach { path ->
            log.debug("Watching $path for changes.")
        }
        val modifiers = get_com_sun_nio_file_SensitivityWatchEventModifier_HIGH()?.let { arrayOf(it) } ?: emptyArray()
        packageWatchKeys = paths.map {
            it.register(watcher, arrayOf(ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY), *modifiers)
        }
    }

    override fun start() {
        applicationInstanceLock.write {
            val (application, classLoader) = try {
                createApplication()
            } catch (t: Throwable) {
                destroyApplication()
                if (watchPatterns.isNotEmpty()) {
                    watcher.close()
                }

                throw t
            }
            _applicationInstance = application
            _applicationClassLoader = classLoader
        }
    }

    override fun stop() {
        applicationInstanceLock.write {
            destroyApplication()
        }
        if (watchPatterns.isNotEmpty()) {
            watcher.close()
        }
    }

    private fun instantiateAndConfigureApplication(classLoader: ClassLoader): Application {
        val application = Application(this)
        safeRiseEvent(ApplicationStarting, application)

        avoidingDoubleStartup {
            moduleFunctionNames?.forEach { fqName ->
                avoidingDoubleStartupFor(fqName) {
                    executeModuleFunction(classLoader, fqName, application)
                }
            }
        }

        if (watchPatterns.isEmpty()) {
            modules.forEach { it(application) }
        }

        safeRiseEvent(ApplicationStarted, application)
        return application
    }

    private fun avoidingDoubleStartup(block: () -> Unit) {
        try {
            block()
        } finally {
            currentStartupModules.get()?.let {
                if (it.isEmpty()) {
                    currentStartupModules.remove()
                }
            }
        }
    }

    private fun avoidingDoubleStartupFor(fqName: String, block: () -> Unit) {
        val modules = currentStartupModules.getOrSet { ArrayList(1) }
        if (modules.contains(fqName)) {
            throw IllegalStateException("Module startup is already in progress for " +
                "function $fqName (recursive module startup from module main?)")
        }
        modules.add(fqName)
        try {
            block()
        } finally {
            modules.remove(fqName)
        }
    }

    private fun executeModuleFunction(classLoader: ClassLoader, fqName: String, application: Application) {
        fqName.lastIndexOfAny(".#".toCharArray()).let { idx ->
            if (idx == -1) return@let
            val className = fqName.substring(0, idx)
            val functionName = fqName.substring(idx + 1)
            val clazz = classLoader.loadClassOrNull(className) ?: return@let

            val staticFunctions = clazz.methods
                    .filter { it.name == functionName && Modifier.isStatic(it.modifiers) }
                    .mapNotNull { it.kotlinFunction }
                    .filter { it.isApplicableFunction() }

            staticFunctions.bestFunction()?.let { moduleFunction ->
                if (moduleFunction.parameters.none { it.kind == KParameter.Kind.INSTANCE }) {
                    callFunctionWithInjection(null, moduleFunction, application)
                    return
                }
            }

            if (Function1::class.java.isAssignableFrom(clazz)) {
                val constructor = clazz.declaredConstructors.single()
                if (constructor.parameterCount != 0) {
                    throw RuntimeException("Module function with captured variables cannot be instantiated '$fqName'")
                }
                constructor.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val function = constructor.newInstance() as Function1<Application, Unit>
                function(application)
                return
            }

            clazz.takeIfNotFacade()?.let { kclass ->
                kclass.functions.filter { it.name == functionName && it.isApplicableFunction() }
                    .bestFunction()?.let { moduleFunction ->
                    val instance = createModuleContainer(kclass, application)
                    callFunctionWithInjection(instance, moduleFunction, application)
                    return
                }
            }
        }

        throw ClassNotFoundException("Module function cannot be found for the fully qualified name '$fqName'")
    }

    private fun createModuleContainer(applicationEntryClass: KClass<*>, application: Application): Any {
        val objectInstance = applicationEntryClass.objectInstance
        if (objectInstance != null) return objectInstance

        val constructors = applicationEntryClass.constructors.filter {
            it.parameters.all { p -> p.isOptional || isApplicationEnvironment(p) || isApplication(p) }
        }

        val constructor = constructors.bestFunction() ?: throw RuntimeException("There are no applicable constructors found in class $applicationEntryClass")
        return callFunctionWithInjection(null, constructor, application)
    }

    private fun <R> List<KFunction<R>>.bestFunction(): KFunction<R>? {
        return sortedWith(
            compareBy(
                { it.parameters.isNotEmpty() && isApplication(it.parameters[0]) },
                { it.parameters.count { !it.isOptional } },
                { it.parameters.size }))
            .lastOrNull()
    }

    private fun <R> callFunctionWithInjection(instance: Any?, entryPoint: KFunction<R>, application: Application): R {
        return entryPoint.callBy(entryPoint.parameters
                .filterNot { it.isOptional }
                .associateBy({ it }, { p ->
                    @Suppress("IMPLICIT_CAST_TO_ANY")
                    when {
                        p.kind == KParameter.Kind.INSTANCE -> instance
                        isApplicationEnvironment(p) -> this
                        isApplication(p) -> application
                        else -> {
                            if (p.type.toString().contains("Application")) {
                                // It is possible that type is okay, but classloader is not
                                val classLoader = (p.type.javaType as? Class<*>)?.classLoader
                                throw IllegalArgumentException("Parameter type ${p.type}:{$classLoader} is not supported." +
                                        "Application is loaded as $ApplicationClassInstance:{${ApplicationClassInstance.classLoader}}")
                            }

                            throw IllegalArgumentException("Parameter type '${p.type}' of parameter '${p.name ?: "<receiver>"}' is not supported")
                        }
                    }
                }))
    }

    private fun ClassLoader.loadClassOrNull(name: String): Class<*>? = try {
        loadClass(name)
    } catch (e: ClassNotFoundException) {
        null
    }

    @Suppress("FunctionName")
    private fun get_com_sun_nio_file_SensitivityWatchEventModifier_HIGH() = try {
        val c = Class.forName("com.sun.nio.file.SensitivityWatchEventModifier")
        val f = c.getField("HIGH")
        f.get(c) as? WatchEvent.Modifier
    } catch (e: Exception) {
        null
    }

    companion object {
        private val currentStartupModules = ThreadLocal<MutableList<String>>()

        private fun isParameterOfType(p: KParameter, type: Class<*>) = (p.type.javaType as? Class<*>)?.let { type.isAssignableFrom(it) } ?: false
        private fun isApplicationEnvironment(p: KParameter) = isParameterOfType(p, ApplicationEnvironmentClassInstance)
        private fun isApplication(p: KParameter) = isParameterOfType(p, ApplicationClassInstance)

        private val ApplicationEnvironmentClassInstance = ApplicationEnvironment::class.java
        private val ApplicationClassInstance = Application::class.java

        private fun KFunction<*>.isApplicableFunction(): Boolean {
            if (isOperator || isInfix || isInline || isAbstract) return false
            if (isSuspend) return false // not supported yet

            extensionReceiverParameter?.let {
                if (!isApplication(it) && !isApplicationEnvironment(it)) return false
            }

            javaMethod?.let {
                if (it.isSynthetic) return false

                // static no-arg function is useless as a module function since no application instance available
                // so nothing could be configured
                if (Modifier.isStatic(it.modifiers) && parameters.isEmpty()) {
                    return false
                }
            }

            return parameters.all { isApplication(it) || isApplicationEnvironment(it)
                || it.kind == KParameter.Kind.INSTANCE || it.isOptional }
        }

        private fun Class<*>.takeIfNotFacade(): KClass<*>? {
            if (getAnnotation(Metadata::class.java)?.takeIf { it.kind == 1 } != null) {
                return kotlin
            }
            return null
        }
    }
}
