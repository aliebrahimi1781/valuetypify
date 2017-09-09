package fr.umlv.valuetypify;

import static fr.umlv.valuetypify.ValueTypifier.State.NONE;
import static fr.umlv.valuetypify.ValueTypifier.State.OBJECT;
import static fr.umlv.valuetypify.ValueTypifier.State.VALUE;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.Opcodes.ASM6;
import static org.objectweb.asm.Opcodes.VBOX;
import static org.objectweb.asm.Opcodes.VSTORE;
import static org.objectweb.asm.Opcodes.VUNBOX;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;
import org.objectweb.asm.util.TraceMethodVisitor;

public class ValueTypifier {
  static final boolean REWRITE_VALUETYPE_ARRAY = true;
  
  final AnnotationOracle oracle;
  
  ValueTypifier(AnnotationOracle oracle) {
    this.oracle = Objects.requireNonNull(oracle);
  }
  
  boolean isVCC(Type type) {
    if (type.getSort() != Type.OBJECT) {
      return false;
    }
    return oracle.isAValueCapableClass(type.getInternalName());
  }
  
  Type asElementOfAnArrayOfVCC(Type type) {
    Type elementType;
    return (type.getSort() == Type.ARRAY && isVCC(elementType = type.getElementType()))? elementType: null;
  }
  
  static String asValueInternalName(Type type) {
    return ";Q" + type.getInternalName() + "$Value;";
  }
  static String asArrayOfValueInternalName(Type type) {
    return "[Q" + type.getInternalName() + "$Value;";
  }
  
  private static class VTValue extends BasicValue {
    final Set<AbstractInsnNode> sources;
   
    VTValue(Type type) {
      super(type);
      this.sources = new HashSet<>();
    }
    
    @Override
    public boolean equals(Object value) {
      if (!(value instanceof VTValue)) {
        return false;
      }
      return super.equals(value);
    }

    VTValue append(AbstractInsnNode source) {
      sources.add(source);
      return this;
    }
    
    VTValue appendAll(Set<AbstractInsnNode> sources) {
      sources.addAll(sources);
      return this;
    }
    
    @Override
    public int getSize() {
      return getType().getSize();
    }
  }
  
  void rewriteMethod(String owner, MethodNode methodNode, HashMap<AbstractInsnNode, Patch> patchMap, MethodVisitor mv) {
    // replace instructions
    InsnList list = methodNode.instructions;
    patchMap.forEach((insn, patch) -> {
      list.set(insn, new AbstractInsnNode(-1) {  // create a fake instruction
        @Override
        public int getType() {
          throw new UnsupportedOperationException();
        }
        
        @Override
        public AbstractInsnNode clone(Map<LabelNode, LabelNode> labels) {
          throw new UnsupportedOperationException();
        }
        
        @Override
        public void accept(MethodVisitor mv) {
          patch.accept(mv, () -> insn.accept(mv));
        }
      });
    });
    
    // generate bytecodes
    methodNode.accept(new MethodVisitor(ASM6, mv) {
      private void convertArgumentMaybe(Type parameterType, int slot) {
        if (isVCC(parameterType)) {
          visitVarInsn(ALOAD, slot);
          visitTypeInsn(VUNBOX, asValueInternalName(parameterType));
          visitVarInsn(VSTORE, slot);
        }
      }
      
      @Override
      public void visitCode() {
        super.visitCode();
        
        // add a preamble to unbox parameters if possible
        boolean isStatic = (methodNode.access & ACC_STATIC) != 0;
        if (!isStatic) {
          Type receiverType = Type.getObjectType(owner);
          convertArgumentMaybe(receiverType, 0);
        }
        
        int slot = isStatic? 0: 1;
        for(Type parameterType: Type.getArgumentTypes(methodNode.desc)) {
          convertArgumentMaybe(parameterType, slot);
          slot += parameterType.getSize();
        }
      }
      
      @Override
      public void visitMaxs(int maxStack, int maxLocals) {
        super.visitMaxs(maxStack, maxLocals);
      }
    });
  }
  
  static final BiConsumer<MethodVisitor, Type> EMPTY = (mv, type) -> { /* empty */ };
  
  enum State {
    VALUE(EMPTY,                 ValueTypifier::box),
    OBJECT(ValueTypifier::unbox, EMPTY),
    NONE(null, null)
    ;
    
    private final BiConsumer<MethodVisitor, Type> defaultState;
    private final BiConsumer<MethodVisitor, Type> boxState;
    
    private State(BiConsumer<MethodVisitor, Type> defaultState, BiConsumer<MethodVisitor, Type> boxState) {
      this.defaultState = defaultState;
      this.boxState = boxState;
    }
    
    public void accept(boolean box, MethodVisitor mv, Type type) {
      ((box)? boxState: defaultState).accept(mv, type);
    }
  }
  
  static final BiConsumer<MethodVisitor, Runnable> DEFAULT_ACTION = (__, defaultAction) -> defaultAction.run();
  
  class Patch {
    private final BiConsumer<MethodVisitor, Runnable> action;
    private final State state;
    private final Type type;
    boolean box;
    
    public Patch(BiConsumer<MethodVisitor, Runnable> action, State state, Type type) {
      this.action = Objects.requireNonNull(action);
      this.state = Objects.requireNonNull(state);
      this.type = type;
    }
    
    void accept(MethodVisitor mv, Runnable runnable) {
      action.accept(mv, runnable);
      if (state != NONE) {
        state.accept(box, mv, type);
      }
    }
  }
  
  static void box(MethodVisitor mv, Type type) {
    mv.visitTypeInsn(VBOX, type.getInternalName());
  }
  static void unbox(MethodVisitor mv, Type type) {
    mv.visitTypeInsn(VUNBOX, asValueInternalName(type));
  }
  
  static boolean isValueType(BasicValue value) {
    return value instanceof VTValue;
  }
  
  
  
  private void convert(OutputStream outputStream, InputStream inputStream) throws IOException {
    ClassReader reader = new ClassReader(inputStream);
    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES);
    ClassVisitor visitor = writer;
    if (REWRITE_VALUETYPE_ARRAY) {
      visitor = new ClassRemapper(writer, new Remapper() {
        @Override
        public String mapDesc(String desc) {
          Type type = Type.getType(desc);
          Type elementType = asElementOfAnArrayOfVCC(type);
          return (elementType == null)? desc: asArrayOfValueInternalName(elementType);
        }
      });
    }
    reader.accept(new ClassVisitor(ASM6, visitor) {
      String owner;
      
      @Override
      public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        super.visit(version, access, name, signature, superName, interfaces);
        owner = name;
      }
      
      @Override
      public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor methodWriter = super.visitMethod(access, name, desc, signature, exceptions);
        
        // do not rewrite constructors too much (FIXME !)
        if (name.equals("<init>")) {
          if (REWRITE_VALUETYPE_ARRAY) {
            return new MethodVisitor(ASM6, methodWriter) {
              @Override
              public void visitTypeInsn(int opcode, String type) {
                if (opcode == ANEWARRAY) {
                  Type elementType = Type.getObjectType(type);
                  if (isVCC(elementType)) {
                    super.visitTypeInsn(ANEWARRAY, asValueInternalName(elementType));
                    return;
                  }
                }
                super.visitTypeInsn(opcode, type);
              }
            };
          } else {
            return methodWriter;
          }
        }
        
        return new MethodNode(ASM6, access, name, desc, signature, exceptions) {
          final HashMap<AbstractInsnNode, Patch> patchMap = new HashMap<>();
          
          void patch(AbstractInsnNode insn, Patch patch) {
            patchMap.put(insn, patch);
          }
          void markBoxed(BasicValue value) {
            Set<AbstractInsnNode> sources = ((VTValue)value).sources;
            sources.forEach(source -> patchMap.get(source).box = true); 
          }
          
          @Override
          public void visitEnd() {
            super.visitEnd();
            
            // analyze the method
            Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter(ASM6) {
              private int newValueState = 0;
              
              @Override
              public BasicValue newValue(Type type) {
                switch(newValueState++) {
                case 0:  // return type
                  return super.newValue(type);
                  
                default: // this type, parameter type & local type
                  if (type == null) {  // uninitialized or top for double/long
                    return super.newValue(type);
                  }
                  
                  if (isVCC(type)) {
                    return new VTValue(type);
                  }
                  BasicValue value = super.newValue(type);
                  // use precise type for objects and arrays
                  return (value == BasicValue.REFERENCE_VALUE)? new BasicValue(type): value;  
                }
              }

              @Override
              public BasicValue newOperation(AbstractInsnNode insn) throws AnalyzerException {
                switch(insn.getOpcode()) {
                case GETSTATIC: {
                  FieldInsnNode fieldInsn = (FieldInsnNode)insn;
                  Type type = Type.getType(fieldInsn.desc);
                  if (isVCC(type) ) {
                    patch(insn, new Patch(DEFAULT_ACTION, VALUE, type));
                    return new VTValue(type).append(insn);
                  }
                  return super.newOperation(insn);
                }
                default:
                  return super.newOperation(insn);
                }
              }

              @Override
              public BasicValue copyOperation(AbstractInsnNode insn, BasicValue value) throws AnalyzerException {
                switch(insn.getOpcode()) {
                case ASTORE: {
                  if (!isValueType(value)) {
                    return super.copyOperation(insn, value);
                  }
                  VarInsnNode varInsn = (VarInsnNode)insn;
                  patch(insn, new Patch((mv, __) -> mv.visitVarInsn(VSTORE, varInsn.var), NONE, null));
                  return new VTValue(value.getType());
                }
                case ALOAD:
                  if (!isValueType(value)) {
                    return super.copyOperation(insn, value);
                  }
                  VarInsnNode varInsn = (VarInsnNode)insn;
                  patch(insn, new Patch((mv, __) -> mv.visitVarInsn(VLOAD, varInsn.var), VALUE, value.getType()));
                  return new VTValue(value.getType()).append(insn);
                default:
                  return super.copyOperation(insn, value);
                }
              }

              @Override
              public BasicValue unaryOperation(AbstractInsnNode insn, BasicValue value) throws AnalyzerException {
                switch(insn.getOpcode()) {
                case MONITORENTER:
                case MONITOREXIT:
                  if (isValueType(value)) {
                    throw new AnalyzerException(insn, "try to enter/exit a monitor on a value type !!");
                  }
                  return super.unaryOperation(insn, value);
                  
                case PUTSTATIC:
                case INSTANCEOF:
                case ATHROW:
                case IFNULL:       // TODO revisit
                case IFNONNULL: {  // TODO revisit
                  if (!isValueType(value)) {
                    return super.unaryOperation(insn, value);
                  }
                  markBoxed(value);
                  return null;
                }
                case GETFIELD: {
                  FieldInsnNode fieldInsn = (FieldInsnNode)insn;
                  Type type = Type.getType(fieldInsn.desc);
                  patch(insn, new Patch((mv, defaultAction) -> {
                      if (isValueType(value)) {
                        mv.visitFieldInsn(GETFIELD, asValueInternalName(Type.getObjectType(fieldInsn.owner)), fieldInsn.name, fieldInsn.desc);
                      } else {
                        defaultAction.run();
                      }
                    },
                    VALUE, type));
                  return isVCC(type)? new VTValue(type).append(insn): super.unaryOperation(insn, value);
                }
                case CHECKCAST: {
                  TypeInsnNode typeInsn = (TypeInsnNode)insn;
                  Type type = Type.getObjectType(typeInsn.desc);
                  if (isValueType(value)) {
                    markBoxed(value);
                  }
                  if (isVCC(type)) {
                    patch(insn, new Patch(DEFAULT_ACTION, OBJECT, type));
                    return new VTValue(type).append(insn);
                  }
                  return super.unaryOperation(insn, value);
                }
                  
                case ANEWARRAY:
                  if (REWRITE_VALUETYPE_ARRAY) {
                    TypeInsnNode typeInsn = (TypeInsnNode)insn;
                    Type elementType = Type.getObjectType(typeInsn.desc);
                    if (isVCC(elementType)) {
                      patch(insn, new Patch((mv, __) -> mv.visitTypeInsn(ANEWARRAY, asValueInternalName(elementType)), NONE, null));
                    }
                  }
                  return super.unaryOperation(insn, value);
                  
                default:
                  return super.unaryOperation(insn, value);
                }
              }

              @Override
              public BasicValue binaryOperation(AbstractInsnNode insn, BasicValue value1, BasicValue value2) throws AnalyzerException {
                switch(insn.getOpcode()) {
                case IF_ACMPEQ:
                case IF_ACMPNE: {
                  if (isValueType(value1)) {
                    markBoxed(value1);
                  }
                  if (isValueType(value2)) {
                    markBoxed(value2);
                  }
                  return super.binaryOperation(insn, value1, value2);
                }
                case PUTFIELD:
                  if (isValueType(value2)) {
                    markBoxed(value2);
                  }
                  return super.binaryOperation(insn, value1, value2);
                case AALOAD: {
                  Type elementType = asElementOfAnArrayOfVCC(value1.getType());
                  if (elementType != null) {
                    if (REWRITE_VALUETYPE_ARRAY) {
                      patch(insn, new Patch((mv, __) -> mv.visitInsn(VALOAD), VALUE, elementType));
                    } else {
                      patch(insn, new Patch(DEFAULT_ACTION, OBJECT, elementType));
                    }
                    return new VTValue(elementType).append(insn);
                  }
                  return super.binaryOperation(insn, value1, value2);
                }
                default:
                }
                return super.binaryOperation(insn, value1, value2);
              }

              @Override
              public BasicValue ternaryOperation(AbstractInsnNode insn, BasicValue value1, BasicValue value2, BasicValue value3) throws AnalyzerException {
                switch(insn.getOpcode()) {
                case AASTORE:
                  if (isValueType(value3)) {
                    if (REWRITE_VALUETYPE_ARRAY) {
                      Type elementType = value3.getType();
                      patch(insn, new Patch((mv, __) -> mv.visitInsn(VASTORE), VALUE, elementType));
                    } else {
                      markBoxed(value3);
                    }
                  }
                  return super.ternaryOperation(insn, value1, value2, value3);
                default:
                  return super.ternaryOperation(insn, value1, value2, value3);
                }
              }

              @Override
              public BasicValue naryOperation(AbstractInsnNode insn, List<? extends BasicValue> values) throws AnalyzerException {
                for(BasicValue value: values) {
                  if (isValueType(value)) {
                    markBoxed(value);
                  }
                }
                
                String desc, name;
                int opcode = insn.getOpcode();
                switch(opcode) {
                case MULTIANEWARRAY:
                  return super.naryOperation(insn, values);
                case INVOKEDYNAMIC: {
                  InvokeDynamicInsnNode indyInsn = (InvokeDynamicInsnNode)insn;
                  desc = indyInsn.desc;
                  name = "";
                  break;
                }
                default: {
                  MethodInsnNode methodInsn = (MethodInsnNode)insn;
                  desc = methodInsn.desc;
                  name = methodInsn.name;
                }
                }
                Type type = Type.getReturnType(desc);
                if (isVCC(type)) {
                  patch(insn, new Patch(DEFAULT_ACTION, OBJECT, type));
                  return new VTValue(type).append(insn);
                }
                
                // may need to unbox after the call to the constructor
                if (opcode == INVOKESPECIAL && name.equals("<init>")) { // constructor call
                  BasicValue receiverValue =  values.get(0); 
                  if (isValueType(receiverValue)) {                     // receiver is a value type
                    patch(insn, new Patch(DEFAULT_ACTION, OBJECT, receiverValue.getType()));
                    
                    // register to emit the unbox or not
                    ((VTValue)receiverValue).append(insn);
                  }
                }
                
                return super.naryOperation(insn, values);
              }

              @Override
              public void returnOperation(AbstractInsnNode insn, BasicValue value, BasicValue expected) throws AnalyzerException {
                switch(insn.getOpcode()) {
                case ARETURN: {
                  if (!isValueType(value)) {
                    return;
                  }
                  markBoxed(value);
                  return;
                }
                default:
                  return;
                }
              }

              @Override
              public BasicValue merge(BasicValue v, BasicValue w) {
                if (!isValueType(v)) {
                  if (!isValueType(w)) {
                    return super.merge(v, w);
                  }
                  markBoxed(w);
                  return super.merge(v, w);
                } else {
                  if (!isValueType(w)) {
                    markBoxed(v);
                    return super.merge(v, w);
                  }
                }
                if (v.getType().equals(w.getType())) {
                  return new VTValue(v.getType()).appendAll(((VTValue)v).sources).appendAll(((VTValue)w).sources);
                }
                markBoxed(w);
                markBoxed(v);
                return super.merge(v, w);
              }

            });
            
            try {
              analyzer.analyze(owner, this);
            } catch (AnalyzerException e) {
              throw new UncheckedIOException(new IOException(e));
            }
            
            //DEBUG
            //TraceClassVisitor traceClassVisitor = new TraceClassVisitor(new PrintWriter(System.err));
            //rewriteMethod(owner, this, patchMap,
            //    traceClassVisitor.visitMethod(access, name, desc, signature, exceptions.toArray(new String[0])));
            //traceClassVisitor.visitEnd();
            
            rewriteMethod(owner, this, patchMap, methodWriter);
          }

          
        };
      }
    }, 0);

    byte[] code = writer.toByteArray();
    
    // DEBUG
    ClassReader reader2 = new ClassReader(code);
    reader2.accept(new TraceClassVisitor(new PrintWriter(System.err)), 0);
    
    outputStream.write(code);
  }
  
  private static void copy(OutputStream jarOutputStream, InputStream inputStream) throws IOException {
    int read;
    byte[] buffer = new byte[8192];
    while((read = inputStream.read()) != -1) {
      jarOutputStream.write(buffer, 0, read);
    }
  }


  private JarEntry convert(JarFile input, JarOutputStream jarOutputStream, JarEntry entry) throws IOException {
    String name = entry.getName();
    JarEntry newEntry = new JarEntry(name);
    jarOutputStream.putNextEntry(newEntry);
    try(InputStream inputStream = input.getInputStream(entry)) {
      if (name.endsWith(".class")) {
        convert(jarOutputStream, inputStream);
      } else {
        copy(jarOutputStream, inputStream);
      }
    }
    return newEntry;
  }

  static interface IOConsumer<T> {
    public void accept(T t) throws IOException;

    static <T> Consumer<T> unchecked(IOConsumer<? super T> consumer) {
      return t -> {
        try {
          consumer.accept(t);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      };
    }
  }
  static interface IOFunction<T, U> {
    public U apply(T t) throws IOException;

    static <T, U> Function<T, U> unchecked(IOFunction<? super T, ? extends U> function) {
      return t -> {
        try {
          return function.apply(t);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      };
    }
  }


  private static String toOutputName(String pathName) {
    return pathName.substring(0, pathName.length() - ".jar".length()) + "-valuetypified.jar";
  }

  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      System.err.println("java fr.umlv.valuetypifier foo.jar");
      return;
    }
    
    Path path = Paths.get(args[0]);
    String pathName = path.toString();
    Path outputPath = Paths.get(toOutputName(pathName));
    
    try(JarFile input = new JarFile(pathName);
        JarOutputStream jarOutputStream = new JarOutputStream(Files.newOutputStream(outputPath));
        Stream<JarEntry> stream = input.stream()) {
      AnnotationOracle oracle = new AnnotationOracle(
          name -> Optional.ofNullable(input.getJarEntry(name + ".class")).map(IOFunction.unchecked(input::getInputStream)));
      ValueTypifier valueTypifier = new ValueTypifier(oracle);
      
      stream.forEach(IOConsumer.unchecked(entry -> valueTypifier.convert(input, jarOutputStream, entry)));
    }    
    
    System.out.println(outputPath + " generated");
  }  
}
