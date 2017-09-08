# valuetypifier
a tool that transforms your Java 8 bytecode to bleeding edge Java 9.1 bytecode that uses the new valuetype related opcodes.

## how it works ?
The valuetypifier is a tools that rewrite all usage of the value capable class (the class annoated with the annotation @jvm.internal.value.ValueCapableClass) to the corresponding value type.

In more details,
- in order to preserve the semantics, all public/protected methods are not changed by the valuetypifier,
  so if the jars contain all the classes of a package (no split package), the generated should work as a dropin replacement.
- all methods parameters that are a value capable are unboxed to be represented as a value type inside the method, all arguments of a method call are boxed before the method is called. 
- all arrays of a value capable class are converted to an array of value types

and what about testing if a reference of a value capable class is null, using a VCC in a synchronized, doing an identity check, etc. in that case the valuetypifier box the value capable class and your code will certainly not behave like you want so don't do that. 

## how to build ?
run
```
sh build.sh
```

## why there is a jar (asm-debug-all-6.0_MVT.jar) in the source ?
It's a version 6.0 of ASM patched to read/write the new opcodes defined by the Minimal Value Type project.
The code is available in the branch [MINIMAL_VALUE_TYPE](https://gitlab.ow2.org/asm/asm/tree/MINIMAL_VALUE_TYPE) of ASM.

## what is the Minimal Value Type project ?
see [https://wiki.openjdk.java.net/display/valhalla/Minimal+Value+Types](https://wiki.openjdk.java.net/display/valhalla/Minimal+Value+Types)

## how to use the valuetypifier ?
run
```
java -jar valuetypifier.jar your.jar
```

it will create a new jar with the name with the suffix 'valuetypified', on the example the resulting jar will be 'your-valuetypified.jar'.

## but why ?
several reasons:
- it allow me to easily test the implementation in ASM (with the limitation that the valuetypifier does not emit the opcodes VDEFAULT and VWITHFIELD)
- it allow me to test the Java VM of the MVT project so i'm more effective in my job of expert of the valhalla project
- it's just fun, i used to write backports, the valuetypifier is better, it's a 'forwardport' !

## can i use the valuetypifier in production ?
yes, if you want to change of job.

## do you have preliminary results ?
there is currently one tets that defines a value capable class named [Color](https://github.com/forax/valuetypify/blob/master/src/test/java/fr/umlv/valuetypify/test/Color.java) and a class ColorList that acts a list of Color.
The test in the [main() of ColorList](https://github.com/forax/valuetypify/blob/master/src/test/java/fr/umlv/valuetypify/test/ColorList.java#L44) creates a list of 50 millions of Color and compute the average of the colors.

On my laptop (i7-5600U @ 2.6Gz x 4), using the test.jar, so with no value types, it's not that fast 
```
time mvt/valhalla/build/linux-x86_64-normal-server-release/jdk/bin/java \
     -cp test.jar fr.umlv.valuetypify.test.ColorList
Color(2.2517998E7, 2.2517998E7, 2.2517998E7)
creation time 2886.933223 ms
average time 606.217409 ms

real	0m4.839s
user	0m11.101s
sys	0m1.306s
```
This test use the VM patched for the MVT (with the MVT disable), i've results in the same ballpark with the jdk9 VM.

using the test-valuetypified.jar produced by the valuetypifier, it's faster.
```
$ time mvt/valhalla/build/linux-x86_64-normal-server-release/jdk/bin/java -XX:+EnableMVT \
       -cp test-valuetypified.jar fr.umlv.valuetypify.test.ColorList
Color(2.2517998E7, 2.2517998E7, 2.2517998E7)
creation time 1039.692931 ms
average time 70.152561 ms

real	0m1.582s
user	0m1.245s
sys	0m0.766s

``` 

If i test with less than 1 million of colors, the timings are the same (modulo the error margin), i suppose it's because the JIT doesn't have the time to kick in.
If i test with more than 50 millions of colors, the test that uses the value type fails because i've not enough memory to allocate a contiguous array of that size.


