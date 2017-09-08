package fr.umlv.valuetypify;


import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import jvm.internal.value.ValueCapableClass;

class AnnotationOracle {
  static final String VALUE_CAPABLE_CLASS_NAME = 'L' + ValueCapableClass.class.getName().replace('.', '/') + ';';
  
  private final HashMap<String, Boolean> cache = new HashMap<>();
  private final Function<String, Optional<InputStream>> classFileFinder;
  
  public AnnotationOracle(Function<String, Optional<InputStream>> classFileFinder) {
    this.classFileFinder = Objects.requireNonNull(classFileFinder);
  }

  public boolean isAValueCapableClass(String className) {
    return cache.computeIfAbsent(className, this::analyzeClass);
  }
  
  static class Found extends Error {
    private static final long serialVersionUID = 1L;
    static final Found FOUND = new Found();
    
    private Found() {
      super(null, null, false, false);
    }
  }
  
  private boolean analyzeClass(String className) {
    Optional<InputStream> classFileInputStream = classFileFinder.apply(className);
    if (!classFileInputStream.isPresent()) {
      return false;
    }
    
    try(InputStream input = classFileInputStream.get()) {
      ClassReader reader = new ClassReader(input);
      reader.accept(new ClassVisitor(Opcodes.ASM6) {
        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
          if (desc.equals(VALUE_CAPABLE_CLASS_NAME)) {
            throw Found.FOUND;
          }
          return null;
        }
      }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch(Found found) {
      return true;
    }
    return false;
  }
}
