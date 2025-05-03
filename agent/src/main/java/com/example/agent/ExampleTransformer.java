package com.example.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

import java.util.ArrayList;
import java.util.List;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.NotFoundException;
import javassist.bytecode.AnnotationsAttribute;
import javassist.bytecode.ClassFile;

public class ExampleTransformer implements ClassFileTransformer {
    public static final String ANNOTATION_NAME = "id.dendenden.Instrumented";
    private final MethodRegistry registry;

    public ExampleTransformer(MethodRegistry registry) {
        this.registry = registry;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer){

        // Skip non-application classes
        if (className == null || className.startsWith("java/") || className.startsWith("sun/") ||
            className.startsWith("jdk/") || className.startsWith("com/example/annotations/")) {
            return null;
        }

        System.out.println("Transforming class: " + className);

        try {
            ClassPool cp = ClassPool.getDefault();

            // Add the current classloader to ClassPool to find our annotation class
            if (loader != null) {
                cp.insertClassPath(new javassist.LoaderClassPath(loader));
            }

            CtClass ctClass = cp.makeClass(new java.io.ByteArrayInputStream(classfileBuffer));

            // Skip interfaces and annotations
            if (ctClass.isInterface() || ctClass.isAnnotation()) {
                return null;
            }

            // Check if class has the Instrumented annotation
            ClassFile classFile = ctClass.getClassFile();
            AnnotationsAttribute visible = (AnnotationsAttribute)
                classFile.getAttribute(AnnotationsAttribute.visibleTag);

            boolean classHasAnnotation = false;
            if (visible != null) {
                classHasAnnotation = visible.getAnnotation(ANNOTATION_NAME) != null;
            }
            boolean modified = false;

            // Example: Add timing around all methods
            for (CtMethod method : ctClass.getDeclaredMethods()) {
                //// Skip abstract and native methods
                //if (method.getMethodInfo().isAbstract() || method.getMethodInfo().isNative()) {
                //    continue;
                //}

                boolean shouldInstrument = classHasAnnotation;
                // Check if method has the Instrumented annotation
                if (!shouldInstrument) {
                    AnnotationsAttribute methodAnnotations = (AnnotationsAttribute)
                        method.getMethodInfo().getAttribute(AnnotationsAttribute.visibleTag);

                    if (methodAnnotations != null &&
                        methodAnnotations.getAnnotation(ANNOTATION_NAME) != null) {
                        shouldInstrument = true;
                    }
                }

                if (shouldInstrument) {
                    System.out.println("Instrumenting method: " + method.getLongName());
                    startInstrumentation(ctClass, method);
                    modified = true;
                }
            }

            if (modified) {
                return ctClass.toBytecode();
            }
        } catch (Exception e) {
            System.err.println("Error transforming class " + className + ": " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    private void startInstrumentation(CtClass ctClass, CtMethod method)
        throws NotFoundException, CannotCompileException {
        String fqMethod = ctClass.getName();
        String methodName = method.getName();
        List<MethodRegistry.ParamInfo> paramInfoList = new ArrayList<>();
        for (CtClass param : method.getParameterTypes()) {
            List<MethodRegistry.FieldInfo> fields = new ArrayList<>();
            if (!param.isPrimitive() && !param.isArray()) {
                try {
                    for (CtField field : param.getDeclaredFields()) {
                        fields.add(new MethodRegistry.FieldInfo(field.getType().getName(),
                            field.getName()));
                    }
                } catch (NotFoundException ignored) {
                }
            }

            paramInfoList.add(new MethodRegistry.ParamInfo(param.getName(), fields));
        }
        registry.register(new MethodRegistry.MethodInfo(fqMethod, methodName, paramInfoList));

        // Add timing methods
        method.addLocalVariable("startTime", CtClass.longType);
        method.insertBefore("startTime = System.currentTimeMillis();");
        method.insertAfter("{" +
            "long endTime = System.currentTimeMillis();" +
            "System.out.println(\"" + method.getLongName() + " execution time: \" + (endTime - startTime) + \" ms\");" +
            "}");
    }
}