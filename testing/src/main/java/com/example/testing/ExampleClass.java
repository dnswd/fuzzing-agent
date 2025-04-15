package com.example.testing;

import id.dendenden.Instrumented;

@Instrumented
public class ExampleClass {

    public void doSomething() {
        System.out.println("Doing something...");
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public int doSomethingElse(int value) {
        System.out.println("Doing something else with value: " + value);
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return value * 2;
    }

    public int doSomethingWithRequest(Request request) {
        System.out.println("Doing something else with value: " + request);
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return request.value * 2;
    }

    public int doSomethingWithRequest(Request request, int multiplier) {
        System.out.println("Doing something else with value: " + request);
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return request.value * 2 * multiplier;
    }

    public static class Request {
        public int value;

        public Request(int value) {
            this.value = value;
        }
    }
}