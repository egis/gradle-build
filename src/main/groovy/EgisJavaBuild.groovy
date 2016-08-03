import org.gradle.api.*;
import org.gradle.api.file.*;
import org.gradle.api.plugins.*;
import jp.classmethod.aws.gradle.s3.*;
import com.amazonaws.services.s3.*;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.auth.*;
import java.io.*;
import java.util.zip.*;
import java.security.MessageDigest;
import org.ajoberstar.grgit.Grgit;
import org.gradle.jvm.tasks.Jar;
import javax.inject.Inject;
import org.gradle.api.tasks.*;


class EgisJavaBuild implements Plugin<Project> {

    def revision;
    def project;
    def quick;
    def buildNo;

    def DF = "dd MMM yyyy HH:mm:ss"


    public EgisJavaBuild(def project) {
        this.project = project;
    }

    @Inject
    public EgisJavaBuild() {
    }

    def metadata(int length) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType("plain/text")
        metadata.setContentLength(length)
        return metadata
    }

    void apply(Project project) {
        this.quick = "true" == System.getenv()['QUICK']
        this.project = project
        this.buildNo = System.getenv()['CIRCLE_BUILD_NUM'];
        def git = Grgit.open(project.file('.'))
        this.revision = "debug";

        if (!quick) {
            this.revision = git.head().id.substring(0, 8) + " committed on " + git.head().getDate().format(DF);
            if (buildNo != null) {
                this.revision += "b" + buildNo;
            } else {
                this.revision += ", bDEBUG built on " + new Date().format(DF);
            }
        }

        println this.revision
        def bucketName = project.libBucket;
        def prefix = project.libPrefix ?: "libs/";
        project.task([dependsOn: 'jar'],'publish') << {
            def source = project.fileTree("build/libs/").include(project.ext.pkg + '*.jar')
            AmazonS3 s3 = new AmazonS3Client(new DefaultAWSCredentialsProviderChain());
            source.visit(new EmptyFileVisitor() {
                public void visitFile(FileVisitDetails element) {
                    String base = element.getFile().getName().split("\\.")[0]
                    String key = prefix + element.getRelativePath();
                    String version = prefix + base + "-b" + buildNo + ".jar"
                    project.getLogger().info("2:s3://{}/{}", bucketName, version);
                    def md5 =  md5(element.getFile());

                    s3.putObject(new PutObjectRequest(bucketName, version, element.getFile()));
                    s3.putObject(new PutObjectRequest(bucketName, version + ".md5",  new ByteArrayInputStream(md5.bytes), metadata(md5.length())));
                    s3.putObject(new PutObjectRequest(bucketName, key, element.getFile()));
                    s3.putObject(new PutObjectRequest(bucketName, key + ".md5",  new ByteArrayInputStream(md5.bytes), metadata(md5.length()) ));
                    s3.putObject(new PutObjectRequest(bucketName, key + ".latest",  new ByteArrayInputStream(buildNo.bytes), metadata(buildNo.length())));

                }
            });
        }

         project.task([dependsOn: 'groovydoc'],'publishDocs') << {
            AmazonS3 s3 = new AmazonS3Client(new DefaultAWSCredentialsProviderChain());
            String docs = prefix +  project.ext.pkg + "/"
            project.fileTree("build/docs/groovydoc").visit(new EmptyFileVisitor() {
                public void visitFile(FileVisitDetails element) {
                String base = element.getFile().getName().split("\\.")[0]
                    project.getLogger().info(" => s3://{}/{}", bucketName, docs + element.getRelativePath());
                    s3.putObject(new PutObjectRequest(bucketName,  docs + element.getRelativePath(), element.getFile()));
                }
            })

        }

        if (!quick) {
            project.task([overwrite: true, dependsOn: "jar"], '_deploy', {
                def source = project.fileTree("build/libs/").include(project.ext.pkg + '*.jar')
                project.getLogger().info(source.toString())
                source.visit(new EmptyFileVisitor() {
                    public void visitFile(FileVisitDetails element) {
                        File to = new File(System.getenv()['WORK_DIR'] + File.separator + "build", element.getFile().name);
                        project.getLogger().info("Copying ${element.getFile()} to ${to}")
                        element.getFile().renameTo(to)
                    }
                });
            });
        }

        if (project.getPluginManager().hasPlugin("groovy"))  {
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

        project.task("setup") << {
            downloadFromLibTxt("libs")
            downloadFromLibTxt("test-libs")
        }

        project.dependencies {
            apiCompile project.fileTree(dir:'libs');
            testCompile project.fileTree(dir:'test-libs')
            compile this.project.files("${this.project.buildDir}/classes/api")
            compile project.fileTree(dir:'libs')
        }

        project.task([type: Jar], 'apiJar', {
            archiveName = "${this.project.ext.pkg}-api.jar"
            manifest {
                attributes("Git-Version": this.revision)
            }
            from(project.sourceSets.api.output) {
                include "com/egis/**"
            }
        })

        project.task([type: Jar], 'srcJar', {

            archiveName = "${this.project.ext.pkg}.jar"
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


    def unzip(File file) {
        File dir = file.getParentFile();
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new FileInputStream(file));
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                FileOutputStream out = null;
                try {
                    out = new FileOutputStream(new File(dir, entry.getName()));
                    out << zis
                } finally {
                    out.close()
                }

            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            zis.close()
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
        return new String(out.toByteArray());
    }

    def md5(File file) {
        if (!file.exists()) {
            return "missing"

        }
        char[] HEX = "0123456789abcdef".toCharArray();
        def BUFFER_SIZE = 1024;
        file.withInputStream { input ->


            MessageDigest digest = MessageDigest.getInstance("MD5")
            byte[] b = new byte[BUFFER_SIZE];

            for (int n = input.read(b); n != -1; n = input.read(b)) {
                digest.update(b, 0, n);
            }

            byte[] identifier = digest.digest();
            char[] buffer = new char[identifier.length * 2];

            for (int i = 0; i < identifier.length; ++i) {
                buffer[2 * i] = HEX[identifier[i] >> 4 & 15];
                buffer[2 * i + 1] = HEX[identifier[i] & 15];
            }

            return new String(buffer);
        }
    }

    def downloadDependencies(def lines, root, group = null) {
        def _files = [];
        println "Downloading dependencies for ${root.absolutePath} ${group?:''}"
        lines.each { dep ->
            if (dep.startsWith("#")) {
                return;
            }
            def name = dep.split(" ")[0];
            String url = "https://s3.amazonaws.com/${this.project.libBucket}/libs/$name"

            def _md5;
            if (dep.split(" ").length == 2) {
                _md5 = dep.split(" ")[1]
            }

            if (name.endsWith(".txt")) {
                this.project.buildDir.mkdir()
                File child = new File(this.project.buildDir, name);
                download(url, child)
                _files.addAll(downloadDependencies(child.text.split("\n"), root, name))
                return;
            }

            File file = new File(root.parentFile, name);
            if (!file.exists()) {
                download(url, file)

            } else if (_md5 != null && !_md5.equals(md5(file))) {
                println "$name corrupted, expected $_md5 got ${md5(file)}"
                download(url, file)
            }
            _files.add(file)
        }
        return _files;
    }

    def downloadFromLibTxt(dir) {
        def _files = [];
        new File(dir).eachFileRecurse({
            if (it.name == 'lib.txt') {
                _files.addAll(downloadDependencies(it.text.split("\n"),it));
            }
        })
        return this.project.files(_files);
    }

}