package com.example.testing;

public class Main {
    public static void main(String[] args) {
        System.out.println("Testing application started");

        ExampleClass example = new ExampleClass();
        example.doSomething();
        example.doSomethingElse(5);
        ExampleClass.Request request = new ExampleClass.Request(7);
        example.doSomethingWithRequest(request);
        example.doSomethingWithRequest(request, 2);

        // Method-level annotation example
        AnotherExampleClass anotherExample = new AnotherExampleClass();
        anotherExample.regularMethod();  // This won't be instrumented
        anotherExample.specialMethod();  // This will be instrumented

        System.out.println("Testing application finished");
    }
}
