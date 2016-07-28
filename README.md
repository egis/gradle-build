# gradle-build

* Support both Groovy and Java 8 
* Automatically updates the JAR/MANIFEST.MF with the GIT SHA1, Commit Date and Build Date


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
gradle dependencies 
````

The advantages of using this mechanism:

* Supports any file file format  in any folder - i.e. libs/lib.txt and test-libs/lib.txt
* Speed - Downloads directly from Amazon S3
* Server Free
