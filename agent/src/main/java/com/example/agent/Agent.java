package com.example.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Guice;
import com.google.inject.Injector;
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
        Injector injector = Guice.createInjector();
        Server server = Server.getInstance(methodRegistry, objectMapper);
        // Register transformer
        inst.addTransformer(new ExampleTransformer(methodRegistry), true);
    }
}
