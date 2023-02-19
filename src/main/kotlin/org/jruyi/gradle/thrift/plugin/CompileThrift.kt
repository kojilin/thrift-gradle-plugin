package org.jruyi.gradle.thrift.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaPlugin
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
    // To avoid user access Property#set directly, we can't use Property API here.
    // Because setting these 3 fields need to update source folder setting.
    private val generators: MutableMap<String, String> = HashMap()

    private var outputDir: File? = null

    @get:Input
    var createGenFolder: Boolean = true
        set(value) {
            if (field == value)
                return
            val oldJavaOutputDir = currentJavaOutputDir()
            field = value
            addSourceDir(oldJavaOutputDir)
        }

    @get:Incremental
    @get:InputFiles
    abstract val sourceItems: ConfigurableFileCollection

    @get:Incremental
    @get:InputFiles
    abstract val includeDirs: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val thriftExecutable: Property<String>

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

    @Input
    fun getGenerators(): Map<String, String> {
        return generators
    }

    fun includeDir(includeDirInput: Any) {
        val dir = if (includeDirInput !is File) {
            project.file(includeDirInput)
        } else {
            includeDirInput
        }
        includeDirs.from(dir)
    }

    fun generator(gen: String, vararg args: String) {
        val options =
            if (args.isEmpty()) {
                ""
            } else {
                args.joinToString(separator = ",") { it.trim() }
            }

        val oldJavaOutputDir = currentJavaOutputDir()
        this.generators[gen.trim()] = options
        addSourceDir(oldJavaOutputDir)
    }

    @OutputDirectory
    fun getOutputDir(): File? {
        return this.outputDir
    }

    fun outputDir(outputDirInput: Any) {
        val outputDirFile: File = if (outputDirInput is File) {
            outputDirInput
        } else {
            project.file(outputDirInput)
        }
        if (this.outputDir == outputDirFile) {
            return
        }

        val oldOutputDir = currentJavaOutputDir()
        this.outputDir = outputDirFile
        addSourceDir(oldOutputDir)
    }

    private fun addSourceDir(oldJavaOutputDir: File?) {
        if (project.plugins.hasPlugin("java"))
            makeAsDependency(oldJavaOutputDir)
        else {
            project.plugins.whenPluginAdded { plugin ->
                if (plugin is JavaPlugin) {
                    makeAsDependency(oldJavaOutputDir)
                }
            }
        }
    }

    private fun makeAsDependency(oldDir: File?) {
        val compileJava = project.tasks.findByName(JavaPlugin.COMPILE_JAVA_TASK_NAME) ?: return

        val genJava = currentJavaOutputDir()?.canonicalFile
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

        if (genJava != null) {
            sourceSet.java.srcDir(genJava.absoluteFile)
        }

        // For backward compatibility, even we don't has gen generator, we still keep it's task dependency
        compileJava.dependsOn(this)
    }

    private fun currentJavaOutputDir(): File? {
        val outDirFile = outputDir ?: return null

        if (!generators.containsKey("java")) {
            return null
        }

        return if (createGenFolder) {
            File(outDirFile, "gen-java")
        } else {
            outDirFile
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
        val output = outputDir ?: throw GradleException("outputDir must not be null")
        if (!output.exists() && !output.mkdirs()) {
            throw GradleException("Could not create thrift output directory: ${output.absolutePath}")
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
        if (generators.isEmpty()) {
            throw GradleException("Must specify generator")
        }
        val output = outputDir ?: throw GradleException("outputDir must not be null")
        val cmdLine = mutableListOf(
            thriftExecutable.getOrElse("thrift"),
            if (createGenFolder) {
                "-o"
            } else {
                "-out"
            },
            output.absolutePath
        )
        generators.entries.forEach { generator ->
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
        val outputDirFile = outputDir ?: throw GradleException("outputDir must not be null")
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
