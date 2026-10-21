package com.feedback.architecture

import com.feedback.common.exception.AbstractException
import com.feedback.config.GlobalExceptionHandler
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.asm.ClassReader
import org.springframework.asm.ClassVisitor
import org.springframework.asm.MethodVisitor
import org.springframework.asm.Opcodes
import org.springframework.web.bind.annotation.ExceptionHandler
import java.nio.file.Files
import java.nio.file.Path

/** 异常边界的编译产物门禁；公开响应由 HTTP/单元行为测试覆盖。 */
class ExceptionBoundaryArchitectureTest {
    private val projectDir = Path.of(checkNotNull(System.getProperty("feedback.backend.project-dir")))
    private val productionClasses by lazy { productionClasses(projectDir) }

    @Test
    fun `controllers delegate exception mapping to the global advice`() {
        val violations = productionClasses
            .filter { it.name.startsWith("com.feedback.controller.") }
            .flatMap { type ->
                type.declaredMethods
                    .filter { it.isAnnotationPresent(ExceptionHandler::class.java) }
                    .map { method -> "${type.name}.${method.name}" }
            }

        assertTrue(violations.isEmpty(), "Controller-local exception handlers: ${violations.sorted()}")
    }

    @Test
    fun `migrated feedback boundaries declare only unified project exceptions`() {
        val violations = productionClasses
            .filter { type -> isMigratedClass(type.name.replace('.', '/')) }
            .filter { Throwable::class.java.isAssignableFrom(it) }
            .filterNot { AbstractException::class.java.isAssignableFrom(it) }
            .map(Class<*>::getName)

        assertTrue(violations.isEmpty(), "Feedback exceptions outside AbstractException: ${violations.sorted()}")
    }

    @Test
    fun `migrated feedback methods do not construct legacy exceptions or Kotlin guard failures`() {
        val violations = productionClassFiles(projectDir)
            .filter { (className, _) -> isMigratedClass(className) }
            .flatMap { (className, path) -> legacyExceptionConstructions(Files.readAllBytes(path), className) }

        assertTrue(violations.isEmpty(), "Legacy exception construction in migrated boundaries:\n${violations.sorted().joinToString("\n")}")
    }

    @Test
    fun `bytecode gate detects direct legacy exceptions and inline Kotlin guards`() {
        val resource = checkNotNull(
            LegacyExceptionBytecodeFixture::class.java.getResourceAsStream("LegacyExceptionBytecodeFixture.class")
        )
        val violations = resource.use { legacyExceptionConstructions(it.readAllBytes(), "fixture") }

        assertEquals(5, violations.size)
        assertTrue(violations.any { "IllegalArgumentException" in it })
        assertTrue(violations.any { "IllegalStateException" in it })
        assertTrue(violations.any { "AccessDeniedException" in it })
    }

    @Test
    fun `compiled production names use current business terminology`() {
        val violations = productionClasses.flatMap { type ->
            buildList {
                if (type.name.contains(LEGACY_TERM, ignoreCase = true)) add(type.name)
                type.declaredMethods
                    .filter { it.name.contains(LEGACY_TERM, ignoreCase = true) }
                    .forEach { add("${type.name}.${it.name}") }
                type.declaredFields
                    .filter { it.name.contains(LEGACY_TERM, ignoreCase = true) }
                    .forEach { add("${type.name}.${it.name}") }
            }
        }

        assertTrue(violations.isEmpty(), "Legacy production names: ${violations.sorted()}")
    }

    @Test
    fun `global exception advice exposes its metrics dependency through the constructor`() {
        val constructor = GlobalExceptionHandler::class.java.declaredConstructors.single { !it.isSynthetic }
        val injectedFields = GlobalExceptionHandler::class.java.declaredFields
            .filter { it.isAnnotationPresent(Autowired::class.java) }

        assertEquals(listOf(MeterRegistry::class.java), constructor.parameterTypes.toList())
        assertTrue(injectedFields.isEmpty(), "Field-injected dependencies: ${injectedFields.map { it.name }}")
    }

    companion object {
        const val LEGACY_TERM = "phaseone"

        val MIGRATED_PACKAGE_PREFIXES = listOf(
            "com/feedback/repository/feedback/",
            "com/feedback/repository/problem/",
            "com/feedback/repository/applicationidentity/",
            "com/feedback/repository/attachment/",
            "com/feedback/repository/cluster/",
            "com/feedback/repository/station/online/",
            "com/feedback/repository/sdk/",
            "com/feedback/controller/query/",
            "com/feedback/controller/sdk/",
            "com/feedback/service/query/",
            "com/feedback/service/attachment/",
            "com/feedback/service/applicationidentity/",
            "com/feedback/service/station/",
            "com/feedback/service/sdk/",
            "com/feedback/service/cluster/"
        )
        val MIGRATED_SECURITY_CLASSES = setOf(
            "com/feedback/security/CurrentWebPrincipalProvider",
            "com/feedback/security/FeedbackAccessGuard",
            "com/feedback/security/PrincipalAccess",
            "com/feedback/security/station/StationGatewayAuthenticationFilter"
        )
    }
}

private fun isMigratedClass(className: String): Boolean =
    ExceptionBoundaryArchitectureTest.MIGRATED_PACKAGE_PREFIXES.any(className::startsWith) ||
        ExceptionBoundaryArchitectureTest.MIGRATED_SECURITY_CLASSES.any {
            className == it || className.startsWith("${it}\$")
        }

private val forbiddenExceptionOwners = setOf(
    "java/lang/IllegalArgumentException",
    "java/lang/IllegalStateException",
    "java/util/NoSuchElementException",
    "org/springframework/security/access/AccessDeniedException",
    "org/springframework/web/server/ResponseStatusException"
)

private fun legacyExceptionConstructions(bytecode: ByteArray, fallbackClassName: String): List<String> {
    val violations = mutableListOf<String>()
    ClassReader(bytecode).accept(object : ClassVisitor(Opcodes.ASM9) {
        private var className = fallbackClassName

        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
            className = name
        }

        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor =
            object : MethodVisitor(Opcodes.ASM9) {
                override fun visitMethodInsn(opcode: Int, owner: String, methodName: String, descriptor: String, isInterface: Boolean) {
                    if (methodName == "<init>" && owner in forbiddenExceptionOwners) {
                        violations += "${className.replace('/', '.')}.$name -> ${owner.replace('/', '.')}"
                    }
                }
            }
    }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    return violations.distinct()
}

private class LegacyExceptionBytecodeFixture {
    fun requireFailure(value: Boolean) = require(value)
    fun requireNotNullFailure(value: String?) = requireNotNull(value)
    fun checkFailure(value: Boolean) = check(value)
    fun errorFailure(): Nothing = error("failure")
    fun accessDenied(): Nothing = throw org.springframework.security.access.AccessDeniedException("failure")
}

private fun productionClassFiles(projectDir: Path): List<Pair<String, Path>> {
    val roots = listOf(
        projectDir.resolve("build/classes/kotlin/main"),
        projectDir.resolve("build/classes/java/main")
    ).filter(Files::isDirectory)
    check(roots.isNotEmpty()) { "Compile production classes before running architecture gates" }

    return roots.flatMap { root ->
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }
                .map { path ->
                    root.relativize(path).toString().removeSuffix(".class").replace('\\', '/') to path
                }
                .filter { (name, _) -> name.startsWith("com/feedback/") }
                .toList()
        }
    }
}

private fun productionClasses(projectDir: Path): Set<Class<*>> {
    return productionClassFiles(projectDir)
        .map { (name, _) -> name.replace('/', '.') }
        .filter { '$' !in it }
        .map { Class.forName(it, false, Thread.currentThread().contextClassLoader) }
        .toSet()
}
