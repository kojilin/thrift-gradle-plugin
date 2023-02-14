package org.jruyi.gradle.thrift.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.ChangeType
import org.gradle.work.Incremental
import org.gradle.work.InputChanges
import java.io.File
import javax.inject.Inject


abstract class CompileThrift : DefaultTask() {

    @get:Incremental
    @get:InputFiles
    abstract val sourceItems: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Incremental
    @get:InputFiles
    abstract val includeDirs: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val thriftExecutable: Property<String>

    @get:Input
    abstract val generators: MapProperty<String, String>

    @get:Input
    @get:Optional
    abstract val createGenFolder: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val recurse: Property<Boolean>

    @get:Internal
    abstract val nowarn: Property<Boolean>

    @get:Internal
    abstract val strict: Property<Boolean>

    @get:Internal
    abstract val verbose: Property<Boolean>

    @get:Internal
    abstract val debug: Property<Boolean>

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val objectFactory: ObjectFactory

    init {
        sourceDir("${project.projectDir}/src/main/thrift")
        outputDir("${project.buildDir}/generated-sources/thrift")
    }

    fun recurse(recurse: Boolean) {
        this.recurse.set(recurse)
    }

    fun nowarn(nowarn: Boolean) {
        this.nowarn.set(nowarn)
    }

    fun strict(strict: Boolean) {
        this.strict.set(strict)
    }

    fun verbose(verbose: Boolean) {
        this.verbose.set(verbose)
    }

    fun debug(debug: Boolean) {
        this.debug.set(debug)
    }

    fun createGenFolder(createGenFolder: Boolean) {
        if (this.createGenFolder.getOrElse(true) == createGenFolder)
            return
        val oldOutputDir = currentOutputDir()
        this.createGenFolder.set(createGenFolder)
        addSourceDir(oldOutputDir)
    }

    fun generator(gen: String, vararg args: String) {
        val options =
            if (args.isEmpty()) {
                ""
            } else {
                args.joinToString(separator = ",") { it.trim() }
            }
        generators.put(gen.trim(), options)
    }

    fun sourceDir(sourceDir: Any) {
        sourceItems(sourceDir)
    }

    fun sourceItems(vararg sourceItems: Any) {
        for (item in sourceItems) {
            this.sourceItems.from(convertToFile(item))
        }
    }

    private fun convertToFile(item: Any): File {
        if (item is File) {
            return item
        }

        val result = File(item.toString())
        if (result.exists()) {
            return result
        }

        return project.file(item)
    }

    fun thriftExecutable(thriftExecutable: String) {
        this.thriftExecutable.set(thriftExecutable)
    }

    fun outputDir(outputDirInput: Any) {
        val outputDirFile: File = if (outputDirInput is File) {
            outputDirInput
        } else {
            project.file(outputDirInput)
        }
        if (this.outputDir.asFile.orNull == outputDirFile)
            return

        val oldOutputDir = currentOutputDir()
        this.outputDir.set(outputDirFile)
        addSourceDir(oldOutputDir)
    }

    fun includeDir(includeDirInput: Any) {
        val dir = if (includeDirInput !is File) {
            project.file(includeDirInput)
        } else {
            includeDirInput
        }
        includeDirs.from(dir)
    }

    private fun addSourceDir(oldOutputDir: File?) {
        if (project.plugins.hasPlugin("java"))
            makeAsDependency(oldOutputDir)
        else {
            project.plugins.whenPluginAdded { plugin ->
                if (plugin is JavaPlugin) {
                    makeAsDependency(oldOutputDir)
                }
            }
        }
    }

    private fun makeAsDependency(oldDir: File?) {
        val compileJava = project.tasks.findByName(JavaPlugin.COMPILE_JAVA_TASK_NAME) ?: return

        generators.put("java", "")
        val genJava = currentOutputDir()?.canonicalFile ?: return
        if (genJava == oldDir)
            return

        val sourceSetContainer = project.extensions.getByType(SourceSetContainer::class.java)
        val sourceSet = sourceSetContainer.getByName(SourceSet.MAIN_SOURCE_SET_NAME)

        if (oldDir != null) {
            val filteredJavaSrcDirs = sourceSet.java.sourceDirectories.filter { file ->
                !file.equals(oldDir)
            }.files
            sourceSet.java.setSrcDirs(filteredJavaSrcDirs)
        }
        sourceSet.java.srcDir(genJava.absoluteFile)

        compileJava.dependsOn(this)
    }

    private fun currentOutputDir(): File? {
        val outDirFile = outputDir.asFile
        if (outDirFile.orNull == null) {
            return null
        }
        return if (createGenFolder.getOrElse(true)) {
            File(outDirFile.get(), "gen-java")
        } else {
            outDirFile.orNull
        }
    }

    @TaskAction
    fun compileThrift(inputChanges: InputChanges) {
        if (!inputChanges.isIncremental) {
            compileAll()
            return
        }

        val fileChanges = inputChanges.getFileChanges(sourceItems)
        if (fileChanges.any { c -> c.changeType == ChangeType.REMOVED }) {
            compileAll()
            return
        }
        val output = outputDir.asFile.get()
        if (!output.exists() && !output.mkdirs()) {
            throw GradleException("Could not create thrift output directory: ${outputDir.asFile.get().absolutePath}")
        }
        fileChanges
            .filter { change -> change.file.name.endsWith("thrift") }
            .map { change -> change.file }
            .forEach { file ->
                logger.info("Item to be generated for: $file")
                compile(file.absolutePath)
            }
    }

    private fun compile(source: String) {
        val cmdLine = mutableListOf(
            thriftExecutable.getOrElse("thrift"),
            if (createGenFolder.getOrElse(true)) {
                "-o"
            } else {
                "-out"
            },
            outputDir.asFile.get().absolutePath
        )
        generators.get().entries.forEach { generator ->
            cmdLine += "--gen"
            var cmd = generator.key.trim()
            val options = generator.value.trim()
            if (options.isNotEmpty()) {
                cmd += ":$options"
            }
            cmdLine += cmd
        }

        includeDirs.forEach { includeDir ->
            cmdLine += "-I"
            cmdLine += includeDir.absolutePath
        }
        if (recurse.getOrElse(false)) {
            cmdLine += "-r"
        }
        if (nowarn.getOrElse(false)) {
            cmdLine += "-nowarn"
        }
        if (strict.getOrElse(false)) {
            cmdLine += "-strict"
        }
        if (verbose.getOrElse(false)) {
            cmdLine += "-v"
        }
        if (debug.getOrElse(false)) {
            cmdLine += "-debug"
        }
        cmdLine += source

        val result = execOperations.exec { execSpec ->
            execSpec.commandLine(cmdLine)
        }

        val exitCode = result.exitValue
        if (exitCode != 0) {
            throw GradleException("Failed to compile ${source}, exit=${exitCode}")
        }
    }

    private fun compileAll() {
        val outputDirFile = outputDir.get().asFile
        if (!outputDirFile.deleteRecursively()) {
            throw GradleException("Could not delete thrift output directory: ${outputDirFile.absolutePath}")
        }

        if (!outputDirFile.mkdirs()) {
            throw GradleException("Could not create thrift output directory: ${outputDirFile.absolutePath}")
        }
        val resolvedSourceItems = mutableSetOf<String>()
        sourceItems.files.forEach { file ->
            if (file.isFile) {
                resolvedSourceItems.add(file.absolutePath)
            } else if (file.isDirectory) {
                objectFactory.fileTree()
                    .from(file.canonicalPath)
                    .matching {
                        it.include("**/*.thrift")
                    }
                    .forEach { it ->
                        resolvedSourceItems.add(it.absolutePath)
                    }
            } else if (!file.exists()) {
                logger.warn("Could not find $file. Will ignore it")
            } else {
                logger.warn("Unable to handle $file. Will ignore it")
            }
        }

        logger.info("Items to be generated for: {}", resolvedSourceItems)

        resolvedSourceItems.forEach {
            compile(it)
        }
    }
}
