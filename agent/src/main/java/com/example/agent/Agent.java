package com.example.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.instrument.Instrumentation;

public class Agent {
    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("Agent loaded with premain method");
        init(agentArgs, inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        System.out.println("Agent loaded with agentmain method");
        init(agentArgs, inst);
    }

    private static void init(String agentArgs, Instrumentation inst) {
        MethodRegistry methodRegistry = new MethodRegistry();
        ObjectMapper objectMapper = new ObjectMapper();
        JaCoCoMethodCoverage coverageUtil = new JaCoCoMethodCoverage(System.out);
        Server server = Server.getInstance(methodRegistry, objectMapper, coverageUtil);
        // Register transformer
        inst.addTransformer(new ExampleTransformer(methodRegistry), true);

        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            try {
                if (!inst.isModifiableClass(clazz)) continue;
                if (clazz.getClassLoader() == null) continue;

                for (java.lang.annotation.Annotation ann : clazz.getAnnotations()) {
                    if (ann.annotationType().getName().equals(ExampleTransformer.ANNOTATION_NAME)) {
                        System.out.println("Retransforming annotated class: " + clazz.getName());
                        inst.retransformClasses(clazz);
                        break;
                    }
                }
            } catch (Throwable t) {
                System.err.println("Failed to retransform " + clazz.getName() + ": " + t);
            }
        }
    }
}
