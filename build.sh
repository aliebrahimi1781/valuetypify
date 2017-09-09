#!/bin/bash
[ -z "$JAVA_HOME" ] && export JAVA_HOME=/usr/jdk/jdk-8

export javac=$JAVA_HOME/bin/javac
export jar=$JAVA_HOME/bin/jar

rm -fr target/output/

# main
echo "create valuetypifier jar ..."
mkdir -p target/output/main
$javac -d target/output/main -cp .:deps/asm-debug-all-6.0_MVT.jar -sourcepath src/main/java src/main/java/fr/umlv/valuetypify/*.java src/main/java/jvm/internal/value/*.java

cd target/output/main
$jar xfM ../../../deps/asm-debug-all-6.0_MVT.jar
rm module-info.class
rm META-INF/MANIFEST.MF
rmdir META-INF
cd ../../..

$jar cfm valuetypifier.jar MANIFEST.MF -C target/output/main .


# test
echo "create test jar ..."
mkdir -p target/output/test
$javac -d target/output/test -cp .:valuetypifier.jar -sourcepath src/test/java src/test/java/fr/umlv/valuetypify/test/*.java

$jar cfM test.jar -C target/output/test .

