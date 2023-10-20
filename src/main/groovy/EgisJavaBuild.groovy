import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectRequest
import groovy.json.JsonSlurper
import groovy.xml.MarkupBuilder
import org.ajoberstar.grgit.Grgit
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.EmptyFileVisitor
import org.gradle.api.file.FileVisitDetails
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Zip
import org.gradle.jvm.tasks.Jar

import javax.inject.Inject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.apache.tools.ant.taskdefs.condition.Os

class EgisJavaBuild implements Plugin<Project> {

    def revision
    def project
    def quick
    def buildNo
    def log = new Logger()

    class Logger {
         def info(msg) {
            println msg
        }
        def info(msg, e) {
            println msg + "," + e
        }

    }
    def DF = "dd MMM yyyy HH:mm:ss"

    def ant = new AntBuilder()
    def npmCommand = Os.isFamily(Os.FAMILY_WINDOWS) ? 'npm.cmd' : 'npm'

    public EgisJavaBuild(def project) {
        this.project = project
    }

    @Inject
    public EgisJavaBuild() {
    }

    static boolean empty(String str) {
        return str == null || str.trim().equalsIgnoreCase("null") || str.trim().length() == 0;
    }

    def metadata(int length) {
        ObjectMetadata metadata = new ObjectMetadata()
        metadata.setContentType("plain/text")
        metadata.setContentLength(length)
        return metadata
   }


    def getPkg() {
        def pkg = null

        if (new File('package.json').exists()) {
             pkg = new JsonSlurper().parse(new File('package.json'))
        }
        return pkg
    }

    void apply(Project project) {
        this.quick = "true" == System.getenv()['QUICK']
        this.project = project
        this.buildNo = System.getenv()['CIRCLE_BUILD_NUM']
        this.revision = "debug"
        log.info("Apply b5 to ${revision}")



        try {
            def git = Grgit.open(project.file('.'))
        if (!quick) {
            this.revision = git.head().id.substring(0, 8) + " committed on " + git.head().getDate().format(DF)
            if (buildNo != null) {
                this.revision += "b" + buildNo
            } else {
                this.revision += ", bDEBUG built on " + new Date().format(DF)
            }
        }
        } catch (e) {
            log.info("no git repo found",e)
        }
        def bucketName = project.libBucket
        def prefix = project.libPrefix ?: "libs/"
        project.task([dependsOn: 'jar'], 'publish')  {
           doLast {
                def source = project.fileTree("build/libs/").include('*.jar')
                AmazonS3 s3 = new AmazonS3Client(new DefaultAWSCredentialsProviderChain())
                source.visit(new EmptyFileVisitor() {
                    public void visitFile(FileVisitDetails element) {
                        String base = element.getFile().getName().split("\\.")[0]
                        String key = prefix + element.getRelativePath()
                        String version = prefix + base + "-b" + buildNo + ".jar"
                        project.getLogger().info("2:s3://{}/{}", bucketName, version)
                        def md5 = md5(element.getFile())

                        s3.putObject(new PutObjectRequest(bucketName, version, element.getFile()))
                        s3.putObject(new PutObjectRequest(bucketName, version + ".md5", new ByteArrayInputStream(md5.bytes), metadata(md5.length())))
                        s3.putObject(new PutObjectRequest(bucketName, key, element.getFile()))
                        s3.putObject(new PutObjectRequest(bucketName, key + ".md5", new ByteArrayInputStream(md5.bytes), metadata(md5.length())))
                        s3.putObject(new PutObjectRequest(bucketName, key + ".latest", new ByteArrayInputStream(buildNo.bytes), metadata(buildNo.length())))

                    }
                })
            }
        }

        project.task([dependsOn: 'groovydoc'], 'publishDocs') {
            doLast{
                AmazonS3 s3 = new AmazonS3Client(new DefaultAWSCredentialsProviderChain())
                String docs = prefix + project.ext.pkg + "/"
                project.fileTree("build/docs/groovydoc").visit(new EmptyFileVisitor() {
                    public void visitFile(FileVisitDetails element) {
                        project.getLogger().info(" => s3://{}/{}", bucketName, docs + element.getRelativePath())
                        s3.putObject(new PutObjectRequest(bucketName, docs + element.getRelativePath(), element.getFile()))
                    }
                })

            }

        }
        if (!quick) {

            project.task([dependsOn: "jar"], '_deploy', {
                def source = project.fileTree("build/libs/").include(project.ext.pkg + '*.jar')
                log.info(source.toString())
                source.visit(new EmptyFileVisitor() {
                    public void visitFile(FileVisitDetails element) {
                        File to = new File(System.getenv()['WORK_DIR'] + File.separator + "build", element.getFile().name)
                        project.getLogger().info("Copying ${element.getFile()} to ${to}")
                        element.getFile().renameTo(to)
                    }
                })
            })
        }

        if (project.getPluginManager().hasPlugin("groovy")) {
            project.sourceSets.main.groovy.srcDirs += 'src/'
            project.sourceSets.test.groovy.srcDirs += 'test/'
        }

        project.sourceSets {
            api {
                java {
                    srcDir 'api/'
                }
            }
        }

        project.compileJava.options.incremental = true
        project.jar.archiveBaseName = project.ext.pkg
        project.compileGroovy.options.incremental = true

        project.apply([plugin: 'idea'])

        project.idea.module {
            sourceDirs += project.file('src/groovy')
            excludeDirs += project.file('node_modules/')
            excludeDirs += project.file('tmp/')
            excludeDirs += project.file('dist/')
            excludeDirs += project.file('.gradle')
            excludeDirs += project.file('build')
            outputDir = project.file('build/classes')
        }

        project.idea.project {
            vcs = 'Git'
        }

        project.task("setup") {
            doLast{
                if (getPkg() != null) {
                    project.exec {
                        executable this.npmCommand
                        args "run", "setup"
                    }
                }

                downloadFromLibTxt("libs")
                downloadFromLibTxt("test-libs")
            }
        }

        project.task("npm") {
            doLast{
                project.exec {
                    executable this.npmCommand
                    args "run", "build"
                }
            }
        }

        project.task("ant") {
            doLast{
                print  project.sourceSets.main.output.classesDir
                new File("build.xml").write(ant_file())
            }
        }

        def resources = {  it,  dir ->
            it.from ("resources/") {
                it.include "**/*"
                it.exclude "**/.keep"
            }

            it.from ("build/resources/") {
                it.include "**/*"
                it.exclude "**/.keep"
            }

            def pkg = getPkg()
            if (pkg != null) {
                it.from('build') {
                    it.into("System/plugins/${pkg.plugin}")
                    it.include("${pkg.mainFile}.js")
                }
            }


                def excludes = []
                def tmp = "build/tmp2"

                new File(tmp).mkdirs()
                new File(dir).listFiles().each { f ->
                    if (f.name.endsWith(".zip") && f.directory) {
                        excludes += f.name
                        collapseZip(f, tmp)
                    }
                }
                it.from(tmp) {
                    it.include "*.zip"
                    it.into("PT-SCRIPTS")
                }
                it.from (dir) {
                    it.include "**/*"
                    it.exclude excludes
                    it.into("PT-SCRIPTS")
                    it.exclude "**/.keep"
                }


            it.from('build/libs/' ) {
                it.include "*.jar"
                it.into("System/jars/")
            }
        }

        project.task([type: Zip], 'resources') {
            getArchiveFileName().set  project.ext.pkg + "-resources.zip"
            from("resources/") {
                include "**/*"
            }
        }

        project.task([type: Zip, dependsOn: ['forms','jar','npm']],'upgrade')  {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            String ciBuildNumber = System.getenv('CIRCLE_BUILD_NUM')
            if(!empty(ciBuildNumber)){
                ciBuildNumber = '-' + ciBuildNumber
            }
            String filename = project.ext.pkg +(ciBuildNumber?: '') + "-upgrade.zip"
            getArchiveFileName().set(filename)
            resources(it, 'forms')
            resources(it, 'upgrade')
        }

        project.task('forms') {
            String temp = 'forms'
            log.info('Clearing Directory: ', temp)
            new File(temp).deleteDir()

            new File(temp).mkdirs()

            new File('upgrade').listFiles().each { f ->
                if (f.name.endsWith(".zip") && f.directory) {
                    log.info('Creating form: ', f.name)
                    collapseZip(f, temp)
                }
            }

        }

        project.tasks.jar {
            dependsOn "classes"
            mustRunAfter "npm"
        }


        project.task([type: Zip, dependsOn: ['jar','upgrade'] ], 'install' ) {
            outputs.upToDateWhen { false }
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            String ciBuildNumber = System.getenv('CIRCLE_BUILD_NUM')
            
            if(!empty(ciBuildNumber)){
                ciBuildNumber = '-' + ciBuildNumber
            }

            String filename = project.ext.pkg + (ciBuildNumber?: '') + "-install.zip"
            getArchiveFileName().set(filename)

            resources(it, 'upgrade')
            resources(it, 'install')
        }

        project.dependencies {
            apiImplementation project.fileTree(dir: 'libs')
            testImplementation project.fileTree(dir: 'test-libs')
            implementation this.project.files("${this.project.buildDir}/classes/api")
            implementation project.fileTree(dir: 'libs')
        }

        project.task([type: Jar], 'apiJar', {
            getArchiveFileName().set ( "${this.project.ext.pkg}-api.jar")
            manifest {
                attributes("Git-Version": this.revision)
            }
            from(project.sourceSets.api.output) {
                include "com/egis/**"
            }
        })

        project.task([type: Jar], 'srcJar', {

            getArchiveFileName().set  ( "${this.project.ext.pkg}.jar")
            manifest {
                attributes("Git-Version": this.revision)
            }

            from(project.sourceSets.main.output) {
                include "com/egis/**"
            }

            from("src/") {
                include "com/egis/**"
                exclude "**/*.java"
                exclude "**/*.groovy"
            }
        })


    }

    def collapseZip(File f, String tmp) {
        if (new File (f, f.name.replaceAll(".zip", ".xml")).exists()){
            //workflow have an .xml with the same name as the zip
            def out  = new FileOutputStream(new File(tmp, f.name))
            ZipOutputStream zipFile = new ZipOutputStream(out)
            f.listFiles().each { File child ->
                zipFile.putNextEntry(new ZipEntry(child.name))
                child.withInputStream { i ->
                    zipFile << i
                }
                zipFile.closeEntry()
            }
            zipFile.finish()
            return
        }
        //else it's a form

        def out  = new FileOutputStream(new File(tmp, f.name))
        ZipOutputStream zipFile = new ZipOutputStream(out)
        f.listFiles().each { File child ->
            def sha  = sha1(child)
            zipFile.putNextEntry(new ZipEntry(sha))
            child.withInputStream { i ->
                zipFile << i
            }
            zipFile.closeEntry()
        }

        StringWriter writer = new StringWriter()
        def xml = new MarkupBuilder(writer)
        xml.document {
            status("Filed")
            createdBy(name:"Administrator")
            createdDate(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()))
            visibilityId("0")

            filename(f.getName().substring(0, f.name.length() - ".zip".length()))
            node {
                path ("System/forms")
                name("forms")
            }
            files {
                f.listFiles().each { File child ->
                    file {
                        path(child.name)
                        checksum(sha1(child))
                        length(child.length())
                    }
                }
            }
        }
        zipFile.putNextEntry(new ZipEntry("data.xml"))
        zipFile << new ByteArrayInputStream(writer.toString().bytes)
        zipFile.closeEntry()
        zipFile.finish()
    }

    def unzip(File file) {
        File dir = file.getParentFile()

        ZipInputStream zis = null
        try {
            zis = new ZipInputStream(new FileInputStream(file))
            ZipEntry entry
            while ((entry = zis.getNextEntry()) != null) {
                FileOutputStream out = null

                try {
                    out = new FileOutputStream(new File(dir, entry.getName()))
                    out << zis
                } catch(Exception e){

                }
                finally {
                    if(out  !=null){
                        out.close()
                    }
                }

            }

        } catch (IOException e) {
            throw new RuntimeException(e)
        } finally {
            if(zis !=null){
                zis.close()
            }
        }
    }

    def download(String url, File file) {
        println "Downloading $file.name"
        new URL(url).openConnection().with { conn ->
            file.withOutputStream { out ->
                conn.inputStream.with { inp ->
                    out << inp
                    inp.close()
                }
            }
        }

        if (file.name.endsWith(".zip")) {
            println "Unzipping $file.name"
            unzip(file)
        }
    }

    def getUrl(String url) {
        println "Retrieving $url"
        def out = new ByteArrayOutputStream()
        new URL(url).openConnection().with { conn ->
                conn.inputStream.with { inp ->
                    out << inp
                    inp.close()
                }
        }
        return new String(out.toByteArray())
    }

    def md5(File file) {
        return checksum("MD5", file)
    }

    String sha1(File file) {
        return checksum("SHA1", file)
    }

    String checksum(String format, File file) {
        if (!file.exists()) {
            return "missing"

        }
        char[] HEX = "0123456789abcdef".toCharArray()
        def BUFFER_SIZE = 1024
        file.withInputStream { input ->


            MessageDigest digest = MessageDigest.getInstance(format)
            byte[] b = new byte[BUFFER_SIZE]

            for (int n = input.read(b); n != -1; n = input.read(b)) {
                digest.update(b, 0, n)
            }

            byte[] identifier = digest.digest()
            char[] buffer = new char[identifier.length * 2]

            for (int i = 0; i < identifier.length; ++i) {
                buffer[2 * i] = HEX[identifier[i] >> 4 & 15]
                buffer[2 * i + 1] = HEX[identifier[i] & 15]
            }

            return new String(buffer)
        }
    }

    def getLatest(String dep) {
        try {
            String url = "https://s3.amazonaws.com/${this.project.libBucket}/libs/${dep}.latest"
            return getUrl(url)
            } catch (e) {
                return null
            }
    }

    def getMD5(String dep) {
         try {
            String url = "https://s3.amazonaws.com/${this.project.libBucket}/libs/${dep}.md5"
            return getUrl(url)
         } catch (e) {
             return null
         }
    }

    def downloadDependencies(def lines, root, group = null) {
        def _files = []
        println "Downloading dependencies for ${root.absolutePath} ${group?:''}"
        lines.each { dep ->
            if (dep.startsWith("#")) {
                return
            }

            dep = dep.split("\r").join("")
            def name = dep.split(" ")[0]
            String url = "https://s3.amazonaws.com/${this.project.libBucket}/libs/$name"

            def _md5
            if (dep.split(" ").length == 2) {
                _md5 = dep.split(" ")[1]
            }

            if (name.endsWith(".txt")) {
                this.project.buildDir.mkdir()
                File child = new File(this.project.buildDir, name)
                def resourceFile = getClass().getResource("/files/" + name)

                // if txt file exists in resource use it
                // otherwise download the file from s3
                if (resourceFile != null) {
                    StringBuffer sb = new StringBuffer()

                    BufferedReader br = new BufferedReader(new InputStreamReader(getClass().getResourceAsStream("/files/" + name), "UTF-8"))
                    for (int c = br.read(); c != -1; c = br.read()) sb.append((char)c)
                    def content = sb.toString()
                    PrintStream out = new PrintStream(new FileOutputStream(child.path))
                    out.print(content)
                    out.close()

                } else {
                    download(url, child)
                }

                _files.addAll(downloadDependencies(child.text.split("\n"), root, name))
                return
            }

            File file = new File(root.parentFile, name)
            if (!file.exists()) {
                download(url, file)

            } else if (_md5 != null && !_md5.equals(md5(file))) {
                println "$name corrupted, expected $_md5 got ${md5(file)}"
                download(url, file)
            }
            _files.add(file)
        }
        return _files
    }


    def freeze(dir) {
      def _files = []
        new File(dir).eachFileRecurse({
            if (it.name == 'lib.txt') {
                for (String dep : it.text.split("\n"))  {
                    def version = getLatest(dep.split(" ")[0])
                    if (version != null) {
                        def latest = dep.replace(".jar", "") + "-b${version}.jar " + getMD5(dep)
                        it.write(it.text.replace(dep,latest))
                        println "${it.name}: $dep -> $latest"
                    }
                }
            }
        })
        return this.project.files(_files)
    }

    def downloadFromLibTxt(dir) {
        def _files = []
        new File(dir).eachFileRecurse({
            if (it.name == 'lib.txt') {
                _files.addAll(downloadDependencies(it.text.split("\n"),it))
            }
        })
        return this.project.files(_files)
    }

    def ant_file() {
        String target = "1.8"
        String xml =
                """
<project name="build.base" basedir="." default="compile">

    <path id="build.classpath">
        <fileset dir="libs">
            <include name="*.jar"/>
        </fileset>

    </path>

    <path id="test.classpath">
        <fileset dir="libs">
            <include name="*.jar"/>
        </fileset>
        <fileset dir="test-libs">
            <include name="*.jar"/>
        </fileset>

         <dirset dir="${project.sourceSets.main.output.classesDir}" erroronmissingdir="false">
            <include name="."/>
        </dirset>

         <dirset dir="${project.sourceSets.api.output.classesDir}" erroronmissingdir="false">
            <include name="."/>
        </dirset>
    </path>

    <taskdef name="groovyc"
             classname="org.codehaus.groovy.ant.Groovyc"
             classpathref="build.classpath"/>

    <taskdef name="groovy"
             classname="org.codehaus.groovy.ant.Groovy"
             classpathref="build.classpath"/>

    <macrodef name="compile">
        <attribute name="src"/>
        <attribute name="dest"/>
        <attribute name="classpath"/>

        <sequential>
            <groovyc srcdir="@{src}" destdir="@{dest}" fork="true" indy="true">
                <classpath>
                    <path refid="@{classpath}"/>
                </classpath>
                <javac debug="on" target="${target}" source="${target}"/>
            </groovyc>
        </sequential>
    </macrodef>

    <target name="compile.test" depends="compile">
        <mkdir dir="test"/>
        <compile src="test" dest="bin" classpath="test.classpath"/>
        <copy todir="bin" overwrite="true">
            <fileset dir="test">
                <include name="**\\*"/>
                <exclude name="**\\*.java"/>
            </fileset>
        </copy>
    </target>

    <target name="compile">
        <mkdir dir="api"/>
        <mkdir dir="src"/>
        <mkdir dir="${project.sourceSets.main.output.classesDir}"/>
        <compile src="api" dest="${project.sourceSets.main.output.classesDir}" classpath="build.classpath"/>
        <compile src="src" dest="${project.sourceSets.main.output.classesDir}" classpath="build.classpath"/>
    </target>

</project>

"""
return xml

    }

}