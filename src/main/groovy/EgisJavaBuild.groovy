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

    def DF = "dd MMM yyyy HH:mm:ss"


    public EgisJavaBuild(def project) {
        this.project = project;
    }

    @Inject
    public EgisJavaBuild() {
    }

    void apply(Project project) {
        this.project = project
        def git = Grgit.open(project.file('.'))
        this.revision = git.head().id.substring(0, 8) + " committed on " + git.head().getDate().format(DF) + ", built on " + new Date().format(DF)
        println this.revision
        project.task('publish') << {
            def source = project.fileTree("build/libs/").include(project.ext.pkg + '*.jar')
            def bucketName = project.libBucket;
            def prefix = project.libPrefix ?: "libs/";
            AmazonS3 s3 = new AmazonS3Client(new DefaultAWSCredentialsProviderChain());
            source.visit(new EmptyFileVisitor() {
                public void visitFile(FileVisitDetails element) {
                    String key = prefix + element.getRelativePath();
                    project.getLogger().info(" => s3://{}/{}", bucketName, key);
                    File _md5 = new File(element.getFile().getParentFile(), element.getFile().getName() + ".md5")
                    _md5.text = md5(element.getFile())
                    project.getLogger().info(" => s3://{}/{}", bucketName, key + ".md5");

                    s3.putObject(new PutObjectRequest(bucketName, key, element.getFile()));
                    s3.putObject(new PutObjectRequest(bucketName, key + ".md5", _md5));
                }
            });
        }

        project.task([overwrite: true, dependsOn: "jar"], '_deploy', {
            def source = project.fileTree("build/libs/").include(project.ext.pkg + '*.jar')
            source.visit(new EmptyFileVisitor() {
                public void visitFile(FileVisitDetails element) {
                    File to = new File(System.getenv()['WORK_DIR'] + File.separator + "build", element.getFile().name);
                    project.getLogger().info("Copying ${element.getFile()} to ${to}")
                    element.getFile().renameTo(to)
                }
            });
        });

        if (project.getPluginManager().hasPlugin("groovy"))  {
            project.sourceSets.main.groovy.srcDir +=   'src/'
            project.sourceSets.test.groovy.srcDir +=   'src/'
        }

        project.sourceSets {
            api {
                java {
                    srcDir 'api/'
                }
            }
        }

        project.dependencies {
            apiCompile downloadFromLibTxt("libs")
            testCompile downloadFromLibTxt("test-libs")
            compile this.project.files("${this.project.buildDir}/classes/api")
            compile downloadFromLibTxt("libs")
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

    def downloadFromLibTxt(dir) {
        def _files = [];
        new File(dir).eachFileRecurse({
            if (it.name == 'lib.txt') {
                println "Downloading dependencies for ${it.path}"
                it.eachLine { dep ->

                    def name = dep.split(" ")[0]
                    def _md5;
                    if (dep.split(" ").length == 2) {
                        _md5 = dep.split(" ")[1]
                    }

                    File file = new File(it.parentFile, name);
                    String url = "https://s3.amazonaws.com/${this.project.libBucket}/libs/$name"
                    if (!file.exists()) {
                        download(url, file)

                    } else if (_md5 != null && !_md5.equals(md5(file))) {
                        println "$name corrupted"
                        download(url, file)
                    }
                    _files.add(file)
                }
            }
        })
        return this.project.files(_files);
    }

}