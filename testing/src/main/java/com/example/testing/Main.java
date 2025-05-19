package com.example.testing;

import com.google.inject.Guice;
import com.google.inject.Injector;

public class Main {
    public static void main(String[] args) {
        // Create Guice injector with our module
        Injector injector = Guice.createInjector(new AppModule());

        // Use injector to get instances
        System.out.println("Testing application started");

        ExampleClass example = injector.getInstance(ExampleClass.class);
        example.doSomething();
        example.doSomethingElse(5);
        ExampleClass.Request request = new ExampleClass.Request(7);
        example.doSomethingWithRequest(request);
        example.doSomethingWithRequest(request, 2);

        // Method-level annotation example
        AnotherExampleClass anotherExample = injector.getInstance(AnotherExampleClass.class);
        anotherExample.regularMethod();  // This won't be instrumented
        anotherExample.specialMethod();  // This will be instrumented

        System.out.println("Testing application finished");
    }
}
