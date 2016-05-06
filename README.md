# gradle-build

* Support both Groovy and Java 8 
* Automatically updates the JAR/MANIFEST.MF with the GIT SHA1, Commit Date and Build Date


## Directory Structure

```
src/
test/
libs/
test-libs
```
##Dependency Management

Will search all folders recursively for files named "lib.txt" and download dependencies included in it from Amazon S3

The format of the lib.txt:
```
{Filename} {MD5}
```

To download all dependencies:
```shell
gradle dependencies 
````

The advantages of using this mechanism:

* Supports any file file format  in any folder - i.e. libs/lib.txt and test-libs/lib.txt
* Speed - Downloads directly from Amazon S3
* Server Free
