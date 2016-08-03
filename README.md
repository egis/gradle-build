# gradle-build

[![CircleCI](https://circleci.com/gh/egis/gradle-build/tree/master.svg?style=svg)](https://circleci.com/gh/egis/gradle-build/tree/master)

A gradle plugin that combines various tasks and conventions for common builds of Egis.

* Support both Groovy and Java 8 
* Automatically updates the JAR/MANIFEST.MF with the GIT SHA1, Commit Date and Build Date

## To use this plugin start with the following starter `build.gradle` file and extend as required:

```groovy
repositories {
    jcenter()
}

buildscript {
    repositories {
        jcenter()
        mavenCentral()
        maven {
            url "https://plugins.gradle.org/m2/"
        }
    }
    dependencies {
        classpath "gradle.plugin.com.egis:gradle-build:1.7"
        classpath 'org.ajoberstar:grgit:1.1.0'
        classpath "jp.classmethod.aws:gradle-aws-plugin:0.+"
    }
}

ext {
    pkg = 'egis-utils'
    libBucket = '...'
    libPrefix = "libs/"
}

apply plugin: 'java'
apply plugin: 'groovy'
apply plugin: "com.egis.gradle"

jar {
    dependsOn apiJar
    dependsOn srcJar
}
```
`pkg` is the name of the package to be build e.g. egis-utils  
`libBucket` is the name of the Amazon S3 bucket to resolve dependencies from  
`libPrefix` is the prefix or folder in the S3 bucket  

#Directory Structure#

```
api/
src/
test/
libs/
  lib.txt
test-libs/
  lib.txt
```
  
`api/` contents are built into standalone outputs to be imported into other modules - cannot depend on any files within `src/`  
`src/` directories contain both Java 8 and Groovy source files  
`test/` directories contain TestNG classes  
`lib.txt` files contain external dependencies  

##Dependency Management

Will search all folders recursively for files named `lib.txt` and download/resolve dependencies included it from Amazon S3 or Maven  

The format of the lib.txt:  
`{Filename} {MD5}` Downloaded from Amazon S3 if the {MD5} does not match  
`{Filename}` Downloaded if the file does not exist  
`{Filename}.txt` Retrieves the contents of the text file and then recursively downloads per each line  

To download all dependencies:  
```shell
gradle setup 
````

The advantages of using this mechanism:

* Supports any file file format  in any folder - i.e. libs/lib.txt and test-libs/lib.txt
* Speed - Downloads directly from Amazon S3
* Server Free
